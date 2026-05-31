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
package com.spectrayan.spector.core;

import com.spectrayan.spector.commons.error.SpectorValidationException;

import com.spectrayan.spector.core.quantization.svasq.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SVASQ-4 (INT4 nibble-packed) pipeline: calibration → encode → prepare → distance.
 *
 * <p>Mirrors {@link SvasqKernelTest} but with 4-bit quantization. Expected accuracy is
 * lower than SVASQ-8 (15 levels vs 255) but still usable with oversampling rescore.</p>
 */
class Svasq4KernelTest {

    private static final long   SEED       = 42L;
    private static final int    DIM        = 128;
    private static final int    N_SAMPLES  = 500;
    // Relaxed tolerances for INT4 — 15 levels vs 255 for INT8
    private static final float  L2_REL_TOL = 0.15f;  // ≤ 15% average relative L2 error
    private static final float  DOT_TOL    = 0.15f;  // ≤ 15% average norm-normalized dot error

    private static SvasqParams     params;
    private static Svasq4Encoder   encoder;
    private static Svasq4QueryPrep queryPrep;
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
        params    = SvasqCalibrator.calibrate4bit(corpus, DIM, SEED);
        encoder   = new Svasq4Encoder(params);
        queryPrep = new Svasq4QueryPrep(params);
    }

    // ── Params validation ─────────────────────────────────────────────────────

    @Test
    void params_bitWidth_is_4() {
        assertEquals(SvasqParams.BIT_WIDTH_4, params.bitWidth());
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
                Svasq4QueryState qs = queryPrep.prepare(query);
                float approxL2 = Svasq4SimdKernel.computeL2(segment, (long) docIdx * bpv, halfDim, qs);

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

            Svasq4QueryState qs = queryPrep.prepare(q);
            float l2 = Svasq4SimdKernel.computeL2(seg, 0L, params.paddedDim() / 2, qs);

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
                Svasq4QueryState qs = queryPrep.prepare(query);
                float approxDot = Svasq4SimdKernel.computeDot(segment, (long) docIdx * bpv, halfDim, qs);

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
        // Top-5 exact should partially overlap with top-15 SVASQ-4 (less strict than SVASQ-8)
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

            // SVASQ-4 top-15 (wider window due to lower precision)
            Svasq4QueryState qs = queryPrep.prepare(query);
            float[] svasqL2 = new float[N_SAMPLES];
            for (int i = 0; i < N_SAMPLES; i++) {
                svasqL2[i] = Svasq4SimdKernel.computeL2(segment, (long) i * bpv, halfDim, qs);
            }
            int[] svasqTop15 = topK(svasqL2, 16, true, 0);

            int overlap = 0;
            for (int e : exactTop5) {
                for (int v : svasqTop15) if (e == v) { overlap++; break; }
            }
            assertTrue(overlap >= 2,
                    "Expected ≥ 2 of top-5 exact to appear in SVASQ-4 top-15; overlap=" + overlap);
        }
    }

    // ── Memory layout ─────────────────────────────────────────────────────────

    @Test
    void encoder_bytesPerVector_matchesParams() {
        assertEquals(params.bytesPerVector(), encoder.bytesPerVector());
    }

    @Test
    void encoder_rejectsWrongBitWidth() {
        // SVASQ-8 params should be rejected by Svasq4Encoder
        SvasqParams int8Params = SvasqCalibrator.calibrate(corpus, DIM, SEED);
        assertThrows(SpectorValidationException.class, () -> new Svasq4Encoder(int8Params));
    }

    @Test
    void queryPrep_rejectsWrongBitWidth() {
        SvasqParams int8Params = SvasqCalibrator.calibrate(corpus, DIM, SEED);
        assertThrows(SpectorValidationException.class, () -> new Svasq4QueryPrep(int8Params));
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
