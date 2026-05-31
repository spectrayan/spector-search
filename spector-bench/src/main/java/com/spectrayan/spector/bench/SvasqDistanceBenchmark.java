package com.spectrayan.spector.bench;

import com.spectrayan.spector.core.quantization.strategy.DistanceContext;
import com.spectrayan.spector.core.quantization.strategy.VasqStrategy;
import com.spectrayan.spector.core.quantization.vasq.VasqCalibrator;
import com.spectrayan.spector.core.quantization.vasq.VasqEncoder;
import com.spectrayan.spector.core.quantization.vasq.VasqParams;
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
 * JMH benchmarks for the VASQ distance kernel — the single hottest path in the system.
 *
 * <p>Compares:</p>
 * <ul>
 *   <li><b>VASQ distance</b> — prepareQueryContext() once, then distance() per candidate
 *       via the Panama SIMD kernel ({@link com.spectrayan.spector.core.quantization.vasq.VasqSimdKernel})</li>
 *   <li><b>Exact float32 baseline</b> — {@link SimilarityFunction#compute} for reference</li>
 *   <li><b>Scan-1000</b> — simulates scanning 1000 candidates (a realistic shard size)</li>
 * </ul>
 *
 * <p>The key metric is <b>distance calls per millisecond</b>. For the VASQ path to beat
 * float32, the SIMD kernel must overcome the FWHT rotation overhead on the query side.
 * The {@code prepareQueryContext} cost is amortized over all candidates in a shard.</p>
 *
 * <p>Run via:</p>
 * <pre>
 *   java -jar spector-bench/target/benchmarks.jar VasqDistanceBenchmark
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
public class VasqDistanceBenchmark {

    @Param({"128", "768"})
    int dims;

    /** Number of candidates in the scan benchmark (realistic shard size). */
    @Param({"1000", "10000"})
    int candidateCount;

    private VasqStrategy vasqStrategy;
    private float[] queryVector;
    private float[][] exactVectors;
    private MemorySegment encodedSegment;
    private Arena arena;
    private int bpv;
    private SimilarityFunction fn = SimilarityFunction.COSINE;

    @Setup(Level.Trial)
    public void setup() {
        Random rng = new Random(42L);

        // Build a calibrated VasqStrategy
        List<float[]> sample = new ArrayList<>(2000);
        for (int i = 0; i < 2000; i++) sample.add(gaussianUnit(rng, dims));
        VasqParams params = VasqCalibrator.calibrate(sample, dims);
        VasqEncoder encoder = new VasqEncoder(params);
        vasqStrategy = new VasqStrategy(params, fn);
        bpv = vasqStrategy.bytesPerVector();

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
     * Single VASQ distance call: prepareQueryContext (FWHT) + one distance() invocation.
     * Represents the fixed per-query overhead.
     */
    @Benchmark
    public float vasqDistance_single(Blackhole bh) {
        DistanceContext ctx = vasqStrategy.prepareQueryContext(queryVector);
        return vasqStrategy.distance(encodedSegment, 0L, ctx);
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
     * VASQ scan over {@code candidateCount} candidates.
     * prepareQueryContext called ONCE, then distance() called per candidate.
     * This is the correct way to use VASQ — amortize the FWHT rotation cost.
     */
    @Benchmark
    public void vasqScan_amortized(Blackhole bh) {
        DistanceContext ctx = vasqStrategy.prepareQueryContext(queryVector);
        float best = Float.MAX_VALUE;
        for (int i = 0; i < candidateCount; i++) {
            float d = vasqStrategy.distance(encodedSegment, (long) i * bpv, ctx);
            if (d < best) best = d;
        }
        bh.consume(best);
    }

    /**
     * Exact float32 scan over {@code candidateCount} candidates.
     * The baseline: what VASQ must beat on total throughput.
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
     * VASQ scan but with prepareQueryContext called per-candidate (intentionally wrong).
     * Demonstrates the cost of NOT amortizing the FWHT rotation.
     * Expected to be ~D×log(D) slower than {@link #vasqScan_amortized}.
     */
    @Benchmark
    public void vasqScan_noAmortization(Blackhole bh) {
        float best = Float.MAX_VALUE;
        for (int i = 0; i < candidateCount; i++) {
            DistanceContext ctx = vasqStrategy.prepareQueryContext(queryVector);
            float d = vasqStrategy.distance(encodedSegment, (long) i * bpv, ctx);
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
