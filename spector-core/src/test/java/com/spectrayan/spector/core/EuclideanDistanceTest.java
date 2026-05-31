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

import com.spectrayan.spector.core.similarity.EuclideanDistance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link EuclideanDistance} SIMD kernel.
 */
class EuclideanDistanceTest {

    @Test
    void identicalVectorsHaveZeroDistance() {
        float[] v = {1f, 2f, 3f, 4f};
        assertThat(EuclideanDistance.compute(v, v)).isEqualTo(0f);
        assertThat(EuclideanDistance.computeSquared(v, v)).isEqualTo(0f);
    }

    @Test
    void unitVectors() {
        float[] a = {1f, 0f, 0f};
        float[] b = {0f, 1f, 0f};
        // distance = sqrt(1 + 1) = sqrt(2)
        assertThat(EuclideanDistance.compute(a, b)).isCloseTo((float) Math.sqrt(2), within(1e-6f));
        assertThat(EuclideanDistance.computeSquared(a, b)).isCloseTo(2f, within(1e-6f));
    }

    @Test
    void knownDistance() {
        float[] a = {0f, 0f, 0f};
        float[] b = {3f, 4f, 0f};
        assertThat(EuclideanDistance.compute(a, b)).isCloseTo(5f, within(1e-6f));
        assertThat(EuclideanDistance.computeSquared(a, b)).isCloseTo(25f, within(1e-6f));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 7, 8, 9, 15, 16, 17, 31, 32, 33, 64, 128, 256, 384, 768, 1536})
    void matchesScalarReference(int dim) {
        float[] a = randomVector(dim, 42);
        float[] b = randomVector(dim, 99);

        float expectedSq = scalarEuclideanSquared(a, b);
        float actualSq = EuclideanDistance.computeSquared(a, b);

        assertThat(actualSq).isCloseTo(expectedSq, within(Math.abs(expectedSq) * 1e-5f + 1e-6f));

        float expected = (float) Math.sqrt(expectedSq);
        float actual = EuclideanDistance.compute(a, b);
        assertThat(actual).isCloseTo(expected, within(Math.abs(expected) * 1e-5f + 1e-6f));
    }

    @Test
    void squaredPreservesRankOrder() {
        float[] query = {1f, 1f, 1f};
        float[] near = {1.1f, 1.1f, 1.1f};
        float[] far = {5f, 5f, 5f};

        float nearDist = EuclideanDistance.computeSquared(query, near);
        float farDist = EuclideanDistance.computeSquared(query, far);
        assertThat(nearDist).isLessThan(farDist);
    }

    // ── Scalar reference ──

    private static float scalarEuclideanSquared(float[] a, float[] b) {
        float sum = 0f;
        for (int i = 0; i < a.length; i++) {
            float diff = a[i] - b[i];
            sum += diff * diff;
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
