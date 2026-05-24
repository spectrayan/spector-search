package com.spectrayan.spector.bench;

import com.spectrayan.spector.core.similarity.SimilarityFunction;
import com.spectrayan.spector.index.HnswParams;
import com.spectrayan.spector.index.ScoredResult;
import com.spectrayan.spector.index.spectrum.SpectorIndex;

import java.util.Arrays;
import java.util.Random;

/**
 * Large-scale benchmark for {@link SpectorIndex} (IVF-HNSW-VASQ) at 500K–1M vectors.
 *
 * <p>Tests the hypothesis that SpectorIndex overtakes plain HNSW at large scale
 * by measuring ingestion speed, search latency, throughput, and recall@10.</p>
 *
 * <p>Run: {@code java --add-modules jdk.incubator.vector -Xmx12g -cp ... SpectorIndexLargeScaleBench}</p>
 */
public class SpectorIndexLargeScaleBench {

    private static final int WARMUP = 100;
    private static final int MEASURE = 500;
    private static final int N_QUERIES = 50;

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║   SPECTOR INDEX — LARGE SCALE BENCHMARK (500K–1M)       ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.printf("  CPUs: %d  |  Max Heap: %d MB%n",
                Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory() / (1024 * 1024));
        System.out.println();

        // 500K with 128 centroids
        runForSize(500_000, 128, 128, new int[]{8, 16, 32, 64});

        // 1M with 256 centroids
        runForSize(1_000_000, 128, 256, new int[]{8, 16, 32, 64, 128});

        System.out.println("═══════════════════════════════════════════════════════════");
    }

    private static void runForSize(int datasetSize, int dims, int nCentroids, int[] nProbes) {
        System.out.printf("▶ Dataset: %,d vectors, %d dims, %d centroids%n", datasetSize, dims, nCentroids);
        long memBefore = usedMemoryMB();

        Random rng = new Random(42L);

        // Generate all vectors upfront
        System.out.printf("  Generating %,d vectors...%n", datasetSize);
        float[][] allVectors = new float[datasetSize][];
        for (int i = 0; i < datasetSize; i++) {
            allVectors[i] = gaussianUnit(rng, dims);
        }
        long memAfterVecs = usedMemoryMB();
        System.out.printf("  Vector memory: +%d MB%n", memAfterVecs - memBefore);

        // Train sample
        int trainSize = Math.min(50_000, datasetSize);
        float[][] trainVectors = new float[trainSize][];
        System.arraycopy(allVectors, 0, trainVectors, 0, trainSize);

        // Prepare queries
        Random qrng = new Random(999L);
        float[][] queries = new float[N_QUERIES][dims];
        for (int q = 0; q < N_QUERIES; q++) queries[q] = gaussianUnit(qrng, dims);

        // Compute ground truth via brute-force
        System.out.printf("  Computing ground truth (%d queries × %,d vectors)...%n", N_QUERIES, datasetSize);
        long gtStart = System.nanoTime();
        int[][] groundTruth = computeGroundTruth(allVectors, queries, 10);
        long gtMs = (System.nanoTime() - gtStart) / 1_000_000;
        System.out.printf("  Ground truth computed in %,dms%n", gtMs);

        // Build and benchmark each nProbe
        System.out.println();
        System.out.printf("  %-8s  %-12s  %-12s  %-12s  %-10s  %-10s  %-12s%n",
                "nProbe", "avg (ms)", "p50 (ms)", "p99 (ms)", "QPS", "recall@10", "ingest (ms)");
        System.out.println("  " + "-".repeat(84));

        for (int nProbe : nProbes) {
            // Build index
            SpectorIndex probeIndex = SpectorIndex.builder()
                    .dimensions(dims)
                    .nCentroids(nCentroids)
                    .nProbe(nProbe)
                    .shardThreshold(20_000)
                    .oversamplingFactor(4)
                    .similarityFunction(SimilarityFunction.COSINE)
                    .hnswParams(new HnswParams(16, 128, 64))
                    .build();

            // Train
            long t0 = System.nanoTime();
            probeIndex.train(trainVectors);
            long trainMs = (System.nanoTime() - t0) / 1_000_000;

            // Ingest
            t0 = System.nanoTime();
            for (int i = 0; i < datasetSize; i++) {
                probeIndex.add("doc-" + i, i, allVectors[i]);
            }
            long ingestMs = (System.nanoTime() - t0) / 1_000_000;

            // Warmup
            for (int w = 0; w < WARMUP; w++) {
                probeIndex.search(queries[w % N_QUERIES], 10);
            }

            // Measure
            long[] nanos = new long[MEASURE];
            ScoredResult[][] results = new ScoredResult[N_QUERIES][];
            for (int m = 0; m < MEASURE; m++) {
                int q = m % N_QUERIES;
                long start = System.nanoTime();
                results[q] = probeIndex.search(queries[q], 10);
                nanos[m] = System.nanoTime() - start;
            }

            // Compute recall
            double recall = computeRecall(results, groundTruth, N_QUERIES);

            // Stats
            Arrays.sort(nanos);
            double avg = Arrays.stream(nanos).average().orElse(0) / 1e6;
            double p50 = nanos[MEASURE / 2] / 1e6;
            double p99 = nanos[(int) (MEASURE * 0.99)] / 1e6;
            double qps = 1e9 / (Arrays.stream(nanos).average().orElse(1));

            System.out.printf("  %-8d  %-12.3f  %-12.3f  %-12.3f  %-10.0f  %-10.4f  %-12d%n",
                    nProbe, avg, p50, p99, qps, recall, ingestMs);

            probeIndex.close();
        }

        long memAfterAll = usedMemoryMB();
        System.out.printf("%n  Total memory used: +%d MB (vectors: +%d MB)%n", memAfterAll - memBefore, memAfterVecs - memBefore);
        System.out.println();
    }

    private static int[][] computeGroundTruth(float[][] data, float[][] queries, int k) {
        int[][] truth = new int[queries.length][k];
        for (int q = 0; q < queries.length; q++) {
            // Use partial sort via min-heap for efficiency at large scale
            float[] sims = new float[data.length];
            for (int i = 0; i < data.length; i++) {
                sims[i] = cosine(queries[q], data[i]);
            }
            // Find top-k via partial sort
            Integer[] indices = new Integer[data.length];
            for (int i = 0; i < data.length; i++) indices[i] = i;
            // Partial sort: only need top-k
            Arrays.sort(indices, (a, b) -> Float.compare(sims[b], sims[a]));
            for (int i = 0; i < k; i++) truth[q][i] = indices[i];
        }
        return truth;
    }

    private static double computeRecall(ScoredResult[][] results,
                                         int[][] groundTruth, int nQueries) {
        int hits = 0;
        int total = 0;
        for (int q = 0; q < nQueries; q++) {
            if (results[q] == null) continue;
            var truthSet = new java.util.HashSet<Integer>();
            for (int idx : groundTruth[q]) truthSet.add(idx);
            for (ScoredResult r : results[q]) {
                if (truthSet.contains(r.index())) hits++;
            }
            total += groundTruth[q].length;
        }
        return total > 0 ? (double) hits / total : 0;
    }

    private static float cosine(float[] a, float[] b) {
        float dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return (float) (dot / (Math.sqrt(normA) * Math.sqrt(normB)));
    }

    private static float[] gaussianUnit(Random rng, int dims) {
        float[] v = new float[dims];
        double norm = 0;
        for (int i = 0; i < dims; i++) {
            v[i] = (float) rng.nextGaussian();
            norm += (double) v[i] * v[i];
        }
        float scale = (float) (1.0 / Math.sqrt(norm));
        for (int i = 0; i < dims; i++) v[i] *= scale;
        return v;
    }

    private static long usedMemoryMB() {
        Runtime.getRuntime().gc();
        return (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
    }
}
