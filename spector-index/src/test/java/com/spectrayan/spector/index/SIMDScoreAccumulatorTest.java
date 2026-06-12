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
package com.spectrayan.spector.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

/**
 * Tests for {@link SIMDScoreAccumulator}.
 *
 * <p>Validates correctness of SIMD-accelerated array operations including
 * edge cases (scalar tail, empty arrays, single element) and consistency
 * between SIMD and scalar codepaths.</p>
 */
class SIMDScoreAccumulatorTest {

    // ══════════════════════════════════════════════════════════════
    // addArrays
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("addArrays — correct element-wise addition")
    void addArrays_correctResult() {
        float[] dst = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f};
        float[] src = {0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f};

        SIMDScoreAccumulator.addArrays(dst, src, dst.length);

        assertThat(dst[0]).isCloseTo(1.1f, within(1e-6f));
        assertThat(dst[7]).isCloseTo(8.8f, within(1e-6f));
    }

    @Test
    @DisplayName("addArrays — scalar tail (length not multiple of SIMD lanes)")
    void addArrays_scalarTail() {
        // Use a prime-length array to guarantee a scalar tail
        int n = 37;
        float[] dst = new float[n];
        float[] src = new float[n];
        for (int i = 0; i < n; i++) {
            dst[i] = i;
            src[i] = 1.0f;
        }

        SIMDScoreAccumulator.addArrays(dst, src, n);

        for (int i = 0; i < n; i++) {
            assertThat(dst[i]).isCloseTo(i + 1.0f, within(1e-6f));
        }
    }

    @Test
    @DisplayName("addArrays — single element (pure scalar)")
    void addArrays_singleElement() {
        float[] dst = {5.0f};
        float[] src = {3.0f};
        SIMDScoreAccumulator.addArrays(dst, src, 1);
        assertThat(dst[0]).isCloseTo(8.0f, within(1e-6f));
    }

    @Test
    @DisplayName("addArrays — n=0 is a no-op")
    void addArrays_emptyArrays() {
        float[] dst = {99.0f};
        float[] src = {1.0f};
        SIMDScoreAccumulator.addArrays(dst, src, 0);
        assertThat(dst[0]).isEqualTo(99.0f); // unchanged
    }

    // ══════════════════════════════════════════════════════════════
    // maxValue
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("maxValue — finds correct max in mixed array")
    void maxValue_correctMax() {
        float[] arr = {1.0f, 5.0f, 3.0f, 9.0f, 2.0f, 7.0f, 4.0f, 8.0f,
                       6.0f, 0.5f, 9.5f, 3.3f};
        assertThat(SIMDScoreAccumulator.maxValue(arr, arr.length))
                .isCloseTo(9.5f, within(1e-6f));
    }

    @Test
    @DisplayName("maxValue — all negative values")
    void maxValue_allNegative() {
        float[] arr = {-10f, -5f, -20f, -1f, -100f, -3f, -7f, -15f};
        assertThat(SIMDScoreAccumulator.maxValue(arr, arr.length))
                .isCloseTo(-1f, within(1e-6f));
    }

    @Test
    @DisplayName("maxValue — single element")
    void maxValue_singleElement() {
        assertThat(SIMDScoreAccumulator.maxValue(new float[]{42.0f}, 1))
                .isCloseTo(42.0f, within(1e-6f));
    }

    @Test
    @DisplayName("maxValue — n=0 returns NEGATIVE_INFINITY")
    void maxValue_emptyReturnsNegInf() {
        assertThat(SIMDScoreAccumulator.maxValue(new float[]{}, 0))
                .isEqualTo(Float.NEGATIVE_INFINITY);
    }

    // ══════════════════════════════════════════════════════════════
    // fmaArrays
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("fmaArrays — correct multiply-accumulate")
    void fmaArrays_correctResult() {
        float[] dst = {1.0f, 2.0f, 3.0f, 4.0f};
        float[] src = {10.0f, 20.0f, 30.0f, 40.0f};

        SIMDScoreAccumulator.fmaArrays(dst, src, 0.5f, 4);

        assertThat(dst[0]).isCloseTo(6.0f, within(1e-6f));   // 1 + 0.5*10
        assertThat(dst[3]).isCloseTo(24.0f, within(1e-6f));  // 4 + 0.5*40
    }

    @Test
    @DisplayName("fmaArrays — factor=0 leaves dst unchanged")
    void fmaArrays_zeroFactor() {
        float[] dst = {1.0f, 2.0f, 3.0f};
        float[] src = {100.0f, 200.0f, 300.0f};

        SIMDScoreAccumulator.fmaArrays(dst, src, 0.0f, 3);

        assertThat(dst[0]).isCloseTo(1.0f, within(1e-6f));
        assertThat(dst[2]).isCloseTo(3.0f, within(1e-6f));
    }

    @Test
    @DisplayName("fmaArrays — negative factor works correctly")
    void fmaArrays_negativeFactor() {
        float[] dst = {10.0f, 20.0f};
        float[] src = {1.0f, 2.0f};

        SIMDScoreAccumulator.fmaArrays(dst, src, -3.0f, 2);

        assertThat(dst[0]).isCloseTo(7.0f, within(1e-6f));   // 10 + (-3)*1
        assertThat(dst[1]).isCloseTo(14.0f, within(1e-6f));  // 20 + (-3)*2
    }

    // ══════════════════════════════════════════════════════════════
    // Smoke
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("isAvailable — returns a boolean without crashing")
    void isAvailable_returnsBoolean() {
        boolean result = SIMDScoreAccumulator.isAvailable();
        System.out.printf("SIMD available: %s (lanes: %d)%n", result,
                SIMDScoreAccumulator.laneCount());
        // No assertion on value — just verifies no crash
    }

    // ══════════════════════════════════════════════════════════════
    // Large array correctness (SIMD bulk + scalar tail)
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("addArrays — 10K elements match scalar reference")
    void addArrays_largeMatchesScalar() {
        int n = 10_000;
        Random rng = new Random(42);
        float[] dst = new float[n];
        float[] src = new float[n];
        float[] expected = new float[n];
        for (int i = 0; i < n; i++) {
            dst[i] = rng.nextFloat() * 100;
            src[i] = rng.nextFloat() * 100;
            expected[i] = dst[i] + src[i];
        }

        SIMDScoreAccumulator.addArrays(dst, src, n);

        for (int i = 0; i < n; i++) {
            assertThat(dst[i]).as("index %d", i)
                    .isCloseTo(expected[i], within(1e-4f));
        }
    }
}
