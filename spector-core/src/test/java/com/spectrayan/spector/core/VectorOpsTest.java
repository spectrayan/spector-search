package com.spectrayan.spector.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link VectorOps} SIMD utility operations.
 */
class VectorOpsTest {

    // ─────────────── Magnitude ───────────────

    @Test
    void magnitudeOfUnitVector() {
        float[] v = {1f, 0f, 0f};
        assertThat(VectorOps.magnitude(v)).isCloseTo(1.0f, within(1e-6f));
    }

    @Test
    void magnitudeOfKnownVector() {
        float[] v = {3f, 4f};
        assertThat(VectorOps.magnitude(v)).isCloseTo(5.0f, within(1e-6f));
    }

    @Test
    void magnitudeSquaredOfZeroVector() {
        float[] v = {0f, 0f, 0f};
        assertThat(VectorOps.magnitudeSquared(v, 0, v.length)).isEqualTo(0f);
    }

    // ─────────────── Normalize ───────────────

    @Test
    void normalizedVectorHasUnitMagnitude() {
        float[] v = {3f, 4f, 0f};
        float[] norm = VectorOps.normalize(v);
        assertThat(VectorOps.magnitude(norm)).isCloseTo(1.0f, within(1e-6f));
    }

    @Test
    void normalizePreservesDirection() {
        float[] v = {2f, 0f, 0f};
        float[] norm = VectorOps.normalize(v);
        assertThat(norm[0]).isCloseTo(1.0f, within(1e-6f));
        assertThat(norm[1]).isCloseTo(0.0f, within(1e-6f));
        assertThat(norm[2]).isCloseTo(0.0f, within(1e-6f));
    }

    @Test
    void normalizeZeroVectorReturnsZero() {
        float[] v = {0f, 0f, 0f};
        float[] norm = VectorOps.normalize(v);
        for (float f : norm) {
            assertThat(f).isEqualTo(0f);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 7, 8, 9, 16, 17, 33, 128, 384, 768, 1536})
    void normalizedVectorAlwaysUnitLength(int dim) {
        float[] v = randomVector(dim, 42);
        float[] norm = VectorOps.normalize(v);
        assertThat(VectorOps.magnitude(norm)).isCloseTo(1.0f, within(1e-4f));
    }

    // ─────────────── Scale ───────────────

    @Test
    void scaleByZero() {
        float[] v = {1f, 2f, 3f};
        float[] result = VectorOps.scale(v, 0f);
        for (float f : result) {
            assertThat(f).isEqualTo(0f);
        }
    }

    @Test
    void scaleByTwo() {
        float[] v = {1f, 2f, 3f};
        float[] result = VectorOps.scale(v, 2f);
        assertThat(result).containsExactly(2f, 4f, 6f);
    }

    // ─────────────── Add ───────────────

    @Test
    void addVectors() {
        float[] a = {1f, 2f, 3f};
        float[] b = {4f, 5f, 6f};
        float[] result = VectorOps.add(a, b);
        assertThat(result).containsExactly(5f, 7f, 9f);
    }

    @Test
    void addZeroVector() {
        float[] a = {1f, 2f, 3f};
        float[] zero = {0f, 0f, 0f};
        assertThat(VectorOps.add(a, zero)).containsExactly(1f, 2f, 3f);
    }

    // ─────────────── Subtract ───────────────

    @Test
    void subtractVectors() {
        float[] a = {5f, 7f, 9f};
        float[] b = {1f, 2f, 3f};
        float[] result = VectorOps.subtract(a, b);
        assertThat(result).containsExactly(4f, 5f, 6f);
    }

    @Test
    void subtractFromSelfIsZero() {
        float[] v = {1f, 2f, 3f};
        float[] result = VectorOps.subtract(v, v);
        for (float f : result) {
            assertThat(f).isEqualTo(0f);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 7, 8, 9, 15, 16, 17, 33, 64, 128, 384, 1536})
    void addSubtractRoundTrip(int dim) {
        float[] a = randomVector(dim, 42);
        float[] b = randomVector(dim, 99);
        float[] sum = VectorOps.add(a, b);
        float[] roundTrip = VectorOps.subtract(sum, b);

        for (int i = 0; i < dim; i++) {
            assertThat(roundTrip[i]).isCloseTo(a[i], within(1e-5f));
        }
    }

    // ── Helpers ──

    private static float[] randomVector(int dim, long seed) {
        java.util.Random rng = new java.util.Random(seed);
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) {
            v[i] = rng.nextFloat() * 2f - 1f;
        }
        return v;
    }
}
