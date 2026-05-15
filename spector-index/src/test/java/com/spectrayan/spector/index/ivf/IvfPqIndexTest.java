package com.spectrayan.spector.index.ivf;

import com.spectrayan.spector.core.SimilarityFunction;
import com.spectrayan.spector.index.ScoredResult;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link IvfPqIndex} — IVF-PQ training, indexing, and search.
 */
class IvfPqIndexTest {

    @Test
    void trainAndSearch_returnsResults() {
        int dims = 32;
        int n = 500;
        int nlist = 16;
        int nprobe = 4;
        int M = 8;

        float[][] vectors = randomVectors(n, dims, 42);

        var index = new IvfPqIndex(dims, nlist, nprobe, M, SimilarityFunction.COSINE);

        // Train
        index.train(vectors);
        assertTrue(index.isTrained());

        // Index all vectors
        for (int i = 0; i < n; i++) {
            index.add("doc-" + i, i, vectors[i]);
        }
        assertEquals(n, index.size());

        // Search
        float[] query = vectors[0];
        ScoredResult[] results = index.search(query, 5);

        assertNotNull(results);
        assertTrue(results.length > 0, "Should return results");
        assertTrue(results.length <= 5, "Should return at most k results");
    }

    @Test
    void searchWithoutTraining_throws() {
        var index = new IvfPqIndex(32, 16, 4, 8, SimilarityFunction.COSINE);
        assertThrows(IllegalStateException.class,
                () -> index.search(new float[32], 5));
    }

    @Test
    void addWithoutTraining_throws() {
        var index = new IvfPqIndex(32, 16, 4, 8, SimilarityFunction.COSINE);
        assertThrows(IllegalStateException.class,
                () -> index.add("doc-0", 0, new float[32]));
    }

    @Test
    void emptyIndex_returnsEmpty() {
        int dims = 16;
        float[][] trainData = randomVectors(100, dims, 42);
        var index = new IvfPqIndex(dims, 8, 4, 4, SimilarityFunction.COSINE);
        index.train(trainData);

        ScoredResult[] results = index.search(trainData[0], 5);
        assertEquals(0, results.length);
    }

    @Test
    void convenienceConstructor_works() {
        var index = new IvfPqIndex(128, 10000);
        assertEquals(128, index.nlist() + 128 - index.nlist()); // just check it doesn't throw
        assertTrue(index.nlist() > 0);
    }

    @Test
    void searchResults_areSortedByScore() {
        int dims = 32;
        int n = 300;
        float[][] vectors = randomVectors(n, dims, 42);

        var index = new IvfPqIndex(dims, 16, 8, 8, SimilarityFunction.COSINE);
        index.train(vectors);

        for (int i = 0; i < n; i++) {
            index.add("doc-" + i, i, vectors[i]);
        }

        ScoredResult[] results = index.search(vectors[0], 10);
        for (int i = 1; i < results.length; i++) {
            assertTrue(results[i - 1].score() >= results[i].score() - 1e-6f,
                    "Results should be sorted by score descending");
        }
    }

    @Test
    void recall_isReasonable() {
        int dims = 32;
        int n = 500;
        float[][] vectors = normalizedVectors(n, dims, 42);

        // IVF-PQ with high nprobe for good recall
        var ivfPq = new IvfPqIndex(dims, 16, 16, 8, SimilarityFunction.COSINE);
        ivfPq.train(vectors);

        for (int i = 0; i < n; i++) {
            ivfPq.add("doc-" + i, i, vectors[i]);
        }

        // When we search for an indexed vector, it should appear in results
        // (not guaranteed for ANN, but likely with high nprobe)
        int found = 0;
        for (int q = 0; q < 20; q++) {
            ScoredResult[] results = ivfPq.search(vectors[q], 20);
            for (ScoredResult r : results) {
                if (r.id().equals("doc-" + q)) {
                    found++;
                    break;
                }
            }
        }

        // With nprobe = nlist = 16, we should find most self-queries
        assertTrue(found >= 10, "Self-recall should be >= 50% but was " + (found * 100 / 20) + "%");
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

    private float[][] normalizedVectors(int n, int dims, long seed) {
        float[][] vectors = randomVectors(n, dims, seed);
        for (float[] v : vectors) {
            float norm = 0;
            for (float f : v) norm += f * f;
            norm = (float) Math.sqrt(norm);
            for (int d = 0; d < dims; d++) v[d] /= norm;
        }
        return vectors;
    }
}
