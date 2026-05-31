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
import com.spectrayan.spector.index.HnswIndex;
import com.spectrayan.spector.index.QuantizedHnswIndex;
import com.spectrayan.spector.index.ScoredResult;
import com.spectrayan.spector.index.spectrum.SpectorIndex;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Recall@10 vs QPS benchmark: compares SpectorIndex against plain HNSW and exact brute force.
 *
 * <p>This is the primary <b>quality benchmark</b> — it measures the recall/speed trade-off
 * curve that determines whether SpectorIndex is worth using over simpler alternatives.</p>
 *
 * <h3>Methodology</h3>
 * <ol>
 *   <li>Build an exact brute-force index (all vectors in RAM) to generate ground truth.</li>
 *   <li>Build the candidate index (SpectorIndex or plain HNSW).</li>
 *   <li>Run {@link #QUERY_COUNT} queries, compare top-K against ground truth.</li>
 *   <li>Report recall@K = |approx ∩ exact| / K.</li>
 * </ol>
 *
 * <p>The JMH throughput measurement gives QPS for the search phase only
 * (index build is in {@code @Setup}).</p>
 *
 * <p>Run via:</p>
 * <pre>
 *   java -jar spector-bench/target/benchmarks.jar RecallVsQpsBenchmark
 * </pre>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
@Fork(value = 1, jvmArgsAppend = {
        "--add-modules", "jdk.incubator.vector",
        "--enable-native-access=ALL-UNNAMED",
        "-Xmx6g", "-Xms4g",
        "-XX:+UseZGC"
})
public class RecallVsQpsBenchmark {

    private static final int K            = 10;
    private static final int QUERY_COUNT  = 100;

    @Param({"128"})
    int dims;

    @Param({"50000"})
    int totalVectors;

    // nProbe for SpectorIndex — drives the recall/QPS curve
    @Param({"2", "4", "8", "16", "32"})
    int nProbe;

    private SpectorIndex spectorIndex;
    private HnswIndex    exactHnswIndex;
    private float[][]    queryVectors;

    // Recall is computed in setup and reported via System.out (not JMH metrics)
    private double spectorRecallAtK;
    private double hnswRecallAtK;

    @Setup(Level.Trial)
    public void setup() {
        Random rng = new Random(42L);
        HnswParams hnswParams = new HnswParams(16, 128, 64);

        // ── Build SpectorIndex ────────────────────────────────────────────────
        spectorIndex = SpectorIndex.builder()
                .dimensions(dims)
                .nCentroids(32)
                .nProbe(nProbe)
                .shardThreshold(20_000)
                .oversamplingFactor(3)
                .similarityFunction(SimilarityFunction.COSINE)
                .hnswParams(hnswParams)
                .build();

        // ── Build exact HNSW (ground truth) ──────────────────────────────────
        exactHnswIndex = new HnswIndex(dims, totalVectors + 10,
                SimilarityFunction.COSINE, hnswParams);

        // Train SpectorIndex
        int trainSize = Math.min(10_000, totalVectors);
        float[][] trainVectors = new float[trainSize][dims];
        for (int i = 0; i < trainSize; i++) trainVectors[i] = gaussianUnit(rng, dims);
        spectorIndex.train(trainVectors);

        // Index all vectors
        float[][] vectors = new float[totalVectors][dims];
        for (int i = 0; i < totalVectors; i++) {
            vectors[i] = gaussianUnit(rng, dims);
            spectorIndex.add("doc-" + i, i, vectors[i]);
            exactHnswIndex.add("doc-" + i, i, vectors[i]);
        }

        // ── Build query set ───────────────────────────────────────────────────
        queryVectors = new float[QUERY_COUNT][dims];
        Random queryRng = new Random(999L);
        for (int q = 0; q < QUERY_COUNT; q++) {
            queryVectors[q] = gaussianUnit(queryRng, dims);
        }

        // ── Pre-compute recall for reporting ─────────────────────────────────
        spectorRecallAtK = measureRecall(spectorIndex, exactHnswIndex, queryVectors, K);
        System.out.printf(
                "%n[RecallVsQpsBenchmark] dims=%d, N=%d, nProbe=%d → SpectorIndex recall@%d = %.4f%n",
                dims, totalVectors, nProbe, K, spectorRecallAtK);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        spectorIndex.close();
        exactHnswIndex.close();
    }

    // ── Search throughput benchmarks ─────────────────────────────────────────

    /**
     * SpectorIndex search throughput — the primary metric.
     * Cycles through all QUERY_COUNT queries to avoid query-vector cache effects.
     */
    @Benchmark
    public ScoredResult[] spectorIndex_search(org.openjdk.jmh.infra.BenchmarkParams bp,
                                               Blackhole bh) {
        // Rotate through queries so the same query vector isn't always in cache
        int q = (int) (System.nanoTime() % QUERY_COUNT);
        ScoredResult[] results = spectorIndex.search(queryVectors[q], K);
        bh.consume(results);
        return results;
    }

    /**
     * Exact HNSW search throughput — the quality baseline.
     * SpectorIndex must get close to this recall while beating its QPS at large N.
     */
    @Benchmark
    public ScoredResult[] exactHnsw_search(Blackhole bh) {
        int q = (int) (System.nanoTime() % QUERY_COUNT);
        ScoredResult[] results = exactHnswIndex.search(queryVectors[q], K);
        bh.consume(results);
        return results;
    }

    // ── Recall helper ────────────────────────────────────────────────────────

    private static double measureRecall(SpectorIndex approx, HnswIndex exact,
                                         float[][] queries, int k) {
        int totalHits = 0;
        for (float[] q : queries) {
            ScoredResult[] approxResults = approx.search(q, k);
            ScoredResult[] exactResults  = exact.search(q, k);

            Set<String> exactIds = new HashSet<>();
            for (ScoredResult r : exactResults) exactIds.add(r.id());
            for (ScoredResult r : approxResults) {
                if (exactIds.contains(r.id())) totalHits++;
            }
        }
        return (double) totalHits / ((double) queries.length * k);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

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
