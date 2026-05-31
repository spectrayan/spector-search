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

import com.spectrayan.spector.core.quantization.strategy.SvasqStrategy;
import com.spectrayan.spector.core.quantization.svasq.SvasqCalibrator;
import com.spectrayan.spector.core.quantization.svasq.SvasqEncoder;
import com.spectrayan.spector.core.quantization.svasq.SvasqParams;
import com.spectrayan.spector.core.similarity.SimilarityFunction;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmarks for SVASQ encode throughput.
 *
 * <p>Measures the full encode pipeline: FWHT rotation → per-dimension INT8 quantization →
 * off-heap {@link MemorySegment} write. This is the hot path on every {@code add()} call
 * after promotion to HNSW mode.</p>
 *
 * <p>Run via:</p>
 * <pre>
 *   java -jar spector-bench/target/benchmarks.jar SvasqEncodeBenchmark
 * </pre>
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(value = 1, jvmArgsAppend = {
        "--add-modules", "jdk.incubator.vector",
        "--enable-native-access=ALL-UNNAMED",
        "-Xmx2g"
})
public class SvasqEncodeBenchmark {

    @Param({"128", "768"})
    int dims;

    /** Number of vectors in the batch encode benchmark. */
    @Param({"1000", "10000"})
    int batchSize;

    private SvasqEncoder encoder;
    private SvasqStrategy strategy;
    private float[] singleVector;
    private float[][] batchVectors;
    private MemorySegment segment;
    private Arena arena;
    private int bpv;

    @Setup(Level.Trial)
    public void setup() {
        Random rng = new Random(42L);

        // Build calibration sample
        List<float[]> sample = new ArrayList<>(2000);
        for (int i = 0; i < 2000; i++) {
            float[] v = gaussianUnit(rng, dims);
            sample.add(v);
        }

        SvasqParams params = SvasqCalibrator.calibrate(sample, dims);
        encoder = new SvasqEncoder(params);
        strategy = new SvasqStrategy(params, SimilarityFunction.COSINE);
        bpv = strategy.bytesPerVector();

        // Off-heap segment big enough for batchSize vectors
        arena = Arena.ofShared();
        segment = arena.allocate((long) batchSize * bpv, 8L);

        // Single vector for single-encode benchmark
        singleVector = gaussianUnit(rng, dims);

        // Batch vectors
        batchVectors = new float[batchSize][dims];
        for (int i = 0; i < batchSize; i++) {
            batchVectors[i] = gaussianUnit(rng, dims);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        arena.close();
    }

    /**
     * Encodes a single vector into the segment at offset 0.
     * Represents the per-vector cost in the HNSW add() hot path.
     */
    @Benchmark
    public void encode_single(Blackhole bh) {
        encoder.encode(singleVector, segment, 0L);
        bh.consume(segment);
    }

    /**
     * Encodes all {@code batchSize} vectors sequentially into the segment.
     * Represents bulk-ingestion throughput (e.g. at shard promotion time).
     */
    @Benchmark
    public void encode_batch(Blackhole bh) {
        for (int i = 0; i < batchSize; i++) {
            encoder.encode(batchVectors[i], segment, (long) i * bpv);
        }
        bh.consume(segment);
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
