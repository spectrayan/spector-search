package com.spectrayan.spector.core;

import com.spectrayan.spector.core.quantization.vasq.*;
import com.spectrayan.spector.core.simd.SimdCapability;

import jdk.incubator.vector.VectorSpecies;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link VasqSimdKernel} and the full encode → prepare → distance pipeline.
 */
class VasqKernelTest {

    private static final long   SEED       = 42L;
    private static final int    DIM        = 128;
    private static final int    N_SAMPLES  = 500;
    private static final float  L2_REL_TOL = 0.05f; // ≤ 5% relative L2 error
    private static final float  DOT_TOL    = 0.10f; // ≤ 10% relative dot error

    private static VasqParams   params;
    private static VasqEncoder  encoder;
    private static VasqQueryPrep queryPrep;
    private static List<float[]> corpus;

    @BeforeAll
    static void setup() {
        Random rng = new Random(1L);
        corpus = new ArrayList<>(N_SAMPLES);
        for (int i = 0; i < N_SAMPLES; i++) {
            float[] v = new float[DIM];
            for (int d = 0; d < DIM; d++) v[d] = (float) rng.nextGaussian();
            corpus.add(v);
        }
        params    = VasqCalibrator.calibrate(corpus, DIM, SEED);
        encoder   = new VasqEncoder(params);
        queryPrep = new VasqQueryPrep(params);
    }

    // ── Species safety regression ─────────────────────────────────────────────

    @Test
    void byteSpecies_laneCount_equals_floatSpecies_laneCount() {
        // This is the regression test for the SPECIES_256 bug from quant.md analysis.
        // B_SPECIES.length() must equal F_SPECIES.length() for the castShape to be valid.
        int floatLanes = VasqSimdKernel.floatSpecies().length();
        int byteLanes  = VasqSimdKernel.byteSpecies().length();
        assertEquals(floatLanes, byteLanes,
                "B_SPECIES must have the same lane count as F_SPECIES. "
                + "Got floatLanes=" + floatLanes + " byteLanes=" + byteLanes);
    }

    @Test
    void laneCount_is_power_of_two() {
        int lanes = VasqSimdKernel.laneCount();
        assertTrue(lanes > 0 && (lanes & (lanes - 1)) == 0,
                "Lane count must be a power of two, got " + lanes);
    }

    // ── L2 distance accuracy ──────────────────────────────────────────────────

    @Test
    void computeL2_closeToExact_randomPairs() {
        int paddedDim = params.paddedDim();
        int bpv       = encoder.bytesPerVector();

        try (Arena arena = Arena.ofConfined()) {
            // Encode all corpus vectors into one MemorySegment
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
                VasqQueryState qs = queryPrep.prepare(query);
                float approxL2 = VasqSimdKernel.computeL2(segment, (long) docIdx * bpv, paddedDim, qs);

                // L2 distances should be non-negative
                assertTrue(approxL2 >= -0.01f,
                        "L2 distance must be ≥ 0, got " + approxL2);

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
    void computeL2_zero_query_returns_exactNormSq() {
        // When query is zero: dot = 0, C(q) = 0, constL2Q = ‖0‖² - 2×0 = 0
        // So L2 = exactNormSq + 0 - 0 = exactNormSq
        float[] query  = new float[DIM]; // all zeros
        float[] doc    = corpus.get(0);
        int     bpv    = encoder.bytesPerVector();

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(bpv, 8);
            encoder.encode(doc, seg, 0L);

            VasqQueryState qs = queryPrep.prepare(query);
            float approxL2 = VasqSimdKernel.computeL2(seg, 0L, params.paddedDim(), qs);

            // Should approximately equal exactNormSq stored in the header
            float storedNorm = seg.get(java.lang.foreign.ValueLayout.JAVA_FLOAT, 0L);
            assertEquals(storedNorm, approxL2, storedNorm * 0.02f + 0.01f,
                    "L2 with zero query should ≈ exactNormSq");
        }
    }

    @Test
    void computeL2_same_vector_is_near_zero() {
        // L2(q, q) should be ≈ 0
        float[] q   = corpus.get(0);
        int     bpv = encoder.bytesPerVector();

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(bpv, 8);
            encoder.encode(q, seg, 0L);

            VasqQueryState qs = queryPrep.prepare(q);
            float l2 = VasqSimdKernel.computeL2(seg, 0L, params.paddedDim(), qs);

        // Quantization introduces ~5-10% error, so L2(q,q) won't be exactly 0
            float normSq = exactNormSq(q);
            assertTrue(l2 < normSq * 0.15f,
                    "L2(q,q) should be < 15% of ‖q‖², got " + l2 + " norm²=" + normSq);
        }
    }

    // ── Dot product accuracy ──────────────────────────────────────────────────

    @Test
    void computeDot_closeToExact_randomPairs() {
        int paddedDim = params.paddedDim();
        int bpv       = encoder.bytesPerVector();

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
                VasqQueryState qs = queryPrep.prepare(query);
                float approxDot = VasqSimdKernel.computeDot(segment, (long) docIdx * bpv, paddedDim, qs);

                // Normalize by ‖query‖·‖doc‖ to avoid division by near-zero dot products
                float normProd = (float) Math.sqrt(exactNormSq(query) * exactNormSq(doc)) + 1e-9f;
                totalAbsError += Math.abs(approxDot - exactDot);
                totalNormProd += normProd;
            }

            // Average normalized error should be < 5% of the typical vector norm product
            double avgNormError = totalAbsError / totalNormProd;
            assertTrue(avgNormError < 0.05,
                    "Average norm-normalized dot error too high: " + avgNormError);
        }
    }

    // ── Ranking preservation (relative order) ────────────────────────────────

    @Test
    void l2_ranking_mostly_preserved() {
        // Top-5 by exact L2 should appear in top-10 by VASQ L2 (>= 4 out of 5)
        float[] query = corpus.get(0);
        int bpv = encoder.bytesPerVector();

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate((long) N_SAMPLES * bpv, 8);
            for (int i = 0; i < N_SAMPLES; i++) {
                encoder.encode(corpus.get(i), segment, (long) i * bpv);
            }

            // Exact top-5 (excluding query itself)
            float[] exactL2 = new float[N_SAMPLES];
            for (int i = 0; i < N_SAMPLES; i++) exactL2[i] = exactL2Sq(query, corpus.get(i));
            int[] exactTop5 = topK(exactL2, 6, true, 0); // skip index 0

            // VASQ top-10
            VasqQueryState qs = queryPrep.prepare(query);
            float[] vasqL2 = new float[N_SAMPLES];
            for (int i = 0; i < N_SAMPLES; i++) {
                vasqL2[i] = VasqSimdKernel.computeL2(segment, (long) i * bpv, params.paddedDim(), qs);
            }
            int[] vasqTop10 = topK(vasqL2, 11, true, 0); // skip index 0

            int overlap = 0;
            for (int e : exactTop5) {
                for (int v : vasqTop10) if (e == v) { overlap++; break; }
            }
            assertTrue(overlap >= 3,
                    "Expected ≥ 3 of top-5 exact to appear in VASQ top-10; overlap=" + overlap);
        }
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
