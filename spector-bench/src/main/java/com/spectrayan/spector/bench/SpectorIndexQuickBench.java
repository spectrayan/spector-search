package com.spectrayan.spector.bench;

import com.spectrayan.spector.core.similarity.SimilarityFunction;
import com.spectrayan.spector.index.HnswParams;
import com.spectrayan.spector.index.ScoredResult;
import com.spectrayan.spector.index.spectrum.SpectorIndex;

import java.util.Arrays;
import java.util.Random;

/**
 * Quick direct-measurement benchmark for {@link SpectorIndex} (IVF-HNSW-VASQ).
 *
 * <p>Measures search latency, throughput, and recall at various nProbe and dataset
 * sizes without requiring JMH annotation processing. Outputs a console table
 * for direct comparison with the documentation.</p>
 *
 * <p>Run: {@code java --add-modules jdk.incubator.vector -cp ... SpectorIndexQuickBench}</p>
 */
public class SpectorIndexQuickBench {

    private static final int WARMUP = 100;
    private static final int MEASURE = 500;

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║     SPECTOR INDEX (IVF-HNSW-VASQ) QUICK BENCHMARK       ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();

        int[] sizes = {10_000, 50_000, 100_000};
        int[] nProbes = {4, 8, 16, 32};
        int dims = 128;

        for (int size : sizes) {
            runForSize(size, dims, nProbes);
        }

        System.out.println("═══════════════════════════════════════════════════════════");
    }

    private static void runForSize(int datasetSize, int dims, int[] nProbes) {
        System.out.printf("▶ Dataset: %,d vectors, %d dims, 32 centroids%n", datasetSize, dims);

        Random rng = new Random(42L);
        int nCentroids = 32;

        // Build index with default nProbe (will override per-query later)
        SpectorIndex index = SpectorIndex.builder()
                .dimensions(dims)
                .nCentroids(nCentroids)
                .nProbe(8)
                .shardThreshold(20_000)
                .oversamplingFactor(3)
                .similarityFunction(SimilarityFunction.COSINE)
                .hnswParams(new HnswParams(16, 128, 64))
                .build();

        // Train
        int trainSize = Math.min(10_000, datasetSize);
        float[][] trainVectors = new float[trainSize][dims];
        for (int i = 0; i < trainSize; i++) trainVectors[i] = gaussianUnit(rng, dims);

        long t0 = System.nanoTime();
        index.train(trainVectors);
        long trainMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("  Train: %dms (%d vectors)%n", trainMs, trainSize);

        // Ingest
        float[][] allVectors = new float[datasetSize][dims];
        t0 = System.nanoTime();
        for (int i = 0; i < datasetSize; i++) {
            allVectors[i] = gaussianUnit(rng, dims);
            index.add("doc-" + i, i, allVectors[i]);
        }
        long ingestMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("  Ingest: %dms (%.0f docs/s)%n", ingestMs,
                datasetSize / (ingestMs / 1000.0));

        // Prepare queries + ground truth (brute-force top-10)
        int nQueries = 100;
        float[][] queries = new float[nQueries][dims];
        Random qrng = new Random(999L);
        for (int q = 0; q < nQueries; q++) queries[q] = gaussianUnit(qrng, dims);

        // Compute exact top-10 via brute force L2 for recall measurement
        // (SpectorIndex uses L2 internally for IVF residual search)
        int[][] groundTruth = computeGroundTruth(allVectors, queries, 10);

        System.out.println();
        System.out.printf("  %-8s  %-12s  %-12s  %-12s  %-10s  %-10s%n",
                "nProbe", "avg (ms)", "p50 (ms)", "p99 (ms)", "QPS", "recall@10");
        System.out.println("  " + "-".repeat(72));

        for (int nProbe : nProbes) {
            // Rebuild with this nProbe
            SpectorIndex probeIndex = SpectorIndex.builder()
                    .dimensions(dims)
                    .nCentroids(nCentroids)
                    .nProbe(nProbe)
                    .shardThreshold(20_000)
                    .oversamplingFactor(3)
                    .similarityFunction(SimilarityFunction.COSINE)
                    .hnswParams(new HnswParams(16, 128, 64))
                    .build();

            probeIndex.train(trainVectors);
            for (int i = 0; i < datasetSize; i++) {
                probeIndex.add("doc-" + i, i, allVectors[i]);
            }

            // Warmup
            for (int w = 0; w < WARMUP; w++) {
                probeIndex.search(queries[w % nQueries], 10);
            }

            // Measure
            long[] nanos = new long[MEASURE];
            ScoredResult[][] results = new ScoredResult[nQueries][];
            for (int m = 0; m < MEASURE; m++) {
                int q = m % nQueries;
                long start = System.nanoTime();
                results[q] = probeIndex.search(queries[q], 10);
                nanos[m] = System.nanoTime() - start;
            }

            // Compute recall
            double recall = computeRecall(results, groundTruth, nQueries);

            // Stats
            Arrays.sort(nanos);
            double avg = Arrays.stream(nanos).average().orElse(0) / 1e6;
            double p50 = nanos[MEASURE / 2] / 1e6;
            double p99 = nanos[(int) (MEASURE * 0.99)] / 1e6;
            double qps = 1e9 / (Arrays.stream(nanos).average().orElse(1));

            System.out.printf("  %-8d  %-12.3f  %-12.3f  %-12.3f  %-10.0f  %-10.4f%n",
                    nProbe, avg, p50, p99, qps, recall);

            probeIndex.close();
        }

        index.close();
        System.out.println();
    }

    private static int[][] computeGroundTruth(float[][] data, float[][] queries, int k) {
        int[][] truth = new int[queries.length][k];
        for (int q = 0; q < queries.length; q++) {
            float[] dists = new float[data.length];
            for (int i = 0; i < data.length; i++) {
                dists[i] = l2Squared(queries[q], data[i]);
            }
            // Find top-k indices by L2 distance (lowest = closest)
            Integer[] indices = new Integer[data.length];
            for (int i = 0; i < data.length; i++) indices[i] = i;
            Arrays.sort(indices, (a, b) -> Float.compare(dists[a], dists[b]));
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

    private static float l2Squared(float[] a, float[] b) {
        float sum = 0;
        for (int i = 0; i < a.length; i++) {
            float d = a[i] - b[i];
            sum += d * d;
        }
        return sum;
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
}
