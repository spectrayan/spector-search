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
import java.util.PriorityQueue;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmarks for the SpectorShard flat-scan path.
 *
 * <p>The flat scan is the critical mode for small shards (&lt; shardThreshold). It performs
 * exhaustive exact L2 over float32 residuals and is expected to outperform HNSW for
 * sizes below ~20K due to contiguous memory access patterns and SIMD-friendly layout.</p>
 *
 * <p>Benchmarks:</p>
 * <ul>
 *   <li><b>float32 flat scan</b> — exhaustive exact similarity over raw float residuals</li>
 *   <li><b>VASQ flat scan</b> — exhaustive scan using the VASQ distance kernel over encoded
 *       off-heap residuals. Simulates what the shard would do post-calibration in a fully
 *       quantized shard (not yet promoted to HNSW).</li>
 * </ul>
 *
 * <p>Run via:</p>
 * <pre>
 *   java -jar spector-bench/target/benchmarks.jar FlatScanBenchmark
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
public class FlatScanBenchmark {

    @Param({"128", "384"})
    int dims;

    /** Shard size — spans the flat-mode range and one post-threshold point. */
    @Param({"1000", "5000", "20000"})
    int shardSize;

    @Param({"10"})
    int topK;

    private float[] queryResidual;
    private float[][] floatResiduals;   // float32 exact residuals (flat mode)
    private MemorySegment encodedSegment;
    private Arena arena;
    private VasqStrategy vasqStrategy;
    private int bpv;
    private SimilarityFunction fn = SimilarityFunction.COSINE;

    @Setup(Level.Trial)
    public void setup() {
        Random rng = new Random(42L);

        // Build calibrated VASQ strategy
        List<float[]> sample = new ArrayList<>(Math.min(shardSize, 2000));
        for (int i = 0; i < sample.size(); i++) sample.add(gaussianUnit(rng, dims));
        // Ensure we have enough for calibration
        while (sample.size() < 200) sample.add(gaussianUnit(rng, dims));
        VasqParams params = VasqCalibrator.calibrate(sample, dims);
        VasqEncoder encoder = new VasqEncoder(params);
        vasqStrategy = new VasqStrategy(params, fn);
        bpv = vasqStrategy.bytesPerVector();

        // Query residual
        queryResidual = gaussianUnit(rng, dims);

        // Float32 residuals (heap)
        floatResiduals = new float[shardSize][dims];
        for (int i = 0; i < shardSize; i++) floatResiduals[i] = gaussianUnit(rng, dims);

        // VASQ-encoded residuals (off-heap)
        arena = Arena.ofShared();
        encodedSegment = arena.allocate((long) shardSize * bpv, 8L);
        for (int i = 0; i < shardSize; i++) {
            encoder.encode(floatResiduals[i], encodedSegment, (long) i * bpv);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        arena.close();
    }

    // ── Float32 exact flat scan (current SpectorShard flat mode) ─────────────

    /**
     * Exhaustive exact similarity scan over float32 residuals.
     * Uses a min-heap of size k to track the best candidates.
     * This is what {@link com.spectrayan.spector.index.spectrum.SpectorShard#flatScan} does.
     */
    @Benchmark
    public void flatScan_exact_float32(Blackhole bh) {
        PriorityQueue<float[]> heap = new PriorityQueue<>(topK,
                (a, b) -> Float.compare(a[0], b[0]));  // min-heap by score

        for (int i = 0; i < shardSize; i++) {
            float score = fn.compute(queryResidual, floatResiduals[i]);
            if (heap.size() < topK) {
                heap.offer(new float[]{score, i});
            } else if (score > heap.peek()[0]) {
                heap.poll();
                heap.offer(new float[]{score, i});
            }
        }
        bh.consume(heap);
    }

    // ── VASQ quantized flat scan (hypothetical fully-quantized shard mode) ───

    /**
     * Exhaustive VASQ distance scan over off-heap encoded residuals.
     * Demonstrates the throughput possible if the flat-scan path also used VASQ
     * instead of float32 (useful for very large pre-promotion shards).
     */
    @Benchmark
    public void flatScan_vasq_encoded(Blackhole bh) {
        DistanceContext ctx = vasqStrategy.prepareQueryContext(queryResidual);
        PriorityQueue<float[]> heap = new PriorityQueue<>(topK,
                (a, b) -> Float.compare(a[0], b[0]));

        for (int i = 0; i < shardSize; i++) {
            float score = vasqStrategy.distance(encodedSegment, (long) i * bpv, ctx);
            if (heap.size() < topK) {
                heap.offer(new float[]{score, i});
            } else if (score > heap.peek()[0]) {
                heap.poll();
                heap.offer(new float[]{score, i});
            }
        }
        bh.consume(heap);
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
