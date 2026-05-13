package com.spectrayan.spector.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link CosineSimilarity} SIMD kernel.
 */
class CosineSimilarityTest {

    @Test
    void identicalVectors() {
        float[] v = {1f, 2f, 3f, 4f};
        assertThat(CosineSimilarity.compute(v, v)).isCloseTo(1.0f, within(1e-6f));
    }

    @Test
    void oppositeVectors() {
        float[] a = {1f, 2f, 3f};
        float[] b = {-1f, -2f, -3f};
        assertThat(CosineSimilarity.compute(a, b)).isCloseTo(-1.0f, within(1e-6f));
    }

    @Test
    void orthogonalVectors() {
        float[] a = {1f, 0f, 0f};
        float[] b = {0f, 1f, 0f};
        assertThat(CosineSimilarity.compute(a, b)).isCloseTo(0.0f, within(1e-6f));
    }

    @Test
    void zeroVectorReturnsZero() {
        float[] a = {0f, 0f, 0f};
        float[] b = {1f, 2f, 3f};
        assertThat(CosineSimilarity.compute(a, b)).isEqualTo(0.0f);
    }

    @Test
    void bothZeroVectorsReturnZero() {
        float[] a = {0f, 0f, 0f};
        assertThat(CosineSimilarity.compute(a, a)).isEqualTo(0.0f);
    }

    @Test
    void scalingDoesNotAffectResult() {
        float[] a = {1f, 2f, 3f};
        float[] b = {10f, 20f, 30f};
        assertThat(CosineSimilarity.compute(a, b)).isCloseTo(1.0f, within(1e-6f));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 7, 8, 9, 15, 16, 17, 31, 32, 33, 64, 128, 256, 384, 768, 1536})
    void matchesScalarReference(int dim) {
        float[] a = randomVector(dim, 42);
        float[] b = randomVector(dim, 99);

        float expected = scalarCosineSimilarity(a, b);
        float actual = CosineSimilarity.compute(a, b);

        assertThat(actual).isCloseTo(expected, within(1e-5f));
    }

    @Test
    void sliceOffset() {
        float[] a = {999f, 1f, 0f, 0f};
        float[] b = {0f, 0f, 1f, 999f};
        // cosine([1,0,0], [0,0,1]) should be close to 0
        float result = CosineSimilarity.compute(a, 1, b, 0, 3);
        assertThat(result).isCloseTo(0.0f, within(1e-6f));
    }

    // ── Scalar reference implementation ──

    private static float scalarCosineSimilarity(float[] a, float[] b) {
        float dot = 0f, normA = 0f, normB = 0f;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        float denom = (float) Math.sqrt(normA * normB);
        return denom == 0f ? 0f : dot / denom;
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
