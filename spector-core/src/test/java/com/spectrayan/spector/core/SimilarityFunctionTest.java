package com.spectrayan.spector.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SimilarityFunction} strategy enum.
 */
class SimilarityFunctionTest {

    @Test
    void cosine_identicalVectorsScoreHighest() {
        float[] v = {1f, 2f, 3f, 4f};
        float[] other = {5f, 6f, 7f, 8f};
        float selfScore = SimilarityFunction.COSINE.compute(v, v);
        float otherScore = SimilarityFunction.COSINE.compute(v, other);
        assertThat(selfScore).isGreaterThanOrEqualTo(otherScore);
    }

    @Test
    void euclidean_identicalVectorsHaveZeroDistance() {
        float[] v = {1f, 2f, 3f, 4f};
        float selfScore = SimilarityFunction.EUCLIDEAN.compute(v, v);
        assertThat(selfScore).isCloseTo(0f, within(1e-6f));
    }

    @Test
    void dotProduct_normalizedIdenticalVectorsScoreHighest() {
        float[] v = VectorOps.normalize(new float[]{1f, 2f, 3f, 4f});
        float[] other = VectorOps.normalize(new float[]{-1f, 0.5f, -0.3f, 0.1f});
        float selfScore = SimilarityFunction.DOT_PRODUCT.compute(v, v);
        float otherScore = SimilarityFunction.DOT_PRODUCT.compute(v, other);
        assertThat(selfScore).isGreaterThan(otherScore);
    }

    @Test
    void cosinePolarity() {
        assertThat(SimilarityFunction.COSINE.higherIsBetter()).isTrue();
    }

    @Test
    void dotProductPolarity() {
        assertThat(SimilarityFunction.DOT_PRODUCT.higherIsBetter()).isTrue();
    }

    @Test
    void euclideanPolarity() {
        assertThat(SimilarityFunction.EUCLIDEAN.higherIsBetter()).isFalse();
    }

    @Test
    void sliceVariantWorks() {
        float[] a = {0f, 1f, 2f, 3f, 0f};
        float[] b = {1f, 2f, 3f};

        float full = SimilarityFunction.DOT_PRODUCT.compute(b, b);
        float slice = SimilarityFunction.DOT_PRODUCT.compute(a, 1, b, 0, 3);

        assertThat(slice).isCloseTo(full, within(1e-6f));
    }
}
