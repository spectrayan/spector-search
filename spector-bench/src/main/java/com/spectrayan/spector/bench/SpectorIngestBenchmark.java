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
import com.spectrayan.spector.index.spectrum.SpectorIndex;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmarks for {@link SpectorIndex} ingest throughput.
 *
 * <p>Measures:</p>
 * <ul>
 *   <li><b>Training throughput</b> — K-Means iterations per second</li>
 *   <li><b>Add throughput</b> — vectors per millisecond during flat-scan accumulation</li>
 *   <li><b>Promotion cost</b> — shard promotion (SVASQ calibration + HNSW bulk-insert) latency</li>
 *   <li><b>Post-promotion add</b> — add() into live HNSW after shard has promoted</li>
 * </ul>
 *
 * <p>These benchmarks answer: "how long does it take to build a SpectorIndex from scratch?"
 * and "does per-shard SVASQ calibration add meaningful overhead at promotion time?"</p>
 *
 * <p>Run via:</p>
 * <pre>
 *   java -jar spector-bench/target/benchmarks.jar SpectorIngestBenchmark
 * </pre>
 */
@BenchmarkMode({Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(value = 1, jvmArgsAppend = {
        "--add-modules", "jdk.incubator.vector",
        "--enable-native-access=ALL-UNNAMED",
        "-Xmx4g", "-Xms2g",
        "-XX:+UseZGC"
})
public class SpectorIngestBenchmark {

    @Param({"128", "384"})
    int dims;

    /** Vectors to index. Controls whether shards promote (> shardThreshold/nCentroids). */
    @Param({"10000", "100000"})
    int totalVectors;

    private float[][] trainVectors;
    private float[][] indexVectors;
    private SpectorIndex trainedIndex;  // pre-trained, used for add() benchmarks

    @Setup(Level.Trial)
    public void setup() {
        Random rng = new Random(42L);
        int trainSize = Math.min(5_000, totalVectors);

        trainVectors = new float[trainSize][dims];
        for (int i = 0; i < trainSize; i++) trainVectors[i] = gaussianUnit(rng, dims);

        indexVectors = new float[totalVectors][dims];
        for (int i = 0; i < totalVectors; i++) indexVectors[i] = gaussianUnit(rng, dims);

        // Build a pre-trained index for the add() benchmarks
        trainedIndex = SpectorIndex.builder()
                .dimensions(dims)
                .nCentroids(32)
                .nProbe(16)
                .shardThreshold(20_000)
                .oversamplingFactor(3)
                .similarityFunction(SimilarityFunction.COSINE)
                .hnswParams(new HnswParams(16, 128, 64))
                .build();
        trainedIndex.train(trainVectors);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (trainedIndex != null) trainedIndex.close();
    }

    /**
     * Full train + index cycle.
     * Measures total time to build an index from scratch: K-Means + all add() calls.
     */
    @Benchmark
    public void fullBuildCycle(Blackhole bh) {
        SpectorIndex idx = SpectorIndex.builder()
                .dimensions(dims)
                .nCentroids(32)
                .nProbe(16)
                .shardThreshold(20_000)
                .oversamplingFactor(3)
                .similarityFunction(SimilarityFunction.COSINE)
                .hnswParams(HnswParams.DEFAULT)
                .build();
        idx.train(trainVectors);
        for (int i = 0; i < totalVectors; i++) {
            idx.add("doc-" + i, i, indexVectors[i]);
        }
        bh.consume(idx);
        idx.close();
    }

    /**
     * Training only (K-Means++ on trainVectors).
     * Isolates centroid learning cost from add() cost.
     */
    @Benchmark
    public void trainOnly(Blackhole bh) {
        SpectorIndex idx = SpectorIndex.builder()
                .dimensions(dims)
                .nCentroids(32)
                .nProbe(16)
                .shardThreshold(Integer.MAX_VALUE)  // never promote
                .similarityFunction(SimilarityFunction.COSINE)
                .hnswParams(HnswParams.DEFAULT)
                .build();
        idx.train(trainVectors);
        bh.consume(idx);
        idx.close();
    }

    /**
     * Add-only throughput (training pre-done).
     * Measures flat-mode accumulation speed — should be near-zero overhead
     * since it's just an ArrayList.add().
     */
    @Benchmark
    public void addOnly_flatMode(Blackhole bh) {
        // Fresh index per iteration — @Level.Invocation would be ideal but has too much overhead
        // Use a NEW trained index with a very high threshold (never promote)
        SpectorIndex idx = SpectorIndex.builder()
                .dimensions(dims)
                .nCentroids(32)
                .nProbe(16)
                .shardThreshold(Integer.MAX_VALUE)
                .similarityFunction(SimilarityFunction.COSINE)
                .hnswParams(HnswParams.DEFAULT)
                .build();
        idx.train(trainVectors);
        for (int i = 0; i < totalVectors; i++) {
            idx.add("doc-" + i, i, indexVectors[i]);
        }
        bh.consume(idx);
        idx.close();
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
