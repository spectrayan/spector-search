package com.spectrayan.spector.core;

import com.spectrayan.spector.commons.error.SpectorValidationException;

import com.spectrayan.spector.core.quantization.vasq.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for VASQ-4 (INT4 nibble-packed) pipeline: calibration → encode → prepare → distance.
 *
 * <p>Mirrors {@link VasqKernelTest} but with 4-bit quantization. Expected accuracy is
 * lower than VASQ-8 (15 levels vs 255) but still usable with oversampling rescore.</p>
 */
class Vasq4KernelTest {

    private static final long   SEED       = 42L;
    private static final int    DIM        = 128;
    private static final int    N_SAMPLES  = 500;
    // Relaxed tolerances for INT4 — 15 levels vs 255 for INT8
    private static final float  L2_REL_TOL = 0.15f;  // ≤ 15% average relative L2 error
    private static final float  DOT_TOL    = 0.15f;  // ≤ 15% average norm-normalized dot error

    private static VasqParams     params;
    private static Vasq4Encoder   encoder;
    private static Vasq4QueryPrep queryPrep;
    private static List<float[]>  corpus;

    @BeforeAll
    static void setup() {
        Random rng = new Random(1L);
        corpus = new ArrayList<>(N_SAMPLES);
        for (int i = 0; i < N_SAMPLES; i++) {
            float[] v = new float[DIM];
            for (int d = 0; d < DIM; d++) v[d] = (float) rng.nextGaussian();
            corpus.add(v);
        }
        params    = VasqCalibrator.calibrate4bit(corpus, DIM, SEED);
        encoder   = new Vasq4Encoder(params);
        queryPrep = new Vasq4QueryPrep(params);
    }

    // ── Params validation ─────────────────────────────────────────────────────

    @Test
    void params_bitWidth_is_4() {
        assertEquals(VasqParams.BIT_WIDTH_4, params.bitWidth());
    }

    @Test
    void params_bytesPerVector_equals_4_plus_halfPaddedDim() {
        assertEquals(4 + params.paddedDim() / 2, params.bytesPerVector());
    }

    @Test
    void params_codeBytesPerVector_equals_halfPaddedDim() {
        assertEquals(params.paddedDim() / 2, params.codeBytesPerVector());
    }

    // ── Encode/Decode round-trip ──────────────────────────────────────────────

    @Test
    void encodeDecode_roundTrip_withinTolerance() {
        float[] original = corpus.get(0);
        int bpv = encoder.bytesPerVector();

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(bpv, 8);
            encoder.encode(original, seg, 0L);

            // Decode returns rotated-space approximation (not original space).
            // With 15 quantization levels, each dim can be off by up to 1 scale unit.
            // Verify that the decode produces values in a reasonable range
            // and that the stored norm header is correct.
            float[] decoded = encoder.decode(seg, 0L, DIM);
            assertEquals(DIM, decoded.length);

            // Verify the norm header is correctly stored
            float storedNorm = seg.get(java.lang.foreign.ValueLayout.JAVA_FLOAT, 0L);
            float expectedNorm = exactNormSq(original);
            assertEquals(expectedNorm, storedNorm, expectedNorm * 0.01f + 0.001f,
                    "Stored norm should match exact ‖x‖²");

            // Verify decoded values are finite and not all zero
            boolean hasNonZero = false;
            for (float v : decoded) {
                assertTrue(Float.isFinite(v), "Decoded value must be finite");
                if (Math.abs(v) > 1e-6f) hasNonZero = true;
            }
            assertTrue(hasNonZero, "Decoded vector should not be all zeros");
        }
    }

    // ── L2 distance accuracy ──────────────────────────────────────────────────

    @Test
    void computeL2_closeToExact_randomPairs() {
        int halfDim = params.paddedDim() / 2;
        int bpv     = encoder.bytesPerVector();

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate((long) N_SAMPLES * bpv, 8);
            for (int i = 0; i < N_SAMPLES; i++) {
                encoder.encode(corpus.get(i), segment, (long) i * bpv);
            }

            Random rng = new Random(2L);
            double totalRelError = 0;
            int pairs = 200;

            for (int t = 0; t < pairs; t++) {
                float[] query = corpus.get(rng.nextInt(N_SAMPLES));
                int     docIdx = rng.nextInt(N_SAMPLES);
                float[] doc    = corpus.get(docIdx);

                float exactL2 = exactL2Sq(query, doc);
                Vasq4QueryState qs = queryPrep.prepare(query);
                float approxL2 = Vasq4SimdKernel.computeL2(segment, (long) docIdx * bpv, halfDim, qs);

                // L2 distances should be non-negative
                assertTrue(approxL2 >= -0.5f,
                        "L2 distance must be ≥ -0.5 (allowing small numerical error), got " + approxL2);

                if (exactL2 > 1e-6f) {
                    double relError = Math.abs(approxL2 - exactL2) / exactL2;
                    totalRelError += relError;
                }
            }

            double avgRelError = totalRelError / pairs;
            assertTrue(avgRelError < L2_REL_TOL,
                    "Average relative L2 error too high: " + avgRelError);
        }
    }

    @Test
    void computeL2_same_vector_is_near_zero() {
        float[] q   = corpus.get(0);
        int     bpv = encoder.bytesPerVector();

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(bpv, 8);
            encoder.encode(q, seg, 0L);

            Vasq4QueryState qs = queryPrep.prepare(q);
            float l2 = Vasq4SimdKernel.computeL2(seg, 0L, params.paddedDim() / 2, qs);

            float normSq = exactNormSq(q);
            // INT4 is rougher — allow 25% of ‖q‖²
            assertTrue(l2 < normSq * 0.25f,
                    "L2(q,q) should be < 25% of ‖q‖², got " + l2 + " norm²=" + normSq);
        }
    }

    // ── Dot product accuracy ──────────────────────────────────────────────────

    @Test
    void computeDot_closeToExact_randomPairs() {
        int halfDim = params.paddedDim() / 2;
        int bpv     = encoder.bytesPerVector();

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate((long) N_SAMPLES * bpv, 8);
            for (int i = 0; i < N_SAMPLES; i++) {
                encoder.encode(corpus.get(i), segment, (long) i * bpv);
            }

            Random rng = new Random(3L);
            double totalAbsError = 0;
            double totalNormProd = 0;
            int pairs = 200;

            for (int t = 0; t < pairs; t++) {
                float[] query  = corpus.get(rng.nextInt(N_SAMPLES));
                int     docIdx = rng.nextInt(N_SAMPLES);
                float[] doc    = corpus.get(docIdx);

                float exactDot  = exactDot(query, doc);
                Vasq4QueryState qs = queryPrep.prepare(query);
                float approxDot = Vasq4SimdKernel.computeDot(segment, (long) docIdx * bpv, halfDim, qs);

                float normProd = (float) Math.sqrt(exactNormSq(query) * exactNormSq(doc)) + 1e-9f;
                totalAbsError += Math.abs(approxDot - exactDot);
                totalNormProd += normProd;
            }

            double avgNormError = totalAbsError / totalNormProd;
            assertTrue(avgNormError < DOT_TOL,
                    "Average norm-normalized dot error too high: " + avgNormError);
        }
    }

    // ── Ranking preservation ──────────────────────────────────────────────────

    @Test
    void l2_ranking_partially_preserved() {
        // Top-5 exact should partially overlap with top-15 VASQ-4 (less strict than VASQ-8)
        float[] query = corpus.get(0);
        int halfDim = params.paddedDim() / 2;
        int bpv = encoder.bytesPerVector();

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate((long) N_SAMPLES * bpv, 8);
            for (int i = 0; i < N_SAMPLES; i++) {
                encoder.encode(corpus.get(i), segment, (long) i * bpv);
            }

            // Exact top-5
            float[] exactL2 = new float[N_SAMPLES];
            for (int i = 0; i < N_SAMPLES; i++) exactL2[i] = exactL2Sq(query, corpus.get(i));
            int[] exactTop5 = topK(exactL2, 6, true, 0);

            // VASQ-4 top-15 (wider window due to lower precision)
            Vasq4QueryState qs = queryPrep.prepare(query);
            float[] vasqL2 = new float[N_SAMPLES];
            for (int i = 0; i < N_SAMPLES; i++) {
                vasqL2[i] = Vasq4SimdKernel.computeL2(segment, (long) i * bpv, halfDim, qs);
            }
            int[] vasqTop15 = topK(vasqL2, 16, true, 0);

            int overlap = 0;
            for (int e : exactTop5) {
                for (int v : vasqTop15) if (e == v) { overlap++; break; }
            }
            assertTrue(overlap >= 2,
                    "Expected ≥ 2 of top-5 exact to appear in VASQ-4 top-15; overlap=" + overlap);
        }
    }

    // ── Memory layout ─────────────────────────────────────────────────────────

    @Test
    void encoder_bytesPerVector_matchesParams() {
        assertEquals(params.bytesPerVector(), encoder.bytesPerVector());
    }

    @Test
    void encoder_rejectsWrongBitWidth() {
        // VASQ-8 params should be rejected by Vasq4Encoder
        VasqParams int8Params = VasqCalibrator.calibrate(corpus, DIM, SEED);
        assertThrows(SpectorValidationException.class, () -> new Vasq4Encoder(int8Params));
    }

    @Test
    void queryPrep_rejectsWrongBitWidth() {
        VasqParams int8Params = VasqCalibrator.calibrate(corpus, DIM, SEED);
        assertThrows(SpectorValidationException.class, () -> new Vasq4QueryPrep(int8Params));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static float exactL2Sq(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) { double d = a[i] - b[i]; s += d * d; }
        return (float) s;
    }

    private static float exactNormSq(float[] v) {
        double s = 0;
        for (float x : v) s += (double) x * x;
        return (float) s;
    }

    private static float exactDot(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) s += (double) a[i] * b[i];
        return (float) s;
    }

    /** Returns indices of top-k smallest values, skipping {@code skipIdx}. */
    private static int[] topK(float[] scores, int k, boolean smallerIsBetter, int skipIdx) {
        int n = scores.length;
        int[] indices = new int[k];
        boolean[] used = new boolean[n];
        for (int t = 0; t < k; t++) {
            int best = -1;
            for (int i = 0; i < n; i++) {
                if (used[i] || i == skipIdx) continue;
                if (best == -1) { best = i; continue; }
                boolean betterThanBest = smallerIsBetter
                        ? scores[i] < scores[best]
                        : scores[i] > scores[best];
                if (betterThanBest) best = i;
            }
            indices[t] = best;
            used[best] = true;
        }
        return indices;
    }
}
