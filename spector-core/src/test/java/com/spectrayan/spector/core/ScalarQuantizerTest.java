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

import com.spectrayan.spector.core.similarity.CosineSimilarity;
import com.spectrayan.spector.core.similarity.QuantizedCosineSimilarity;
import com.spectrayan.spector.core.quantization.ScalarQuantizer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ScalarQuantizer} — calibration, encoding, decoding, and accuracy.
 */
class ScalarQuantizerTest {

    @Test
    void calibrateAndEncode_simpleVector() {
        float[][] samples = {
                {0.0f, 1.0f, -1.0f, 0.5f},
                {1.0f, 0.0f, 0.5f, -0.5f},
                {-1.0f, 0.5f, 0.0f, 1.0f}
        };

        ScalarQuantizer sq = ScalarQuantizer.calibrate(samples, 4);

        byte[] encoded = sq.encode(new float[]{0.0f, 0.5f, 0.0f, 0.0f});
        assertNotNull(encoded);
        assertEquals(4, encoded.length);

        // Decode and verify reconstruction
        float[] decoded = sq.decode(encoded);
        assertEquals(4, decoded.length);
        for (int i = 0; i < 4; i++) {
            // Should be within 2% of original value range
            assertEquals(new float[]{0.0f, 0.5f, 0.0f, 0.0f}[i], decoded[i], 0.05f,
                    "Dimension " + i + " reconstruction error too high");
        }
    }

    @Test
    void roundTripAccuracy_128dims() {
        int dims = 128;
        int sampleCount = 1000;
        float[][] samples = new float[sampleCount][dims];

        // Generate random vectors
        java.util.Random rng = new java.util.Random(42);
        for (int i = 0; i < sampleCount; i++) {
            for (int d = 0; d < dims; d++) {
                samples[i][d] = (rng.nextFloat() - 0.5f) * 2.0f;
            }
        }

        ScalarQuantizer sq = ScalarQuantizer.calibrate(samples, dims);

        // Measure reconstruction error
        double totalError = 0;
        for (float[] sample : samples) {
            byte[] encoded = sq.encode(sample);
            float[] decoded = sq.decode(encoded);
            for (int d = 0; d < dims; d++) {
                totalError += Math.abs(sample[d] - decoded[d]);
            }
        }
        double avgError = totalError / (sampleCount * dims);
        // Average per-dimension error should be < 1% of range
        assertTrue(avgError < 0.02f, "Average quantization error too high: " + avgError);
    }

    @Test
    void compressionRatio() {
        float[][] samples = {{1.0f, 2.0f, 3.0f}};
        ScalarQuantizer sq = ScalarQuantizer.calibrate(samples, 3);
        assertEquals(0.25f, sq.compressionRatio());
    }

    @Test
    void fromBounds_restoresCorrectly() {
        float[] mins = {-1.0f, -2.0f};
        float[] maxs = {1.0f, 2.0f};
        ScalarQuantizer sq = ScalarQuantizer.fromBounds(2, mins, maxs);

        byte[] encoded = sq.encode(new float[]{0.0f, 0.0f});
        float[] decoded = sq.decode(encoded);

        assertEquals(0.0f, decoded[0], 0.02f);
        assertEquals(0.0f, decoded[1], 0.04f);
    }

    @Test
    void emptySampleThrows() {
        assertThrows(SpectorValidationException.class,
                () -> ScalarQuantizer.calibrate(new float[0][], 4));
    }

    @Test
    void cosineSimilarityPreserved() {
        int dims = 128;
        java.util.Random rng = new java.util.Random(123);

        float[][] samples = new float[500][dims];
        for (int i = 0; i < 500; i++) {
            for (int d = 0; d < dims; d++) {
                samples[i][d] = (rng.nextFloat() - 0.5f) * 2;
            }
        }

        ScalarQuantizer sq = ScalarQuantizer.calibrate(samples, dims);

        // Measure cosine similarity preservation
        float[] query = samples[0];
        float[] doc = samples[1];

        float exactCosine = CosineSimilarity.compute(query, doc);
        float quantizedCosine = QuantizedCosineSimilarity.compute(
                query, sq.encode(doc), sq.mins(), sq.scales(), dims);

        // Should be within 5% of exact
        assertEquals(exactCosine, quantizedCosine, 0.05f,
                "Cosine similarity divergence too high");
    }
}
