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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CudaDotProductKernel}.
 *
 * <p>Tests validate the CPU SIMD fallback path since CUDA may not be available
 * in CI/test environments. The interface contract is identical regardless of backend.</p>
 */
class CudaDotProductKernelTest {

    private CudaDotProductKernel kernel;

    @BeforeEach
    void setUp() {
        // Use CPU SIMD fallback for reliable testing
        kernel = new CudaDotProductKernel(false);
    }

    @AfterEach
    void tearDown() {
        kernel.close();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Basic correctness
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    void compute_identicalUnitVectors_returnsSquaredNorm() {
        int dims = 32;
        float[] query = createUniformVector(dims, 1.0f);
        float[] database = createUniformVector(dims, 1.0f);

        float[] results = kernel.compute(query, database, 1, dims);

        assertEquals(1, results.length);
        // dot([1,1,...,1], [1,1,...,1]) = 32
        assertEquals(32.0f, results[0], 1e-5f);
    }

    @Test
    void compute_oppositeVectors_returnsNegativeDot() {
        int dims = 32;
        float[] query = createUniformVector(dims, 1.0f);
        float[] database = createUniformVector(dims, -1.0f);

        float[] results = kernel.compute(query, database, 1, dims);

        // dot([1,1,...], [-1,-1,...]) = -32
        assertEquals(-32.0f, results[0], 1e-5f);
    }

    @Test
    void compute_orthogonalVectors_returnsZero() {
        int dims = 32;
        float[] query = new float[dims];
        query[0] = 1.0f;
        float[] database = new float[dims];
        database[1] = 1.0f;

        float[] results = kernel.compute(query, database, 1, dims);

        assertEquals(0.0f, results[0], 1e-6f);
    }

    @Test
    void compute_emptyBatch_returnsEmptyArray() {
        float[] query = new float[32];
        float[] database = new float[0];

        float[] results = kernel.compute(query, database, 0, 32);

        assertEquals(0, results.length);
    }

    @Test
    void compute_multipleDatabaseVectors_correctResults() {
        int dims = 32;
        float[] query = createUniformVector(dims, 2.0f);
        float[] database = new float[3 * dims];

        // Vector 0: all 1s -> dot = 2 * 32 = 64
        System.arraycopy(createUniformVector(dims, 1.0f), 0, database, 0, dims);
        // Vector 1: all -1s -> dot = -64
        System.arraycopy(createUniformVector(dims, -1.0f), 0, database, dims, dims);
        // Vector 2: all 3s -> dot = 2*3*32 = 192
        System.arraycopy(createUniformVector(dims, 3.0f), 0, database, 2 * dims, dims);

        float[] results = kernel.compute(query, database, 3, dims);

        assertEquals(3, results.length);
        assertEquals(64.0f, results[0], 1e-5f);
        assertEquals(-64.0f, results[1], 1e-5f);
        assertEquals(192.0f, results[2], 1e-5f);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Dimension validation
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    void compute_dimensionsTooSmall_throws() {
        float[] query = new float[16];
        float[] database = new float[16];

        assertThrows(SpectorValidationException.class,
                () -> kernel.compute(query, database, 1, 16));
    }

    @Test
    void compute_dimensionsTooLarge_throws() {
        float[] query = new float[4096];
        float[] database = new float[4096];

        assertThrows(SpectorValidationException.class,
                () -> kernel.compute(query, database, 1, 4096));
    }

    @Test
    void compute_dimensionsNotMultipleOf32_throws() {
        float[] query = new float[64];
        float[] database = new float[64];

        assertThrows(SpectorValidationException.class,
                () -> kernel.compute(query, database, 1, 48));
    }

    @Test
    void compute_nullQuery_throws() {
        assertThrows(SpectorValidationException.class,
                () -> kernel.compute(null, new float[32], 1, 32));
    }

    @Test
    void compute_nullDatabase_throws() {
        assertThrows(SpectorValidationException.class,
                () -> kernel.compute(new float[32], null, 1, 32));
    }

    @Test
    void compute_negativeBatchSize_throws() {
        assertThrows(SpectorValidationException.class,
                () -> kernel.compute(new float[32], new float[32], -1, 32));
    }

    @Test
    void compute_batchSizeTooLarge_throws() {
        assertThrows(SpectorValidationException.class,
                () -> kernel.compute(new float[32], new float[32], 1_000_001, 32));
    }

    @Test
    void compute_queryTooShort_throws() {
        float[] query = new float[16]; // shorter than dims=32
        float[] database = new float[32];

        assertThrows(SpectorValidationException.class,
                () -> kernel.compute(query, database, 1, 32));
    }

    @Test
    void compute_databaseTooShort_throws() {
        float[] query = new float[32];
        float[] database = new float[32]; // 1 vector, but asking for 2

        assertThrows(SpectorValidationException.class,
                () -> kernel.compute(query, database, 2, 32));
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Supported dimension range
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    void compute_minDimension_works() {
        int dims = 32;
        float[] query = createRandomVector(dims, 42);
        float[] database = createRandomVector(dims, 99);

        float[] results = kernel.compute(query, database, 1, dims);

        assertEquals(1, results.length);
        assertFalse(Float.isNaN(results[0]));
    }

    @Test
    void compute_maxDimension_works() {
        int dims = 2048;
        float[] query = createRandomVector(dims, 42);
        float[] database = createRandomVector(dims, 99);

        float[] results = kernel.compute(query, database, 1, dims);

        assertEquals(1, results.length);
        assertFalse(Float.isNaN(results[0]));
    }

    @Test
    void compute_variousDimensions_allWork() {
        int[] dims = {32, 64, 128, 256, 384, 512, 768, 1024, 1536, 2048};
        for (int dim : dims) {
            float[] query = createRandomVector(dim, 42);
            float[] database = createRandomVector(dim * 5, 99);

            float[] results = kernel.compute(query, database, 5, dim);

            assertEquals(5, results.length, "Failed for dims=" + dim);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // CPU equivalence
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    void compute_matchesManualDotProduct() {
        int dims = 64;
        float[] query = createRandomVector(dims, 42);
        float[] database = createRandomVector(dims * 3, 99);

        float[] results = kernel.compute(query, database, 3, dims);

        for (int i = 0; i < 3; i++) {
            float expected = scalarDotProduct(query, database, i * dims, dims);
            assertEquals(expected, results[i], Math.abs(expected) * 1e-5f + 1e-6f,
                    "Mismatch at vector " + i);
        }
    }

    @Test
    void compute_highDimensional_matchesScalar() {
        int dims = 384;
        int n = 50;
        float[] query = createRandomVector(dims, 42);
        float[] database = createRandomVector(n * dims, 99);

        float[] results = kernel.compute(query, database, n, dims);

        assertEquals(n, results.length);
        for (int i = 0; i < n; i++) {
            float expected = scalarDotProduct(query, database, i * dims, dims);
            assertEquals(expected, results[i], Math.abs(expected) * 1e-5f + 1e-6f,
                    "Mismatch at vector " + i);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Interface contract
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    void name_returnsDotProduct() {
        assertEquals("dot-product", kernel.name());
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
                () -> kernel.compute(new float[32], new float[32], 1, 32));
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Fallback transparency
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    void defaultConstructor_fallsBackGracefully() {
        // Default constructor should not throw even without GPU
        try (var defaultKernel = new CudaDotProductKernel()) {
            float[] query = createRandomVector(32, 42);
            float[] database = createRandomVector(32, 99);

            float[] results = defaultKernel.compute(query, database, 1, 32);
            assertEquals(1, results.length);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Large batch
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    void compute_largeBatch_correctResults() {
        int dims = 128;
        int n = 1000;
        float[] query = createRandomVector(dims, 42);
        float[] database = createRandomVector(n * dims, 99);

        float[] results = kernel.compute(query, database, n, dims);

        assertEquals(n, results.length);
        // Spot-check a few
        for (int i = 0; i < 10; i++) {
            float expected = scalarDotProduct(query, database, i * dims, dims);
            assertEquals(expected, results[i], Math.abs(expected) * 1e-5f + 1e-6f);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────

    private static float[] createUniformVector(int dims, float value) {
        float[] v = new float[dims];
        java.util.Arrays.fill(v, value);
        return v;
    }

    private static float[] createRandomVector(int dims, long seed) {
        java.util.Random rng = new java.util.Random(seed);
        float[] v = new float[dims];
        for (int i = 0; i < dims; i++) {
            v[i] = rng.nextFloat() - 0.5f;
        }
        return v;
    }

    private static float scalarDotProduct(float[] query, float[] database, int offset, int dims) {
        float sum = 0;
        for (int i = 0; i < dims; i++) {
            sum += query[i] * database[offset + i];
        }
        return sum;
    }
}
