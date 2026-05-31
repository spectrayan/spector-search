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
import com.spectrayan.spector.index.HnswIndex;
import com.spectrayan.spector.config.HnswParams;
import com.spectrayan.spector.index.ScoredResult;
import com.spectrayan.spector.index.spectrum.SpectorIndex;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/**
 * Standard ANN benchmark using the SIFT1M dataset.
 *
 * <p>SIFT1M is the canonical dataset for ANN algorithm comparison (ann-benchmarks.github.io).
 * It contains 1 million 128-dimensional SIFT descriptors, 10,000 queries, and precomputed
 * ground-truth nearest neighbors for the top 100 candidates per query.</p>
 *
 * <h3>Dataset download</h3>
 * <pre>
 *   # Download from http://corpus-texmex.irisa.fr/
 *   wget ftp://ftp.irisa.fr/local/texmex/corpus/sift.tar.gz
 *   tar -xf sift.tar.gz
 * </pre>
 *
 * <h3>Running</h3>
 * <p>This is a standalone main-class benchmark (not JMH) because the 1M-vector
 * setup cost is too high for JMH's fork/warmup lifecycle. It measures:</p>
 * <ul>
 *   <li>Ingest throughput (vectors/sec)</li>
 *   <li>Recall@10 at various nProbe values {1, 2, 4, 8, 16, 32}</li>
 *   <li>QPS (queries/sec) at each nProbe</li>
 * </ul>
 *
 * <p>Run via:</p>
 * <pre>
 *   mvn -pl spector-bench exec:java \
 *     -Dexec.mainClass=com.spectrayan.spector.bench.Sift1MAnnBenchmark \
 *     -Dexec.args="path/to/sift"
 * </pre>
 */
public class Sift1MAnnBenchmark {

    private static final int DIMS        = 128;
    private static final int K           = 10;
    private static final int N_CENTROIDS = 256;
    private static final int TRAIN_SIZE  = 50_000;  // K-Means sample from base

    public static void main(String[] args) throws Exception {
        String dataDir = args.length > 0 ? args[0] : "sift";

        System.out.println("=== Spector SIFT1M ANN Benchmark ===");
        System.out.println("Dataset: " + dataDir);

        // ── Load dataset ──────────────────────────────────────────────────────
        System.out.print("Loading sift_base.fvecs (1M × 128)... ");
        float[][] base    = readFvecs(Paths.get(dataDir, "sift_base.fvecs"));
        System.out.printf("done (%d vectors)%n", base.length);

        System.out.print("Loading sift_query.fvecs (10K × 128)... ");
        float[][] queries = readFvecs(Paths.get(dataDir, "sift_query.fvecs"));
        System.out.printf("done (%d queries)%n", queries.length);

        System.out.print("Loading sift_groundtruth.ivecs (10K × 100)... ");
        int[][] gt        = readIvecs(Paths.get(dataDir, "sift_groundtruth.ivecs"));
        System.out.printf("done (%d × %d ground truth)%n", gt.length, gt[0].length);

        // ── Train SpectorIndex ────────────────────────────────────────────────
        System.out.printf("%nBuilding SpectorIndex (nCentroids=%d)...%n", N_CENTROIDS);

        // Sample for K-Means training
        Random rng = new Random(42L);
        float[][] trainSample = new float[TRAIN_SIZE][];
        int[] perm = new int[base.length];
        for (int i = 0; i < perm.length; i++) perm[i] = i;
        for (int i = 0; i < TRAIN_SIZE; i++) {
            int j = i + rng.nextInt(perm.length - i);
            int tmp = perm[i]; perm[i] = perm[j]; perm[j] = tmp;
            trainSample[i] = base[perm[i]];
        }

        SpectorIndex index = SpectorIndex.builder()
                .dimensions(DIMS)
                .nCentroids(N_CENTROIDS)
                .nProbe(16)              // will be overridden per run
                .shardThreshold(20_000)
                .oversamplingFactor(3)
                .similarityFunction(SimilarityFunction.EUCLIDEAN)  // SIFT uses L2
                .hnswParams(new HnswParams(16, 128, 64))
                .build();

        long t0 = System.nanoTime();
        index.train(trainSample);
        System.out.printf("  K-Means training: %.1f sec%n", (System.nanoTime() - t0) / 1e9);

        t0 = System.nanoTime();
        for (int i = 0; i < base.length; i++) {
            index.add("sift-" + i, i, base[i]);
            if (i > 0 && i % 100_000 == 0) {
                System.out.printf("  Indexed %d / %d (%.0f vec/sec)%n",
                        i, base.length, i / ((System.nanoTime() - t0) / 1e9));
            }
        }
        double ingestSec = (System.nanoTime() - t0) / 1e9;
        System.out.printf("  Ingest: %.1f sec → %.0f vec/sec%n",
                ingestSec, base.length / ingestSec);

        // ── Also build exact HNSW for ground-truth comparison ─────────────────
        // Note: for 1M vectors, exact HNSW requires ~4GB RAM — skip if unavailable
        // and use the provided ground-truth file instead.

        // ── Recall@10 vs QPS sweep over nProbe values ────────────────────────
        System.out.println("\n─────────────────────────────────────────────────────────");
        System.out.printf("%-10s %-15s %-15s %-15s%n", "nProbe", "Recall@10", "QPS", "Latency(ms)");
        System.out.println("─────────────────────────────────────────────────────────");

        int[] nProbeValues = {1, 2, 4, 8, 16, 32};
        for (int nProbe : nProbeValues) {
            // Rebuild with this nProbe (config is immutable after build, so reconstruct)
            SpectorIndex probeIndex = SpectorIndex.builder()
                    .dimensions(DIMS)
                    .nCentroids(N_CENTROIDS)
                    .nProbe(nProbe)
                    .shardThreshold(20_000)
                    .oversamplingFactor(3)
                    .similarityFunction(SimilarityFunction.EUCLIDEAN)
                    .hnswParams(new HnswParams(16, 128, 64))
                    .build();
            probeIndex.train(trainSample);
            for (int i = 0; i < base.length; i++) {
                probeIndex.add("sift-" + i, i, base[i]);
            }

            // Warmup
            for (float[] q : queries) probeIndex.search(q, K);

            // Measure recall + QPS
            int totalHits = 0;
            long searchStart = System.nanoTime();
            for (int q = 0; q < queries.length; q++) {
                ScoredResult[] results = probeIndex.search(queries[q], K);
                Set<Integer> gtSet = new HashSet<>();
                for (int idx : gt[q]) gtSet.add(idx);
                for (ScoredResult r : results) {
                    if (gtSet.contains(r.index())) totalHits++;
                }
            }
            double elapsed = (System.nanoTime() - searchStart) / 1e9;
            double recall   = (double) totalHits / ((double) queries.length * K);
            double qps      = queries.length / elapsed;
            double latencyMs = elapsed * 1000.0 / queries.length;

            System.out.printf("%-10d %-15.4f %-15.0f %-15.3f%n",
                    nProbe, recall, qps, latencyMs);

            probeIndex.close();
        }

        System.out.println("─────────────────────────────────────────────────────────");
        index.close();
    }

    // ── .fvecs / .ivecs file readers ─────────────────────────────────────────

    /**
     * Reads a .fvecs file (float vectors).
     * Format: [int32 dim][float32 × dim] repeated N times.
     */
    static float[][] readFvecs(Path path) throws IOException {
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size())
                    .order(ByteOrder.LITTLE_ENDIAN);
            int dim = buf.getInt();
            int recordSize = 4 + dim * 4;
            int n = (int) (ch.size() / recordSize);
            float[][] result = new float[n][dim];
            buf.rewind();
            for (int i = 0; i < n; i++) {
                buf.getInt();  // skip dim field (same for all)
                buf.asFloatBuffer().get(result[i]);
                buf.position(buf.position() + dim * 4);
            }
            return result;
        }
    }

    /**
     * Reads a .ivecs file (int vectors).
     * Format: [int32 dim][int32 × dim] repeated N times.
     */
    static int[][] readIvecs(Path path) throws IOException {
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size())
                    .order(ByteOrder.LITTLE_ENDIAN);
            int dim = buf.getInt();
            int recordSize = 4 + dim * 4;
            int n = (int) (ch.size() / recordSize);
            int[][] result = new int[n][dim];
            buf.rewind();
            for (int i = 0; i < n; i++) {
                buf.getInt();
                buf.asIntBuffer().get(result[i]);
                buf.position(buf.position() + dim * 4);
            }
            return result;
        }
    }
}
