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
package com.spectrayan.spector.index.pq;

import com.spectrayan.spector.commons.error.SpectorValidationException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ProductQuantizer} — PQ training, encoding, decoding, and ADC.
 */
class ProductQuantizerTest {

    @Test
    void train_createsValidCodebooks() {
        int dims = 16;
        int M = 4;
        float[][] samples = randomVectors(500, dims, 42);

        ProductQuantizer pq = ProductQuantizer.train(samples, dims, M);

        assertEquals(dims, pq.dimensions());
        assertEquals(M, pq.numSubspaces());
        assertEquals(dims / M, pq.subDimension());
    }

    @Test
    void encode_producesCodeOfCorrectLength() {
        int dims = 32;
        int M = 8;
        float[][] samples = randomVectors(300, dims, 7);
        ProductQuantizer pq = ProductQuantizer.train(samples, dims, M);

        byte[] code = pq.encode(samples[0]);
        assertEquals(M, code.length);

        // Each byte should be in [0, 255]
        for (byte b : code) {
            int idx = Byte.toUnsignedInt(b);
            assertTrue(idx >= 0 && idx < 256);
        }
    }

    @Test
    void decode_producesApproximateReconstruction() {
        int dims = 16;
        int M = 4;
        float[][] samples = randomVectors(500, dims, 42);
        ProductQuantizer pq = ProductQuantizer.train(samples, dims, M);

        float[] original = samples[0];
        byte[] code = pq.encode(original);
        float[] decoded = pq.decode(code);

        assertEquals(dims, decoded.length);

        // The reconstruction should be roughly close to original
        float error = 0;
        for (int d = 0; d < dims; d++) {
            float diff = original[d] - decoded[d];
            error += diff * diff;
        }
        float mse = error / dims;
        // MSE should be reasonable (not infinity)
        assertTrue(mse < 1.0f, "MSE too high: " + mse);
    }

    @Test
    void adcDistance_matchesReconstructedDistance() {
        int dims = 16;
        int M = 4;
        float[][] samples = randomVectors(500, dims, 42);
        ProductQuantizer pq = ProductQuantizer.train(samples, dims, M);

        float[] query = samples[0];
        byte[] dbCode = pq.encode(samples[1]);

        // ADC distance
        float[][] table = pq.computeDistanceTable(query);
        float adcDist = ProductQuantizer.adcDistance(table, dbCode);

        // Reconstructed L2 distance
        float[] decoded = pq.decode(dbCode);
        float exactDist = 0;
        for (int d = 0; d < dims; d++) {
            float diff = query[d] - decoded[d];
            exactDist += diff * diff;
        }

        // ADC and decoded distances should be identical
        // (ADC is exact for the PQ representation, just computed differently)
        assertEquals(exactDist, adcDist, 1e-3f,
                "ADC distance should match decoded distance");
    }

    @Test
    void batchEncode_matchesSingleEncode() {
        int dims = 16;
        int M = 4;
        float[][] samples = randomVectors(100, dims, 7);
        ProductQuantizer pq = ProductQuantizer.train(samples, dims, M);

        byte[][] batch = pq.encodeBatch(samples);
        for (int i = 0; i < samples.length; i++) {
            assertArrayEquals(pq.encode(samples[i]), batch[i],
                    "Batch encode should match single encode for index " + i);
        }
    }

    @Test
    void dimensionsMustBeDivisibleByM() {
        float[][] samples = randomVectors(100, 15, 42);
        assertThrows(SpectorValidationException.class,
                () -> ProductQuantizer.train(samples, 15, 4),
                "15 not divisible by 4");
    }

    @Test
    void nearestCentroidSearch_ordersCorrectly() {
        int dims = 16;
        int M = 4;
        float[][] samples = randomVectors(300, dims, 42);
        ProductQuantizer pq = ProductQuantizer.train(samples, dims, M);

        float[] query = samples[0];
        float[][] table = pq.computeDistanceTable(query);

        // Encode query itself — its ADC distance should be small (but not zero due to quantization)
        byte[] queryCode = pq.encode(query);
        float selfDist = ProductQuantizer.adcDistance(table, queryCode);

        // A random distant vector should have larger ADC distance
        float[] distant = new float[dims];
        for (int d = 0; d < dims; d++) distant[d] = query[d] + 10.0f;
        byte[] distantCode = pq.encode(distant);
        float distantDist = ProductQuantizer.adcDistance(table, distantCode);

        assertTrue(selfDist < distantDist,
                "Self-distance (" + selfDist + ") should be less than distant vector distance (" + distantDist + ")");
    }

    // ─────────────── Helpers ───────────────

    private float[][] randomVectors(int n, int dims, long seed) {
        java.util.Random rng = new java.util.Random(seed);
        float[][] vectors = new float[n][dims];
        for (int i = 0; i < n; i++) {
            for (int d = 0; d < dims; d++) {
                vectors[i][d] = rng.nextFloat() - 0.5f;
            }
        }
        return vectors;
    }
}
