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

import com.spectrayan.spector.commons.error.SpectorException;
import com.spectrayan.spector.commons.error.SpectorValidationException;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CudaHnswKernel}.
 *
 * <p>Validates the CPU SIMD fallback path (GPU may not be available in CI).
 * The interface contract is identical regardless of backend.</p>
 */
class CudaHnswKernelTest {

    private CudaHnswKernel kernel;

    @BeforeEach
    void setUp() {
        kernel = new CudaHnswKernel(false); // CPU SIMD fallback
    }

    @AfterEach
    void tearDown() {
        kernel.close();
    }

    // ── Cosine Similarity ───────────────────────────────────────────────────────

    @Test
    void computeCosine_identicalVectors_returnsOne() {
        int dims = 64;
        float[] query = createRandomVector(dims, 42);
        float[] candidates = query.clone();

        float[] results = kernel.computeCosine(query, candidates, 1, dims);

        assertEquals(1, results.length);
        assertEquals(1.0f, results[0], 1e-5f);
    }

    @Test
    void computeCosine_oppositeVectors_returnsNegativeOne() {
        int dims = 64;
        float[] query = createUniformVector(dims, 1.0f);
        float[] candidates = createUniformVector(dims, -1.0f);

        float[] results = kernel.computeCosine(query, candidates, 1, dims);

        assertEquals(-1.0f, results[0], 1e-5f);
    }

    @Test
    void computeCosine_orthogonalVectors_returnsZero() {
        int dims = 64;
        float[] query = new float[dims];
        query[0] = 1.0f;
        float[] candidates = new float[dims];
        candidates[1] = 1.0f;

        float[] results = kernel.computeCosine(query, candidates, 1, dims);

        assertEquals(0.0f, results[0], 1e-6f);
    }

    @Test
    void computeCosine_multipleCandidates_correctResults() {
        int dims = 64;
        float[] query = createRandomVector(dims, 42);
        float[] candidates = new float[3 * dims];
        System.arraycopy(createRandomVector(dims, 10), 0, candidates, 0, dims);
        System.arraycopy(createRandomVector(dims, 20), 0, candidates, dims, dims);
        System.arraycopy(query, 0, candidates, 2 * dims, dims); // identical to query

        float[] results = kernel.computeCosine(query, candidates, 3, dims);

        assertEquals(3, results.length);
        assertEquals(1.0f, results[2], 1e-5f); // identical should be 1.0
        assertTrue(Math.abs(results[0]) <= 1.0f);
        assertTrue(Math.abs(results[1]) <= 1.0f);
    }

    @Test
    void computeCosine_matchesScalarCosine() {
        int dims = 128;
        int k = 20;
        float[] query = createRandomVector(dims, 42);
        float[] candidates = createRandomVector(k * dims, 99);

        float[] results = kernel.computeCosine(query, candidates, k, dims);

        for (int i = 0; i < k; i++) {
            float expected = scalarCosine(query, candidates, i * dims, dims);
            assertEquals(expected, results[i], 1e-5f, "Mismatch at candidate " + i);
        }
    }

    @Test
    void computeCosine_384dim_typicalHNSW() {
        int dims = 384;
        int k = 50; // typical efSearch candidates
        float[] query = createRandomVector(dims, 42);
        float[] candidates = createRandomVector(k * dims, 99);

        float[] results = kernel.computeCosine(query, candidates, k, dims);

        assertEquals(k, results.length);
        for (float r : results) {
            assertTrue(r >= -1.0f && r <= 1.0f, "Cosine out of range: " + r);
        }
    }

    @Test
    void computeCosine_emptyBatch_returnsEmpty() {
        float[] results = kernel.computeCosine(new float[64], new float[0], 0, 64);
        assertEquals(0, results.length);
    }

    // ── L2 Squared Distance ─────────────────────────────────────────────────────

    @Test
    void computeL2_identicalVectors_returnsZero() {
        int dims = 64;
        float[] query = createRandomVector(dims, 42);
        float[] candidates = query.clone();

        float[] results = kernel.computeL2(query, candidates, 1, dims);

        assertEquals(1, results.length);
        assertEquals(0.0f, results[0], 1e-5f);
    }

    @Test
    void computeL2_knownDistance_correctResult() {
        int dims = 32;
        float[] query = createUniformVector(dims, 1.0f);
        float[] candidates = createUniformVector(dims, 2.0f);

        float[] results = kernel.computeL2(query, candidates, 1, dims);

        // L2² = sum((1-2)²) = 32 * 1 = 32
        assertEquals(32.0f, results[0], 1e-5f);
    }

    @Test
    void computeL2_multipleCandidates_correctResults() {
        int dims = 64;
        int k = 10;
        float[] query = createRandomVector(dims, 42);
        float[] candidates = createRandomVector(k * dims, 99);

        float[] results = kernel.computeL2(query, candidates, k, dims);

        assertEquals(k, results.length);
        for (int i = 0; i < k; i++) {
            float expected = scalarL2Squared(query, candidates, i * dims, dims);
            assertEquals(expected, results[i], Math.abs(expected) * 1e-5f + 1e-6f,
                    "L2 mismatch at candidate " + i);
        }
    }

    @Test
    void computeL2_emptyBatch_returnsEmpty() {
        float[] results = kernel.computeL2(new float[64], new float[0], 0, 64);
        assertEquals(0, results.length);
    }

    // ── Interface Contract ──────────────────────────────────────────────────────

    @Test
    void name_returnsHnswCandidates() {
        assertEquals("hnsw-candidates", kernel.name());
    }

    @Test
    void isGpuActive_returnsFalseInFallbackMode() {
        assertFalse(kernel.isGpuActive());
    }

    @Test
    void implementsSimilarityKernel() {
        assertInstanceOf(SimilarityKernel.class, kernel);
    }

    @Test
    void close_preventsSubsequentCompute() {
        kernel.close();
        assertThrows(SpectorException.class,
                () -> kernel.computeCosine(new float[64], new float[64], 1, 64));
    }

    @Test
    void defaultConstructor_fallsBackGracefully() {
        try (var defaultKernel = new CudaHnswKernel()) {
            float[] query = createRandomVector(64, 42);
            float[] candidates = createRandomVector(64, 99);

            float[] results = defaultKernel.computeCosine(query, candidates, 1, 64);
            assertEquals(1, results.length);
        }
    }

    // ── Validation ──────────────────────────────────────────────────────────────

    @Test
    void computeCosine_nullQuery_throws() {
        assertThrows(SpectorValidationException.class,
                () -> kernel.computeCosine(null, new float[64], 1, 64));
    }

    @Test
    void computeCosine_nullCandidates_throws() {
        assertThrows(SpectorValidationException.class,
                () -> kernel.computeCosine(new float[64], null, 1, 64));
    }

    @Test
    void computeCosine_dimensionsTooSmall_throws() {
        assertThrows(SpectorValidationException.class,
                () -> kernel.computeCosine(new float[16], new float[16], 1, 16));
    }

    @Test
    void computeCosine_queryTooShort_throws() {
        assertThrows(SpectorValidationException.class,
                () -> kernel.computeCosine(new float[32], new float[64], 1, 64));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static float[] createUniformVector(int dims, float value) {
        float[] v = new float[dims];
        java.util.Arrays.fill(v, value);
        return v;
    }

    private static float[] createRandomVector(int dims, long seed) {
        java.util.Random rng = new java.util.Random(seed);
        float[] v = new float[dims];
        for (int i = 0; i < dims; i++) v[i] = rng.nextFloat() - 0.5f;
        return v;
    }

    private static float scalarCosine(float[] query, float[] candidates, int offset, int dims) {
        float dot = 0, qn = 0, cn = 0;
        for (int i = 0; i < dims; i++) {
            float q = query[i], c = candidates[offset + i];
            dot += q * c;
            qn += q * q;
            cn += c * c;
        }
        float denom = (float) Math.sqrt(qn) * (float) Math.sqrt(cn);
        return denom > 0 ? dot / denom : 0;
    }

    private static float scalarL2Squared(float[] query, float[] candidates, int offset, int dims) {
        float sum = 0;
        for (int i = 0; i < dims; i++) {
            float diff = query[i] - candidates[offset + i];
            sum += diff * diff;
        }
        return sum;
    }
}
