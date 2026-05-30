package com.spectrayan.spector.index.pq;

import com.spectrayan.spector.commons.error.SpectorValidationException;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ParallelPqTrainer}.
 */
class ParallelPqTrainerTest {

    @Test
    void train_producesCorrectCodebookShape() {
        int dims = 32;
        int M = 8;
        int numCentroids = 256;
        float[][] vectors = randomVectors(500, dims, 42);

        ParallelPqTrainer trainer = new ParallelPqTrainer();
        float[][][] codebooks = trainer.train(vectors, M, numCentroids);

        assertEquals(M, codebooks.length, "Should have M sub-codebooks");
        for (int m = 0; m < M; m++) {
            assertEquals(numCentroids, codebooks[m].length,
                    "Sub-codebook " + m + " should have 256 centroids");
            for (int k = 0; k < numCentroids; k++) {
                assertEquals(dims / M, codebooks[m][k].length,
                        "Centroid [" + m + "][" + k + "] should have dimension D/M");
            }
        }
    }

    @Test
    void train_withFewerSamplesThanCentroids_padsCodebook() {
        int dims = 16;
        int M = 4;
        int numCentroids = 256;
        float[][] vectors = randomVectors(100, dims, 42);

        ParallelPqTrainer trainer = new ParallelPqTrainer();
        float[][][] codebooks = trainer.train(vectors, M, numCentroids);

        // Shape should still be [M][256][D/M]
        assertEquals(M, codebooks.length);
        for (int m = 0; m < M; m++) {
            assertEquals(numCentroids, codebooks[m].length);
            assertEquals(dims / M, codebooks[m][0].length);
        }
    }

    @Test
    void train_producesNonZeroCentroids() {
        int dims = 16;
        int M = 4;
        float[][] vectors = randomVectors(500, dims, 42);

        ParallelPqTrainer trainer = new ParallelPqTrainer();
        float[][][] codebooks = trainer.train(vectors, M, 256);

        // At least some centroids should be non-zero
        boolean hasNonZero = false;
        for (int m = 0; m < M && !hasNonZero; m++) {
            for (int k = 0; k < 256 && !hasNonZero; k++) {
                for (int d = 0; d < dims / M; d++) {
                    if (codebooks[m][k][d] != 0f) {
                        hasNonZero = true;
                        break;
                    }
                }
            }
        }
        assertTrue(hasNonZero, "Codebooks should contain non-zero centroids");
    }

    @Test
    void train_withCustomIterations() {
        int dims = 16;
        int M = 4;
        float[][] vectors = randomVectors(300, dims, 42);

        ParallelPqTrainer trainer = new ParallelPqTrainer(10, 42L);
        float[][][] codebooks = trainer.train(vectors, M, 256, 5);

        // Should still produce valid shape
        assertEquals(M, codebooks.length);
        assertEquals(256, codebooks[0].length);
        assertEquals(dims / M, codebooks[0][0].length);
    }

    @Test
    void train_throwsOnNullVectors() {
        ParallelPqTrainer trainer = new ParallelPqTrainer();
        assertThrows(SpectorValidationException.class,
                () -> trainer.train(null, 4, 256));
    }

    @Test
    void train_throwsOnEmptyVectors() {
        ParallelPqTrainer trainer = new ParallelPqTrainer();
        assertThrows(SpectorValidationException.class,
                () -> trainer.train(new float[0][], 4, 256));
    }

    @Test
    void train_throwsOnIndivisibleDimensions() {
        float[][] vectors = randomVectors(100, 15, 42);
        ParallelPqTrainer trainer = new ParallelPqTrainer();
        assertThrows(SpectorValidationException.class,
                () -> trainer.train(vectors, 4, 256));
    }

    @Test
    void train_throwsOnInvalidNumCentroids() {
        float[][] vectors = randomVectors(100, 16, 42);
        ParallelPqTrainer trainer = new ParallelPqTrainer();
        assertThrows(SpectorValidationException.class,
                () -> trainer.train(vectors, 4, 0));
        assertThrows(SpectorValidationException.class,
                () -> trainer.train(vectors, 4, 257));
    }

    @Test
    void train_throwsOnInvalidNumSubspaces() {
        float[][] vectors = randomVectors(100, 16, 42);
        ParallelPqTrainer trainer = new ParallelPqTrainer();
        assertThrows(SpectorValidationException.class,
                () -> trainer.train(vectors, 0, 256));
    }

    @Test
    void isSimdAccelerated_returnsBoolean() {
        // Should not throw; just verifying the method is callable
        boolean result = ParallelPqTrainer.isSimdAccelerated();
        // On most modern machines this should be true
        assertNotNull(Boolean.valueOf(result));
    }

    @Test
    void squaredL2_matchesScalar() {
        float[] a = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f};
        float[] b = {8.0f, 7.0f, 6.0f, 5.0f, 4.0f, 3.0f, 2.0f, 1.0f};

        float simdResult = ParallelPqTrainer.squaredL2(a, 0, b, 0, a.length);
        float scalarResult = ParallelPqTrainer.squaredL2Scalar(a, 0, b, 0, a.length);

        assertEquals(scalarResult, simdResult, 1e-4f,
                "SIMD and scalar L2 should produce same result");
    }

    @Test
    void squaredL2_withOffset() {
        float[] a = {0.0f, 0.0f, 1.0f, 2.0f, 3.0f};
        float[] b = {0.0f, 0.0f, 4.0f, 5.0f, 6.0f};

        float result = ParallelPqTrainer.squaredL2(a, 2, b, 2, 3);
        // (1-4)^2 + (2-5)^2 + (3-6)^2 = 9 + 9 + 9 = 27
        assertEquals(27.0f, result, 1e-4f);
    }

    @Test
    void train_deterministic_sameSeed() {
        int dims = 16;
        int M = 4;
        float[][] vectors = randomVectors(300, dims, 42);

        ParallelPqTrainer trainer1 = new ParallelPqTrainer(25, 123L);
        ParallelPqTrainer trainer2 = new ParallelPqTrainer(25, 123L);

        float[][][] codebooks1 = trainer1.train(vectors, M, 256);
        float[][][] codebooks2 = trainer2.train(vectors, M, 256);

        for (int m = 0; m < M; m++) {
            for (int k = 0; k < 256; k++) {
                assertArrayEquals(codebooks1[m][k], codebooks2[m][k], 1e-6f,
                        "Same seed should produce same codebooks");
            }
        }
    }

    // ─────────────── Helpers ───────────────

    private float[][] randomVectors(int n, int dims, long seed) {
        Random rng = new Random(seed);
        float[][] vectors = new float[n][dims];
        for (int i = 0; i < n; i++) {
            for (int d = 0; d < dims; d++) {
                vectors[i][d] = rng.nextFloat() - 0.5f;
            }
        }
        return vectors;
    }
}
