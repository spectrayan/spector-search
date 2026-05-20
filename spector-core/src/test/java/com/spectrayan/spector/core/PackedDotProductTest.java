package com.spectrayan.spector.core;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PackedDotProduct}.
 */
class PackedDotProductTest {

    private static final float TOLERANCE = 1e-6f;

    @Test
    @DisplayName("SIMD availability should be detected")
    void shouldDetectSimdAvailability() {
        // Just verify the method doesn't throw; actual value depends on runtime
        boolean available = PackedDotProduct.isSimdAvailable();
        // On a standard JDK 21+ with --add-modules, this should be true
        assertThat(available).isNotNull();
    }

    // ── INT4 Tests ──

    @Test
    @DisplayName("INT4: simple known dot product with 4 dimensions")
    void int4SimpleDotProduct() {
        // 4 dimensions: levels [1, 2, 3, 0]
        // centroids4[0]=0.0, [1]=0.5, [2]=1.0, [3]=1.5
        float[] query = {1.0f, 2.0f, 3.0f, 4.0f};
        float[] centroids4 = {0.0f, 0.5f, 1.0f, 1.5f, 0.0f, 0.0f, 0.0f, 0.0f,
                              0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f};

        // Pack levels [1, 2, 3, 0] → byte[0] = (1<<4)|2 = 0x12, byte[1] = (3<<4)|0 = 0x30
        int[] levels = {1, 2, 3, 0};
        byte[] packedDoc = NibblePacker.pack(levels, levels.length);

        // Expected: 1.0*0.5 + 2.0*1.0 + 3.0*1.5 + 4.0*0.0 = 0.5 + 2.0 + 4.5 + 0.0 = 7.0
        float expected = 7.0f;

        assertThat(PackedDotProduct.computeInt4(query, packedDoc, centroids4, 4))
                .isCloseTo(expected, within(TOLERANCE));
        assertThat(PackedDotProduct.computeInt4Scalar(query, packedDoc, centroids4, 4))
                .isCloseTo(expected, within(TOLERANCE));
    }

    @Test
    @DisplayName("INT4: odd dimensions (padding in last byte)")
    void int4OddDimensions() {
        // 3 dimensions: levels [15, 0, 7]
        float[] query = {1.0f, 1.0f, 1.0f};
        float[] centroids4 = new float[16];
        for (int i = 0; i < 16; i++) {
            centroids4[i] = (float) i;
        }

        int[] levels = {15, 0, 7};
        byte[] packedDoc = NibblePacker.pack(levels, levels.length);

        // Expected: 1.0*15.0 + 1.0*0.0 + 1.0*7.0 = 22.0
        float expected = 22.0f;

        assertThat(PackedDotProduct.computeInt4(query, packedDoc, centroids4, 3))
                .isCloseTo(expected, within(TOLERANCE));
        assertThat(PackedDotProduct.computeInt4Scalar(query, packedDoc, centroids4, 3))
                .isCloseTo(expected, within(TOLERANCE));
    }

    @Test
    @DisplayName("INT4: SIMD and scalar produce identical results for 384 dimensions")
    void int4SimdEqualsScalarLargeDimension() {
        int dimensions = 384;
        Random rng = new Random(42);

        float[] query = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            query[i] = rng.nextFloat() * 2.0f - 1.0f;
        }

        float[] centroids4 = new float[16];
        for (int i = 0; i < 16; i++) {
            centroids4[i] = rng.nextFloat() * 2.0f - 1.0f;
        }

        int[] levels = new int[dimensions];
        for (int i = 0; i < dimensions; i++) {
            levels[i] = rng.nextInt(16);
        }
        byte[] packedDoc = NibblePacker.pack(levels, levels.length);

        float simdResult = PackedDotProduct.computeInt4(query, packedDoc, centroids4, dimensions);
        float scalarResult = PackedDotProduct.computeInt4Scalar(query, packedDoc, centroids4, dimensions);

        assertThat(simdResult).isEqualTo(scalarResult);
    }

    @Test
    @DisplayName("INT4: single dimension")
    void int4SingleDimension() {
        float[] query = {3.0f};
        float[] centroids4 = new float[16];
        centroids4[5] = 2.0f;

        int[] levels = {5};
        byte[] packedDoc = NibblePacker.pack(levels, levels.length);

        // Expected: 3.0 * 2.0 = 6.0
        float expected = 6.0f;

        assertThat(PackedDotProduct.computeInt4(query, packedDoc, centroids4, 1))
                .isCloseTo(expected, within(TOLERANCE));
        assertThat(PackedDotProduct.computeInt4Scalar(query, packedDoc, centroids4, 1))
                .isCloseTo(expected, within(TOLERANCE));
    }

    // ── INT2 Tests ──

    @Test
    @DisplayName("INT2: simple known dot product with 4 dimensions")
    void int2SimpleDotProduct() {
        // 4 dimensions: levels [0, 1, 2, 3]
        float[] query = {1.0f, 2.0f, 3.0f, 4.0f};
        float[] centroids2 = {0.0f, 1.0f, 2.0f, 3.0f};

        int[] levels = {0, 1, 2, 3};
        byte[] packedDoc = CrumbPacker.pack(levels, levels.length);

        // Expected: 1.0*0.0 + 2.0*1.0 + 3.0*2.0 + 4.0*3.0 = 0 + 2 + 6 + 12 = 20.0
        float expected = 20.0f;

        assertThat(PackedDotProduct.computeInt2(query, packedDoc, centroids2, 4))
                .isCloseTo(expected, within(TOLERANCE));
        assertThat(PackedDotProduct.computeInt2Scalar(query, packedDoc, centroids2, 4))
                .isCloseTo(expected, within(TOLERANCE));
    }

    @Test
    @DisplayName("INT2: non-multiple-of-4 dimensions (5 dimensions)")
    void int2NonMultipleOf4Dimensions() {
        // 5 dimensions: levels [3, 2, 1, 0, 3]
        float[] query = {1.0f, 1.0f, 1.0f, 1.0f, 1.0f};
        float[] centroids2 = {0.0f, 1.0f, 2.0f, 3.0f};

        int[] levels = {3, 2, 1, 0, 3};
        byte[] packedDoc = CrumbPacker.pack(levels, levels.length);

        // Expected: 3.0 + 2.0 + 1.0 + 0.0 + 3.0 = 9.0
        float expected = 9.0f;

        assertThat(PackedDotProduct.computeInt2(query, packedDoc, centroids2, 5))
                .isCloseTo(expected, within(TOLERANCE));
        assertThat(PackedDotProduct.computeInt2Scalar(query, packedDoc, centroids2, 5))
                .isCloseTo(expected, within(TOLERANCE));
    }

    @Test
    @DisplayName("INT2: SIMD and scalar produce identical results for 384 dimensions")
    void int2SimdEqualsScalarLargeDimension() {
        int dimensions = 384;
        Random rng = new Random(123);

        float[] query = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            query[i] = rng.nextFloat() * 2.0f - 1.0f;
        }

        float[] centroids2 = new float[4];
        for (int i = 0; i < 4; i++) {
            centroids2[i] = rng.nextFloat() * 2.0f - 1.0f;
        }

        int[] levels = new int[dimensions];
        for (int i = 0; i < dimensions; i++) {
            levels[i] = rng.nextInt(4);
        }
        byte[] packedDoc = CrumbPacker.pack(levels, levels.length);

        float simdResult = PackedDotProduct.computeInt2(query, packedDoc, centroids2, dimensions);
        float scalarResult = PackedDotProduct.computeInt2Scalar(query, packedDoc, centroids2, dimensions);

        assertThat(simdResult).isEqualTo(scalarResult);
    }

    @Test
    @DisplayName("INT2: single dimension")
    void int2SingleDimension() {
        float[] query = {5.0f};
        float[] centroids2 = {0.0f, 1.0f, 2.0f, 3.0f};

        int[] levels = {2};
        byte[] packedDoc = CrumbPacker.pack(levels, levels.length);

        // Expected: 5.0 * 2.0 = 10.0
        float expected = 10.0f;

        assertThat(PackedDotProduct.computeInt2(query, packedDoc, centroids2, 1))
                .isCloseTo(expected, within(TOLERANCE));
        assertThat(PackedDotProduct.computeInt2Scalar(query, packedDoc, centroids2, 1))
                .isCloseTo(expected, within(TOLERANCE));
    }

    @Test
    @DisplayName("INT4 and INT2: zero query produces zero result")
    void zeroQueryProducesZero() {
        int dimensions = 16;
        float[] query = new float[dimensions];
        float[] centroids4 = new float[16];
        float[] centroids2 = new float[4];
        for (int i = 0; i < 16; i++) centroids4[i] = (float) i;
        for (int i = 0; i < 4; i++) centroids2[i] = (float) i;

        int[] levels4 = new int[dimensions];
        int[] levels2 = new int[dimensions];
        for (int i = 0; i < dimensions; i++) {
            levels4[i] = i % 16;
            levels2[i] = i % 4;
        }
        byte[] packed4 = NibblePacker.pack(levels4, levels4.length);
        byte[] packed2 = CrumbPacker.pack(levels2, levels2.length);

        assertThat(PackedDotProduct.computeInt4(query, packed4, centroids4, dimensions))
                .isCloseTo(0.0f, within(TOLERANCE));
        assertThat(PackedDotProduct.computeInt2(query, packed2, centroids2, dimensions))
                .isCloseTo(0.0f, within(TOLERANCE));
    }

    @Test
    @DisplayName("INT4: arbitrary dimensionality (17 - not aligned to any SIMD width)")
    void int4ArbitraryDimensionality() {
        int dimensions = 17;
        Random rng = new Random(77);

        float[] query = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            query[i] = rng.nextFloat();
        }

        float[] centroids4 = new float[16];
        for (int i = 0; i < 16; i++) {
            centroids4[i] = rng.nextFloat();
        }

        int[] levels = new int[dimensions];
        for (int i = 0; i < dimensions; i++) {
            levels[i] = rng.nextInt(16);
        }
        byte[] packedDoc = NibblePacker.pack(levels, levels.length);

        float simd = PackedDotProduct.computeInt4(query, packedDoc, centroids4, dimensions);
        float scalar = PackedDotProduct.computeInt4Scalar(query, packedDoc, centroids4, dimensions);

        assertThat(simd).isEqualTo(scalar);
    }

    @Test
    @DisplayName("INT2: arbitrary dimensionality (13 - not aligned to any SIMD width)")
    void int2ArbitraryDimensionality() {
        int dimensions = 13;
        Random rng = new Random(99);

        float[] query = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            query[i] = rng.nextFloat();
        }

        float[] centroids2 = new float[4];
        for (int i = 0; i < 4; i++) {
            centroids2[i] = rng.nextFloat();
        }

        int[] levels = new int[dimensions];
        for (int i = 0; i < dimensions; i++) {
            levels[i] = rng.nextInt(4);
        }
        byte[] packedDoc = CrumbPacker.pack(levels, levels.length);

        float simd = PackedDotProduct.computeInt2(query, packedDoc, centroids2, dimensions);
        float scalar = PackedDotProduct.computeInt2Scalar(query, packedDoc, centroids2, dimensions);

        assertThat(simd).isEqualTo(scalar);
    }
}
