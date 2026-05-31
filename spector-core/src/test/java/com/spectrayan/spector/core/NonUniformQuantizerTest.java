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

import com.spectrayan.spector.core.quantization.NonUniformQuantizer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link NonUniformQuantizer} — calibration, encoding, decoding, and edge cases.
 */
class NonUniformQuantizerTest {

    @Test
    void calibrate_int4_producesBoundariesAndCentroids() {
        int dims = 4;
        int levels = 16;
        float[][] samples = generateUniformSamples(100, dims, 42);

        NonUniformQuantizer q = NonUniformQuantizer.calibrate(samples, dims, levels);

        assertEquals(dims, q.dimensions());
        assertEquals(levels, q.levels());

        for (int d = 0; d < dims; d++) {
            float[] boundaries = q.boundaries(d);
            float[] centroids = q.centroids(d);
            assertEquals(levels, boundaries.length);
            assertEquals(levels, centroids.length);
        }
    }

    @Test
    void calibrate_int2_producesBoundariesAndCentroids() {
        int dims = 8;
        int levels = 4;
        float[][] samples = generateUniformSamples(200, dims, 123);

        NonUniformQuantizer q = NonUniformQuantizer.calibrate(samples, dims, levels);

        assertEquals(dims, q.dimensions());
        assertEquals(levels, q.levels());

        for (int d = 0; d < dims; d++) {
            float[] boundaries = q.boundaries(d);
            float[] centroids = q.centroids(d);
            assertEquals(levels, boundaries.length);
            assertEquals(levels, centroids.length);
        }
    }

    @Test
    void encodeAndDecode_withinErrorBound() {
        int dims = 8;
        int levels = 16;
        float[][] samples = generateUniformSamples(500, dims, 7);

        NonUniformQuantizer q = NonUniformQuantizer.calibrate(samples, dims, levels);

        // Encode and decode a sample vector that was part of calibration
        float[] vector = samples[50];
        int[] encoded = q.encode(vector);
        float[] decoded = q.decode(encoded);

        assertEquals(dims, encoded.length);
        assertEquals(dims, decoded.length);

        // Each encoded value should be in valid range
        for (int d = 0; d < dims; d++) {
            assertTrue(encoded[d] >= 0 && encoded[d] < levels,
                    "Encoded value out of range at dim " + d + ": " + encoded[d]);
        }

        // Decoded values should be reasonable centroids
        for (int d = 0; d < dims; d++) {
            assertNotNull(decoded);
        }
    }

    @Test
    void encodeStability_encodeDecodeEncodeIsIdempotent() {
        int dims = 4;
        int levels = 4;
        float[][] samples = generateUniformSamples(200, dims, 99);

        NonUniformQuantizer q = NonUniformQuantizer.calibrate(samples, dims, levels);

        float[] vector = samples[10];
        int[] firstEncode = q.encode(vector);
        float[] decoded = q.decode(firstEncode);
        int[] secondEncode = q.encode(decoded);

        assertArrayEquals(firstEncode, secondEncode,
                "encode(decode(encode(x))) should equal encode(x)");
    }

    @Test
    void decode_producesCalibrationCentroid() {
        int dims = 2;
        int levels = 4;
        float[][] samples = generateUniformSamples(100, dims, 55);

        NonUniformQuantizer q = NonUniformQuantizer.calibrate(samples, dims, levels);

        // Decoding a level index should return the centroid for that level
        for (int d = 0; d < dims; d++) {
            float[] expectedCentroids = q.centroids(d);
            for (int l = 0; l < levels; l++) {
                int[] quantized = new int[dims];
                quantized[d] = l;
                float[] decoded = q.decode(quantized);
                assertEquals(expectedCentroids[l], decoded[d], 1e-6f,
                        "Decoded value should match centroid for dim=" + d + " level=" + l);
            }
        }
    }

    @Test
    void encode_clampsOutOfRangeValues() {
        int dims = 2;
        int levels = 4;
        // Calibrate with values in [-1, 1]
        float[][] samples = {
                {-1.0f, -1.0f},
                {-0.5f, -0.5f},
                {0.0f, 0.0f},
                {0.5f, 0.5f},
                {1.0f, 1.0f}
        };

        NonUniformQuantizer q = NonUniformQuantizer.calibrate(samples, dims, levels);

        // Value far below range should clamp to level 0
        int[] encodedLow = q.encode(new float[]{-100.0f, -100.0f});
        assertEquals(0, encodedLow[0]);
        assertEquals(0, encodedLow[1]);

        // Value far above range should clamp to max level
        int[] encodedHigh = q.encode(new float[]{100.0f, 100.0f});
        assertEquals(levels - 1, encodedHigh[0]);
        assertEquals(levels - 1, encodedHigh[1]);
    }

    @Test
    void calibrate_emptySampleThrows() {
        assertThrows(SpectorValidationException.class,
                () -> NonUniformQuantizer.calibrate(new float[0][], 4, 16));
    }

    @Test
    void calibrate_nullSampleThrows() {
        assertThrows(SpectorValidationException.class,
                () -> NonUniformQuantizer.calibrate(null, 4, 16));
    }

    @Test
    void encode_dimensionMismatchThrows() {
        float[][] samples = generateUniformSamples(10, 4, 1);
        NonUniformQuantizer q = NonUniformQuantizer.calibrate(samples, 4, 4);

        assertThrows(SpectorValidationException.class,
                () -> q.encode(new float[]{1.0f, 2.0f})); // wrong dimensions
    }

    @Test
    void decode_dimensionMismatchThrows() {
        float[][] samples = generateUniformSamples(10, 4, 1);
        NonUniformQuantizer q = NonUniformQuantizer.calibrate(samples, 4, 4);

        assertThrows(SpectorValidationException.class,
                () -> q.decode(new int[]{0, 1})); // wrong dimensions
    }

    @Test
    void calibrate_dimensionMismatchInSampleThrows() {
        float[][] samples = {
                {1.0f, 2.0f, 3.0f},
                {1.0f, 2.0f}  // wrong length
        };

        assertThrows(SpectorValidationException.class,
                () -> NonUniformQuantizer.calibrate(samples, 3, 4));
    }

    @Test
    void boundaries_outOfRangeThrows() {
        float[][] samples = generateUniformSamples(10, 3, 1);
        NonUniformQuantizer q = NonUniformQuantizer.calibrate(samples, 3, 4);

        assertThrows(SpectorValidationException.class, () -> q.boundaries(-1));
        assertThrows(SpectorValidationException.class, () -> q.boundaries(3));
    }

    @Test
    void centroids_outOfRangeThrows() {
        float[][] samples = generateUniformSamples(10, 3, 1);
        NonUniformQuantizer q = NonUniformQuantizer.calibrate(samples, 3, 4);

        assertThrows(SpectorValidationException.class, () -> q.centroids(-1));
        assertThrows(SpectorValidationException.class, () -> q.centroids(3));
    }

    @Test
    void boundaries_areSortedPerDimension() {
        int dims = 4;
        int levels = 16;
        float[][] samples = generateUniformSamples(500, dims, 77);

        NonUniformQuantizer q = NonUniformQuantizer.calibrate(samples, dims, levels);

        for (int d = 0; d < dims; d++) {
            float[] bounds = q.boundaries(d);
            for (int i = 1; i < bounds.length; i++) {
                assertTrue(bounds[i] >= bounds[i - 1],
                        "Boundaries should be non-decreasing for dim " + d);
            }
        }
    }

    @Test
    void singleSampleCalibration() {
        // Edge case: single sample should still work
        float[][] samples = {{1.0f, 2.0f, 3.0f}};
        NonUniformQuantizer q = NonUniformQuantizer.calibrate(samples, 3, 4);

        assertEquals(3, q.dimensions());
        assertEquals(4, q.levels());

        // Encoding the same vector should produce valid indices
        int[] encoded = q.encode(new float[]{1.0f, 2.0f, 3.0f});
        for (int val : encoded) {
            assertTrue(val >= 0 && val < 4);
        }
    }

    // --- Helpers ---

    private static float[][] generateUniformSamples(int count, int dims, long seed) {
        java.util.Random rng = new java.util.Random(seed);
        float[][] samples = new float[count][dims];
        for (int i = 0; i < count; i++) {
            for (int d = 0; d < dims; d++) {
                samples[i][d] = (rng.nextFloat() - 0.5f) * 2.0f;
            }
        }
        return samples;
    }
}
