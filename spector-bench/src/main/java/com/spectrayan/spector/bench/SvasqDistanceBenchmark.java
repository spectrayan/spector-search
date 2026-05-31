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

import com.spectrayan.spector.core.quantization.strategy.DistanceContext;
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
 * JMH benchmarks for the SVASQ distance kernel — the single hottest path in the system.
 *
 * <p>Compares:</p>
 * <ul>
 *   <li><b>SVASQ distance</b> — prepareQueryContext() once, then distance() per candidate
 *       via the Panama SIMD kernel ({@link com.spectrayan.spector.core.quantization.svasq.SvasqSimdKernel})</li>
 *   <li><b>Exact float32 baseline</b> — {@link SimilarityFunction#compute} for reference</li>
 *   <li><b>Scan-1000</b> — simulates scanning 1000 candidates (a realistic shard size)</li>
 * </ul>
 *
 * <p>The key metric is <b>distance calls per millisecond</b>. For the SVASQ path to beat
 * float32, the SIMD kernel must overcome the FWHT rotation overhead on the query side.
 * The {@code prepareQueryContext} cost is amortized over all candidates in a shard.</p>
 *
 * <p>Run via:</p>
 * <pre>
 *   java -jar spector-bench/target/benchmarks.jar SvasqDistanceBenchmark
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
public class SvasqDistanceBenchmark {

    @Param({"128", "768"})
    int dims;

    /** Number of candidates in the scan benchmark (realistic shard size). */
    @Param({"1000", "10000"})
    int candidateCount;

    private SvasqStrategy svasqStrategy;
    private float[] queryVector;
    private float[][] exactVectors;
    private MemorySegment encodedSegment;
    private Arena arena;
    private int bpv;
    private SimilarityFunction fn = SimilarityFunction.COSINE;

    @Setup(Level.Trial)
    public void setup() {
        Random rng = new Random(42L);

        // Build a calibrated SvasqStrategy
        List<float[]> sample = new ArrayList<>(2000);
        for (int i = 0; i < 2000; i++) sample.add(gaussianUnit(rng, dims));
        SvasqParams params = SvasqCalibrator.calibrate(sample, dims);
        SvasqEncoder encoder = new SvasqEncoder(params);
        svasqStrategy = new SvasqStrategy(params, fn);
        bpv = svasqStrategy.bytesPerVector();

        // Query vector
        queryVector = gaussianUnit(rng, dims);

        // Exact float vectors for the baseline
        exactVectors = new float[candidateCount][dims];
        for (int i = 0; i < candidateCount; i++) exactVectors[i] = gaussianUnit(rng, dims);

        // Encode all candidates into off-heap segment
        arena = Arena.ofShared();
        encodedSegment = arena.allocate((long) candidateCount * bpv, 8L);
        for (int i = 0; i < candidateCount; i++) {
            encoder.encode(exactVectors[i], encodedSegment, (long) i * bpv);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        arena.close();
    }

    // ── Single distance call benchmarks ──────────────────────────────────────

    /**
     * Single SVASQ distance call: prepareQueryContext (FWHT) + one distance() invocation.
     * Represents the fixed per-query overhead.
     */
    @Benchmark
    public float svasqDistance_single(Blackhole bh) {
        DistanceContext ctx = svasqStrategy.prepareQueryContext(queryVector);
        return svasqStrategy.distance(encodedSegment, 0L, ctx);
    }

    /**
     * Single exact float32 distance — the baseline this replaces.
     */
    @Benchmark
    public float exactDistance_single(Blackhole bh) {
        return fn.compute(queryVector, exactVectors[0]);
    }

    // ── Scan-over-N benchmarks (the realistic case) ───────────────────────────

    /**
     * SVASQ scan over {@code candidateCount} candidates.
     * prepareQueryContext called ONCE, then distance() called per candidate.
     * This is the correct way to use SVASQ — amortize the FWHT rotation cost.
     */
    @Benchmark
    public void svasqScan_amortized(Blackhole bh) {
        DistanceContext ctx = svasqStrategy.prepareQueryContext(queryVector);
        float best = Float.MAX_VALUE;
        for (int i = 0; i < candidateCount; i++) {
            float d = svasqStrategy.distance(encodedSegment, (long) i * bpv, ctx);
            if (d < best) best = d;
        }
        bh.consume(best);
    }

    /**
     * Exact float32 scan over {@code candidateCount} candidates.
     * The baseline: what SVASQ must beat on total throughput.
     */
    @Benchmark
    public void exactScan_baseline(Blackhole bh) {
        float best = Float.MAX_VALUE;
        for (int i = 0; i < candidateCount; i++) {
            float d = fn.compute(queryVector, exactVectors[i]);
            if (d < best) best = d;
        }
        bh.consume(best);
    }

    /**
     * SVASQ scan but with prepareQueryContext called per-candidate (intentionally wrong).
     * Demonstrates the cost of NOT amortizing the FWHT rotation.
     * Expected to be ~D×log(D) slower than {@link #svasqScan_amortized}.
     */
    @Benchmark
    public void svasqScan_noAmortization(Blackhole bh) {
        float best = Float.MAX_VALUE;
        for (int i = 0; i < candidateCount; i++) {
            DistanceContext ctx = svasqStrategy.prepareQueryContext(queryVector);
            float d = svasqStrategy.distance(encodedSegment, (long) i * bpv, ctx);
            if (d < best) best = d;
        }
        bh.consume(best);
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
