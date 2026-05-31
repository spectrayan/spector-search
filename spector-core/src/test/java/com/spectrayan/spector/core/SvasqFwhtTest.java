package com.spectrayan.spector.core;

import com.spectrayan.spector.commons.error.SpectorValidationException;

import com.spectrayan.spector.core.quantization.vasq.VasqFwht;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link VasqFwht} — FWHT rotation correctness and orthogonality.
 */
class VasqFwhtTest {

    private static final long SEED = 42L;
    private static final float NORM_TOLERANCE    = 1e-4f;
    private static final float INNER_P_TOLERANCE = 1e-4f;

    // ── nextPowerOfTwo ────────────────────────────────────────────────────────

    @Test
    void nextPowerOfTwo_exactPower() {
        assertEquals(1,    VasqFwht.nextPowerOfTwo(1));
        assertEquals(2,    VasqFwht.nextPowerOfTwo(2));
        assertEquals(4,    VasqFwht.nextPowerOfTwo(4));
        assertEquals(256,  VasqFwht.nextPowerOfTwo(256));
        assertEquals(1024, VasqFwht.nextPowerOfTwo(1024));
    }

    @Test
    void nextPowerOfTwo_nonExactPower() {
        assertEquals(4,    VasqFwht.nextPowerOfTwo(3));
        assertEquals(128,  VasqFwht.nextPowerOfTwo(100));
        assertEquals(512,  VasqFwht.nextPowerOfTwo(385));
        assertEquals(1024, VasqFwht.nextPowerOfTwo(769));
        assertEquals(2048, VasqFwht.nextPowerOfTwo(1537)); // 1536-dim embeddings
    }

    @Test
    void paddedDim_768_is_1024() {
        VasqFwht fwht = new VasqFwht(768, SEED);
        assertEquals(1024, fwht.paddedDim());
        assertEquals(768,  fwht.originalDim());
    }

    @Test
    void paddedDim_384_is_512() {
        VasqFwht fwht = new VasqFwht(384, SEED);
        assertEquals(512,  fwht.paddedDim());
    }

    @Test
    void paddedDim_128_is_128() {
        // 128 is already a power-of-two — no padding
        VasqFwht fwht = new VasqFwht(128, SEED);
        assertEquals(128, fwht.paddedDim());
    }

    // ── Orthogonality: ‖rotate(v)‖ = ‖v‖ ─────────────────────────────────────

    @Test
    void normPreserved_randomVector_128dims() {
        VasqFwht fwht = new VasqFwht(128, SEED);
        Random rng = new Random(1L);

        for (int trial = 0; trial < 50; trial++) {
            float[] v = randomVector(128, rng);
            float[] r = fwht.rotate(v);

            float normOrig   = norm(v);
            float normRotated = norm(r);
            assertEquals(normOrig, normRotated, NORM_TOLERANCE * normOrig,
                    "Norm not preserved on trial " + trial);
        }
    }

    @Test
    void normPreserved_randomVector_768dims() {
        VasqFwht fwht = new VasqFwht(768, SEED);
        Random rng = new Random(2L);

        for (int trial = 0; trial < 20; trial++) {
            float[] v = randomVector(768, rng);
            float[] r = fwht.rotate(v);

            float normOrig    = norm(v);
            float normRotated = norm(r);
            assertEquals(normOrig, normRotated, NORM_TOLERANCE * normOrig + 1e-6f,
                    "Norm not preserved on trial " + trial);
        }
    }

    @Test
    void normPreserved_zeroVector() {
        VasqFwht fwht = new VasqFwht(128, SEED);
        float[] zero   = new float[128];
        float[] rotated = fwht.rotate(zero);
        assertEquals(0f, norm(rotated), 1e-10f, "Zero vector must stay zero after rotation");
    }

    // ── Inner-product preservation: ⟨rotate(a), rotate(b)⟩ ≈ ⟨a, b⟩ ─────────

    @Test
    void innerProductPreserved_randomPairs_128dims() {
        VasqFwht fwht = new VasqFwht(128, SEED);
        Random rng = new Random(3L);

        for (int trial = 0; trial < 30; trial++) {
            float[] a = randomVector(128, rng);
            float[] b = randomVector(128, rng);
            float[] ra = fwht.rotate(a);
            float[] rb = fwht.rotate(b);

            float exactIP    = dot(a, b);
            float rotatedIP  = dot(ra, rb);
            float normProd   = norm(a) * norm(b) + 1e-9f;
            assertEquals(exactIP, rotatedIP, INNER_P_TOLERANCE * normProd + 1e-6f,
                    "Inner product not preserved on trial " + trial);
        }
    }

    // ── Determinism: same seed → same rotation ────────────────────────────────

    @Test
    void deterministic_sameSeed() {
        VasqFwht fwht1 = new VasqFwht(64, SEED);
        VasqFwht fwht2 = new VasqFwht(64, SEED);
        float[] v = randomVector(64, new Random(99L));

        float[] r1 = fwht1.rotate(v);
        float[] r2 = fwht2.rotate(v);

        assertArrayEquals(r1, r2, "Same seed must produce identical rotations");
    }

    @Test
    void different_seeds_produce_different_rotations() {
        VasqFwht fwht1 = new VasqFwht(64, 10L);
        VasqFwht fwht2 = new VasqFwht(64, 20L);
        float[] v = randomVector(64, new Random(99L));

        float[] r1 = fwht1.rotate(v);
        float[] r2 = fwht2.rotate(v);

        // Very unlikely to be identical with different seeds
        boolean allEqual = true;
        for (int i = 0; i < r1.length; i++) {
            if (Math.abs(r1[i] - r2[i]) > 1e-6f) { allEqual = false; break; }
        }
        assertFalse(allEqual, "Different seeds should produce different rotations");
    }

    // ── Output shape ──────────────────────────────────────────────────────────

    @Test
    void rotateAllocating_returns_paddedDim_array() {
        VasqFwht fwht = new VasqFwht(100, SEED);
        assertEquals(128, fwht.paddedDim());
        float[] v = randomVector(100, new Random(5L));
        float[] r = fwht.rotate(v);
        assertEquals(128, r.length);
    }

    @Test
    void rotateInPlace_writes_to_dst_buffer() {
        VasqFwht fwht = new VasqFwht(4, SEED);
        float[] src = {1f, 2f, 3f, 4f};
        float[] dst = new float[4];
        fwht.rotate(src, dst);
        // Verify dst was modified (not all zeros)
        float dstNorm = norm(dst);
        assertTrue(dstNorm > 0f, "dst must be non-zero after rotation");
        // Verify norm is preserved
        assertEquals(norm(src), dstNorm, 0.01f * norm(src) + 1e-6f);
    }

    @Test
    void wrongDimThrows() {
        VasqFwht fwht = new VasqFwht(128, SEED);
        assertThrows(SpectorValidationException.class, () -> fwht.rotate(new float[64]));
    }

    @Test
    void invalidDimThrows() {
        assertThrows(SpectorValidationException.class, () -> new VasqFwht(0, SEED));
    }

    // ── FWHT butterfly correctness (known output) ─────────────────────────────

    @Test
    void applyFwht_knownInput() {
        // For input [1, 0, 0, 0], FWHT gives [1, 1, 1, 1]
        float[] data = {1f, 0f, 0f, 0f};
        VasqFwht.applyFwht(data);
        assertArrayEquals(new float[]{1f, 1f, 1f, 1f}, data, 1e-6f);
    }

    @Test
    void applyFwht_knownInput2() {
        // For input [1, 1, 1, 1], FWHT gives [4, 0, 0, 0]
        float[] data = {1f, 1f, 1f, 1f};
        VasqFwht.applyFwht(data);
        assertArrayEquals(new float[]{4f, 0f, 0f, 0f}, data, 1e-6f);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static float[] randomVector(int dim, Random rng) {
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) v[i] = (float) rng.nextGaussian();
        return v;
    }

    private static float norm(float[] v) {
        double s = 0;
        for (float x : v) s += (double) x * x;
        return (float) Math.sqrt(s);
    }

    private static float dot(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) s += (double) a[i] * b[i];
        return (float) s;
    }
}
