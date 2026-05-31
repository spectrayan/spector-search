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

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmarks for the complete {@link SpectorIndex} search path.
 *
 * <p>Measures end-to-end search latency and throughput at various {@code nProbe} values,
 * dataset sizes, and dimensionalities. This is the primary benchmark for evaluating
 * the IVF + adaptive-shard + SVASQ pipeline against real workloads.</p>
 *
 * <h3>Index configuration</h3>
 * <ul>
 *   <li>Training: K-Means on a 10K vector sample</li>
 *   <li>Shard mode: depends on {@code shardSize} — flat or HNSW</li>
 *   <li>SVASQ: per-shard pre-calibration on all residuals at promotion</li>
 * </ul>
 *
 * <p>Run via:</p>
 * <pre>
 *   java -jar spector-bench/target/benchmarks.jar SpectorIndexBenchmark
 * </pre>
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
@Fork(value = 1, jvmArgsAppend = {
        "--add-modules", "jdk.incubator.vector",
        "--enable-native-access=ALL-UNNAMED",
        "-Xmx4g", "-Xms2g",
        "-XX:+UseZGC"
})
public class SpectorIndexBenchmark {

    @Param({"4", "8", "16", "32"})
    int nProbe;

    @Param({"128", "384"})
    int dims;

    /**
     * Total vectors indexed. Set high enough to ensure at least one shard promotes to HNSW.
     * With 32 centroids and 50K vectors, avg shard = 1562 (flat); at 200K avg shard = 6250 (flat).
     * Use 500K with 32 centroids to push shards to ~15K (approaching threshold).
     */
    @Param({"50000", "200000"})
    int totalVectors;

    private SpectorIndex index;
    private float[] queryVector;

    @Setup(Level.Trial)
    public void setup() {
        Random rng = new Random(42L);
        int nCentroids = 32;

        index = SpectorIndex.builder()
                .dimensions(dims)
                .nCentroids(nCentroids)
                .nProbe(nProbe)
                .shardThreshold(20_000)
                .oversamplingFactor(3)
                .similarityFunction(SimilarityFunction.COSINE)
                .hnswParams(new HnswParams(16, 128, 64))
                .build();

        // Train on a sample
        int trainSize = Math.min(10_000, totalVectors);
        float[][] trainVectors = new float[trainSize][dims];
        for (int i = 0; i < trainSize; i++) trainVectors[i] = gaussianUnit(rng, dims);
        index.train(trainVectors);

        // Index all vectors
        for (int i = 0; i < totalVectors; i++) {
            index.add("doc-" + i, i, gaussianUnit(rng, dims));
        }

        // Fixed query vector
        queryVector = gaussianUnit(new Random(999L), dims);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        index.close();
    }

    // ── Search benchmarks ─────────────────────────────────────────────────────

    /** Search for top-10 — typical recall@10 workload. */
    @Benchmark
    public void search_top10(Blackhole bh) {
        bh.consume(index.search(queryVector, 10));
    }

    /** Search for top-50 — used for re-ranking pipelines. */
    @Benchmark
    public void search_top50(Blackhole bh) {
        bh.consume(index.search(queryVector, 50));
    }

    /** Search for top-100 — retrieval-augmented generation (RAG) use case. */
    @Benchmark
    public void search_top100(Blackhole bh) {
        bh.consume(index.search(queryVector, 100));
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
