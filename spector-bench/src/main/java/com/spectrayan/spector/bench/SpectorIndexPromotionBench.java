/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.spectrayan.spector.bench;

import com.spectrayan.spector.core.similarity.SimilarityFunction;
import com.spectrayan.spector.config.HnswParams;
import com.spectrayan.spector.index.ScoredResult;
import com.spectrayan.spector.index.spectrum.SpectorIndex;

import java.util.Arrays;
import java.util.Random;

/**
 * Benchmark to evaluate SpectorIndex performance before and after HNSW shard promotion.
 *
 * <p>Compares Flat-mode shards (exhaustive SIMD scans over float32 residuals)
 * against Promoted-HNSW shards (SVASQ-quantized local HNSW search) at 100K scale.</p>
 *
 * <p>Run: {@code java --add-modules jdk.incubator.vector -Xmx12g -cp ... SpectorIndexPromotionBench}</p>
 */
public class SpectorIndexPromotionBench {

    private static final int WARMUP = 100;
    private static final int MEASURE = 500;
    private static final int DATASET_SIZE = 100_000;
    private static final int DIMENSIONS = 128;
    private static final int N_CENTROIDS = 32;
    private static final int N_QUERIES = 100;

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║    SPECTOR INDEX — SHARD PROMOTION BENCHMARK (100K)      ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.printf("  CPUs: %d  |  Max Heap: %d MB%n",
                Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory() / (1024 * 1024));
        System.out.println();

        // 1. Generate dataset
        System.out.printf("Generating %,d random %d-dim vectors...%n", DATASET_SIZE, DIMENSIONS);
        Random rng = new Random(42L);
        float[][] dataset = new float[DATASET_SIZE][DIMENSIONS];
        for (int i = 0; i < DATASET_SIZE; i++) {
            dataset[i] = gaussianUnit(rng, DIMENSIONS);
        }

        // 2. Prepare queries and ground truth (exact L2 top-10)
        System.out.printf("Generating %d query vectors and computing ground truth...%n", N_QUERIES);
        Random qrng = new Random(999L);
        float[][] queries = new float[N_QUERIES][DIMENSIONS];
        for (int q = 0; q < N_QUERIES; q++) {
            queries[q] = gaussianUnit(qrng, DIMENSIONS);
        }
        int[][] groundTruth = computeGroundTruth(dataset, queries, 10);

        System.out.println("\n═══════════════════════════════════════════════════════════");
        System.out.println("1. BENCHMARKING: FLAT SHARD MODE (No Promotion, shardThreshold=100K)");
        System.out.println("═══════════════════════════════════════════════════════════");
        runBenchmark(dataset, queries, groundTruth, 100_000);

        System.out.println("\n═══════════════════════════════════════════════════════════");
        System.out.println("2. BENCHMARKING: PROMOTED HNSW SHARD MODE (shardThreshold=1,000)");
        System.out.println("═══════════════════════════════════════════════════════════");
        runBenchmark(dataset, queries, groundTruth, 1_000);

        System.out.println("═══════════════════════════════════════════════════════════");
    }

    private static void runBenchmark(float[][] dataset, float[][] queries, int[][] groundTruth, int shardThreshold) {
        long memBefore = usedMemoryMB();

        // 1. Build index configuration
        SpectorIndex index = SpectorIndex.builder()
                .dimensions(DIMENSIONS)
                .nCentroids(N_CENTROIDS)
                .nProbe(8) // default
                .shardThreshold(shardThreshold)
                .oversamplingFactor(3)
                .similarityFunction(SimilarityFunction.COSINE)
                .hnswParams(new HnswParams(16, 128, 64))
                .build();

        // 2. Train (using 10K sample)
        int trainSize = 10_000;
        float[][] trainVecs = Arrays.copyOf(dataset, trainSize);
        long t0 = System.nanoTime();
        index.train(trainVecs);
        long trainMs = (System.nanoTime() - t0) / 1_000_000;

        // 3. Ingest
        t0 = System.nanoTime();
        for (int i = 0; i < DATASET_SIZE; i++) {
            index.add("doc-" + i, i, dataset[i]);
        }
        long ingestMs = (System.nanoTime() - t0) / 1_000_000;
        long memAfterIngest = usedMemoryMB();
        long memAdded = memAfterIngest - memBefore;

        System.out.printf("  Ingestion: %dms (%.0f docs/s) | Memory added: %d MB%n",
                ingestMs, DATASET_SIZE / (ingestMs / 1000.0), memAdded);
        System.out.println();

        // 4. Test different nProbe configurations
        int[] nProbes = {4, 8, 16, 32};
        System.out.printf("  %-8s  %-12s  %-12s  %-12s  %-10s  %-10s%n",
                "nProbe", "avg (ms)", "p50 (ms)", "p99 (ms)", "QPS", "recall@10");
        System.out.println("  " + "-".repeat(72));

        for (int nProbe : nProbes) {
            // Reconfigure nProbe
            SpectorIndex probeIndex = SpectorIndex.builder()
                    .dimensions(DIMENSIONS)
                    .nCentroids(N_CENTROIDS)
                    .nProbe(nProbe)
                    .shardThreshold(shardThreshold)
                    .oversamplingFactor(3)
                    .similarityFunction(SimilarityFunction.COSINE)
                    .hnswParams(new HnswParams(16, 128, 64))
                    .build();

            probeIndex.train(trainVecs);
            for (int i = 0; i < DATASET_SIZE; i++) {
                probeIndex.add("doc-" + i, i, dataset[i]);
            }

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

            // Compute stats
            double recall = computeRecall(results, groundTruth, N_QUERIES);
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
            Integer[] indices = new Integer[data.length];
            for (int i = 0; i < data.length; i++) indices[i] = i;
            Arrays.sort(indices, (a, b) -> Float.compare(dists[a], dists[b]));
            for (int i = 0; i < k; i++) truth[q][i] = indices[i];
        }
        return truth;
    }

    private static double computeRecall(ScoredResult[][] results, int[][] groundTruth, int nQueries) {
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

    private static long usedMemoryMB() {
        System.gc();
        try { Thread.sleep(100); } catch (Exception ignored) {}
        System.gc();
        return (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
    }
}
