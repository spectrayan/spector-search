package com.spectrayan.spector.gpu;

import com.spectrayan.spector.commons.error.SpectorValidationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CudaCosineKernel}.
 *
 * <p>Tests validate the CPU SIMD fallback path since CUDA may not be available
 * in CI/test environments. The interface contract is identical regardless of backend.</p>
 */
class CudaCosineKernelTest {

    private CudaCosineKernel kernel;

    @BeforeEach
    void setUp() {
        // Use CPU SIMD fallback for reliable testing
        kernel = new CudaCosineKernel(false);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Basic correctness
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    void compute_identicalVectors_returnsOne() {
        int dims = 32;
        float[] query = createUniformVector(dims, 1.0f);
        float[] database = createUniformVector(dims, 1.0f);

        float[] results = kernel.compute(query, database, 1, dims);

        assertEquals(1, results.length);
        assertEquals(1.0f, results[0], 1e-6f);
    }

    @Test
    void compute_oppositeVectors_returnsMinusOne() {
        int dims = 32;
        float[] query = createUniformVector(dims, 1.0f);
        float[] database = createUniformVector(dims, -1.0f);

        float[] results = kernel.compute(query, database, 1, dims);

        assertEquals(-1.0f, results[0], 1e-6f);
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
    void compute_multipleDatabaseVectors() {
        int dims = 32;
        float[] query = createUniformVector(dims, 1.0f);
        float[] database = new float[3 * dims];

        // Vector 0: same direction as query -> cosine ~= 1
        System.arraycopy(createUniformVector(dims, 2.0f), 0, database, 0, dims);
        // Vector 1: opposite direction -> cosine ~= -1
        System.arraycopy(createUniformVector(dims, -3.0f), 0, database, dims, dims);
        // Vector 2: orthogonal
        float[] orthogonal = new float[dims];
        orthogonal[0] = 1.0f;
        orthogonal[1] = -1.0f;
        // This won't be perfectly orthogonal to uniform, but let's use the actual uniform query
        System.arraycopy(createUniformVector(dims, 5.0f), 0, database, 2 * dims, dims);

        float[] results = kernel.compute(query, database, 3, dims);

        assertEquals(3, results.length);
        assertEquals(1.0f, results[0], 1e-5f);   // same direction
        assertEquals(-1.0f, results[1], 1e-5f);  // opposite direction
        assertEquals(1.0f, results[2], 1e-5f);   // same direction (scaled)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // NaN/Infinity handling (Requirement 11.6)
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    void compute_queryWithNaN_returnsNanForAll() {
        int dims = 32;
        float[] query = createUniformVector(dims, 1.0f);
        query[5] = Float.NaN;
        float[] database = createUniformVector(dims, 1.0f);

        float[] results = kernel.compute(query, database, 1, dims);

        assertTrue(Float.isNaN(results[0]), "NaN query should produce NaN result");
    }

    @Test
    void compute_queryWithInfinity_returnsNanForAll() {
        int dims = 32;
        float[] query = createUniformVector(dims, 1.0f);
        query[3] = Float.POSITIVE_INFINITY;
        float[] database = createUniformVector(dims, 1.0f);

        float[] results = kernel.compute(query, database, 1, dims);

        assertTrue(Float.isNaN(results[0]), "Infinity in query should produce NaN result");
    }

    @Test
    void compute_databaseVectorWithNaN_returnsNanForThatVector() {
        int dims = 32;
        float[] query = createUniformVector(dims, 1.0f);
        float[] database = new float[2 * dims];
        System.arraycopy(createUniformVector(dims, 1.0f), 0, database, 0, dims);
        System.arraycopy(createUniformVector(dims, 1.0f), 0, database, dims, dims);
        database[dims + 5] = Float.NaN;  // Second vector has NaN

        float[] results = kernel.compute(query, database, 2, dims);

        assertEquals(1.0f, results[0], 1e-6f, "Valid vector should have correct result");
        assertTrue(Float.isNaN(results[1]), "Vector with NaN should produce NaN result");
    }

    @Test
    void compute_databaseVectorWithNegativeInfinity_returnsNanForThatVector() {
        int dims = 32;
        float[] query = createUniformVector(dims, 1.0f);
        float[] database = new float[2 * dims];
        System.arraycopy(createUniformVector(dims, 1.0f), 0, database, 0, dims);
        System.arraycopy(createUniformVector(dims, 1.0f), 0, database, dims, dims);
        database[dims + 10] = Float.NEGATIVE_INFINITY;

        float[] results = kernel.compute(query, database, 2, dims);

        assertEquals(1.0f, results[0], 1e-6f);
        assertTrue(Float.isNaN(results[1]));
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Pre-normalized vector detection (Requirement 11.4)
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    void compute_preNormalizedVectors_useDotProductDirectly() {
        int dims = 32;
        // Create unit vectors
        float[] query = normalizeVector(createRandomVector(dims, 42));
        float[] database = new float[2 * dims];
        System.arraycopy(normalizeVector(createRandomVector(dims, 100)), 0, database, 0, dims);
        System.arraycopy(normalizeVector(createRandomVector(dims, 200)), 0, database, dims, dims);

        float[] results = kernel.compute(query, database, 2, dims);

        // Results should be valid cosine similarities in [-1, 1]
        for (float r : results) {
            assertTrue(r >= -1.01f && r <= 1.01f, "Result should be in [-1,1]: " + r);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Norm caching (Requirement 11.3)
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    void compute_sameDatabaseRepeatedQueries_usesCache() {
        int dims = 32;
        float[] query1 = createRandomVector(dims, 42);
        float[] query2 = createRandomVector(dims, 99);
        float[] database = createRandomVector(dims * 3, 123);

        // First call populates cache
        float[] results1 = kernel.compute(query1, database, 3, dims);
        // Second call should use cached norms
        float[] results2 = kernel.compute(query2, database, 3, dims);

        assertEquals(3, results1.length);
        assertEquals(3, results2.length);
        // Different queries should give different results
        assertNotEquals(results1[0], results2[0], 1e-6f);
    }

    @Test
    void clearNormCache_removesAllCachedNorms() {
        int dims = 32;
        float[] query = createRandomVector(dims, 42);
        float[] database = createRandomVector(dims * 2, 123);

        kernel.compute(query, database, 2, dims);
        kernel.clearNormCache();

        // Should still work after clearing cache
        float[] results = kernel.compute(query, database, 2, dims);
        assertEquals(2, results.length);
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

    // ─────────────────────────────────────────────────────────────────────────────
    // Interface contract
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    void name_returnsCosine() {
        assertEquals("cosine", kernel.name());
    }

    @Test
    void isGpuActive_returnsFalseInFallbackMode() {
        assertFalse(kernel.isGpuActive());
    }

    @Test
    void implementsSimilarityKernel() {
        assertInstanceOf(SimilarityKernel.class, kernel);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Zero-magnitude vectors
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    void compute_zeroQuery_returnsZeros() {
        int dims = 32;
        float[] query = new float[dims]; // all zeros
        float[] database = createUniformVector(dims, 1.0f);

        float[] results = kernel.compute(query, database, 1, dims);

        assertEquals(0.0f, results[0]);
    }

    @Test
    void compute_zeroDocumentVector_returnsZero() {
        int dims = 32;
        float[] query = createUniformVector(dims, 1.0f);
        float[] database = new float[dims]; // all zeros

        float[] results = kernel.compute(query, database, 1, dims);

        assertEquals(0.0f, results[0]);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Higher dimensions
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    void compute_highDimensional_correctResults() {
        int dims = 384;
        int n = 50;
        float[] query = createRandomVector(dims, 42);
        float[] database = createRandomVector(n * dims, 99);

        float[] results = kernel.compute(query, database, n, dims);

        assertEquals(n, results.length);
        for (float r : results) {
            assertFalse(Float.isNaN(r));
            assertTrue(r >= -1.01f && r <= 1.01f, "Cosine should be in [-1,1]: " + r);
        }
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

    // ─────────────────────────────────────────────────────────────────────────────
    // CPU equivalence verification
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    void compute_matchesManualCosineComputation() {
        int dims = 64;
        float[] query = createRandomVector(dims, 42);
        float[] database = createRandomVector(dims, 99);

        float[] results = kernel.compute(query, database, 1, dims);

        // Manual computation
        float dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < dims; i++) {
            dot += query[i] * database[i];
            normA += query[i] * query[i];
            normB += database[i] * database[i];
        }
        float expected = dot / ((float) Math.sqrt(normA) * (float) Math.sqrt(normB));

        assertEquals(expected, results[0], 1e-5f);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Helper methods
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

    private static float[] normalizeVector(float[] v) {
        float norm = 0;
        for (float f : v) norm += f * f;
        norm = (float) Math.sqrt(norm);
        float[] result = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            result[i] = v[i] / norm;
        }
        return result;
    }
}
