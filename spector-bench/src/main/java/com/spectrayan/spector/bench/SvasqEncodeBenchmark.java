package com.spectrayan.spector.bench;

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
 * JMH benchmarks for VASQ encode throughput.
 *
 * <p>Measures the full encode pipeline: FWHT rotation → per-dimension INT8 quantization →
 * off-heap {@link MemorySegment} write. This is the hot path on every {@code add()} call
 * after promotion to HNSW mode.</p>
 *
 * <p>Run via:</p>
 * <pre>
 *   java -jar spector-bench/target/benchmarks.jar VasqEncodeBenchmark
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
public class VasqEncodeBenchmark {

    @Param({"128", "768"})
    int dims;

    /** Number of vectors in the batch encode benchmark. */
    @Param({"1000", "10000"})
    int batchSize;

    private VasqEncoder encoder;
    private VasqStrategy strategy;
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

        VasqParams params = VasqCalibrator.calibrate(sample, dims);
        encoder = new VasqEncoder(params);
        strategy = new VasqStrategy(params, SimilarityFunction.COSINE);
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
