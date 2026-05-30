package com.spectrayan.spector.core;

import com.spectrayan.spector.commons.error.SpectorValidationException;

import com.spectrayan.spector.core.similarity.DotProduct;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link DotProduct} SIMD kernel.
 */
class DotProductTest {

    @Test
    void identicalVectors() {
        float[] v = {1f, 2f, 3f, 4f};
        // dot(v, v) = 1 + 4 + 9 + 16 = 30
        assertThat(DotProduct.compute(v, v)).isEqualTo(30f);
    }

    @Test
    void orthogonalVectors() {
        float[] a = {1f, 0f, 0f};
        float[] b = {0f, 1f, 0f};
        assertThat(DotProduct.compute(a, b)).isEqualTo(0f);
    }

    @Test
    void oppositeVectors() {
        float[] a = {1f, 2f, 3f};
        float[] b = {-1f, -2f, -3f};
        assertThat(DotProduct.compute(a, b)).isEqualTo(-14f);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 7, 8, 9, 15, 16, 17, 31, 32, 33, 63, 64, 100, 128, 256, 384, 512, 768, 1024, 1536})
    void matchesScalarReference(int dim) {
        float[] a = randomVector(dim, 42);
        float[] b = randomVector(dim, 99);

        float expected = scalarDotProduct(a, b);
        float actual = DotProduct.compute(a, b);

        assertThat(actual).isCloseTo(expected, within(Math.abs(expected) * 1e-5f + 1e-6f));
    }

    @Test
    void sliceOffset() {
        float[] a = {999f, 1f, 2f, 3f, 999f};
        float[] b = {999f, 999f, 4f, 5f, 6f};
        // dot([1,2,3], [4,5,6]) = 4 + 10 + 18 = 32
        assertThat(DotProduct.compute(a, 1, b, 2, 3)).isEqualTo(32f);
    }

    @Test
    void zeroLengthReturnsZero() {
        float[] a = {1f, 2f};
        float[] b = {3f, 4f};
        assertThat(DotProduct.compute(a, 0, b, 0, 0)).isEqualTo(0f);
    }

    @Test
    void invalidInputThrows() {
        float[] a = {1f, 2f};
        float[] b = {3f};
        assertThatThrownBy(() -> DotProduct.compute(a, 0, b, 0, 2))
                .isInstanceOf(SpectorValidationException.class);
    }

    // ── Scalar reference implementation ──

    private static float scalarDotProduct(float[] a, float[] b) {
        float sum = 0f;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    private static float[] randomVector(int dim, long seed) {
        java.util.Random rng = new java.util.Random(seed);
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) {
            v[i] = rng.nextFloat() * 2f - 1f;
        }
        return v;
    }
}
