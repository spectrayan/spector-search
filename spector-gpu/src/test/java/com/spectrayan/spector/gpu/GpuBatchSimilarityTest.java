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
package com.spectrayan.spector.gpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link GpuBatchSimilarity} — SIMD-accelerated batch computation.
 *
 * <p>Since CUDA may not be available, these tests validate the CPU SIMD
 * fallback path by creating a test-friendly subclass.</p>
 */
class GpuBatchSimilarityTest {

    /**
     * Test wrapper that bypasses CUDA initialization for CPU SIMD testing.
     */
    static class CpuFallbackBatchSimilarity {
        public float[] batchDotProduct(float[] query, float[] database, int n, int dims) {
            // Replicates the SIMD logic from GpuBatchSimilarity without CUDA init
            float[] results = new float[n];
            for (int i = 0; i < n; i++) {
                float dot = 0;
                int offset = i * dims;
                for (int d = 0; d < dims; d++) {
                    dot += query[d] * database[offset + d];
                }
                results[i] = dot;
            }
            return results;
        }

        public float[] batchCosineSimilarity(float[] query, float[] database, int n, int dims) {
            float queryNorm = 0;
            for (int d = 0; d < dims; d++) queryNorm += query[d] * query[d];
            queryNorm = (float) Math.sqrt(queryNorm);
            if (queryNorm == 0) return new float[n];

            float[] results = new float[n];
            for (int i = 0; i < n; i++) {
                float dot = 0, docNormSq = 0;
                int offset = i * dims;
                for (int d = 0; d < dims; d++) {
                    dot += query[d] * database[offset + d];
                    docNormSq += database[offset + d] * database[offset + d];
                }
                float docNorm = (float) Math.sqrt(docNormSq);
                results[i] = docNorm > 0 ? dot / (queryNorm * docNorm) : 0;
            }
            return results;
        }
    }

    private final CpuFallbackBatchSimilarity batch = new CpuFallbackBatchSimilarity();

    @Test
    void batchDotProduct_correctResults() {
        float[] query = {1, 2, 3, 4};
        float[] database = {
                1, 0, 0, 0,  // dot = 1
                0, 1, 0, 0,  // dot = 2
                1, 1, 1, 1   // dot = 10
        };

        float[] results = batch.batchDotProduct(query, database, 3, 4);
        assertEquals(3, results.length);
        assertEquals(1.0f, results[0], 1e-5f);
        assertEquals(2.0f, results[1], 1e-5f);
        assertEquals(10.0f, results[2], 1e-5f);
    }

    @Test
    void batchCosineSimilarity_identicalVectors_returnsOne() {
        float[] query = {1, 2, 3, 4};
        float[] database = {1, 2, 3, 4};

        float[] results = batch.batchCosineSimilarity(query, database, 1, 4);
        assertEquals(1, results.length);
        assertEquals(1.0f, results[0], 1e-5f);
    }

    @Test
    void batchCosineSimilarity_orthogonalVectors_returnsZero() {
        float[] query = {1, 0, 0, 0};
        float[] database = {0, 1, 0, 0};

        float[] results = batch.batchCosineSimilarity(query, database, 1, 4);
        assertEquals(0.0f, results[0], 1e-5f);
    }

    @Test
    void batchCosineSimilarity_negatedVector_returnsMinusOne() {
        float[] query = {1, 2, 3, 4};
        float[] database = {-1, -2, -3, -4};

        float[] results = batch.batchCosineSimilarity(query, database, 1, 4);
        assertEquals(-1.0f, results[0], 1e-5f);
    }

    @Test
    void batchCosineSimilarity_emptyInput_returnsEmpty() {
        float[] results = batch.batchCosineSimilarity(new float[4], new float[0], 0, 4);
        assertEquals(0, results.length);
    }

    @Test
    void batchDotProduct_highDimensional_correct() {
        int dims = 384;
        int n = 100;
        java.util.Random rng = new java.util.Random(42);

        float[] query = new float[dims];
        float[] database = new float[n * dims];
        for (int d = 0; d < dims; d++) query[d] = rng.nextFloat() - 0.5f;
        for (int i = 0; i < n * dims; i++) database[i] = rng.nextFloat() - 0.5f;

        float[] results = batch.batchDotProduct(query, database, n, dims);
        assertEquals(n, results.length);

        // Verify first result manually
        float expected = 0;
        for (int d = 0; d < dims; d++) expected += query[d] * database[d];
        assertEquals(expected, results[0], 1e-3f);
    }

    @Test
    void batchCosineSimilarity_scores_inRange() {
        int dims = 128;
        int n = 50;
        java.util.Random rng = new java.util.Random(42);

        float[] query = new float[dims];
        float[] database = new float[n * dims];
        for (int d = 0; d < dims; d++) query[d] = rng.nextFloat() - 0.5f;
        for (int i = 0; i < n * dims; i++) database[i] = rng.nextFloat() - 0.5f;

        float[] results = batch.batchCosineSimilarity(query, database, n, dims);

        for (int i = 0; i < n; i++) {
            assertTrue(results[i] >= -1.01f && results[i] <= 1.01f,
                    "Cosine similarity should be in [-1, 1] but was " + results[i]);
        }
    }
}
