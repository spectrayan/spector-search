package com.spectrayan.spector.index;

import com.spectrayan.spector.core.ScalarQuantizer;
import com.spectrayan.spector.core.SimilarityFunction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link QuantizedHnswIndex} — quantized search with re-ranking.
 */
class QuantizedHnswIndexTest {

    @Test
    void basicSearch_returnsResults() {
        int dims = 32;
        java.util.Random rng = new java.util.Random(42);

        // Pre-generate vectors for calibration
        float[][] vectors = new float[50][dims];
        for (int i = 0; i < 50; i++) {
            vectors[i] = randomVector(rng, dims);
        }

        // Pre-calibrate so quantized path is used
        var sq = com.spectrayan.spector.core.ScalarQuantizer.calibrate(vectors, dims);
        var index = new QuantizedHnswIndex(dims, 100,
                SimilarityFunction.COSINE, HnswParams.DEFAULT, sq);

        for (int i = 0; i < 50; i++) {
            index.add("doc-" + i, i, vectors[i]);
        }

        float[] query = randomVector(rng, dims);
        ScoredResult[] results = index.search(query, 5);

        assertNotNull(results);
        assertTrue(results.length > 0, "Should return results");
        assertTrue(results.length <= 5, "Should return at most k results");

        // Scores should be in non-increasing order (cosine = higher is better)
        for (int i = 1; i < results.length; i++) {
            assertTrue(results[i - 1].score() >= results[i].score() - 1e-6f,
                    "Results should be sorted by score (best first), but index " + (i-1)
                            + " score=" + results[i-1].score() + " < index " + i
                            + " score=" + results[i].score());
        }
    }

    @Test
    void autoCalibration_triggersAtThreshold() {
        int dims = 16;
        var index = new QuantizedHnswIndex(dims, 200,
                SimilarityFunction.COSINE, HnswParams.DEFAULT);

        assertFalse(index.isCalibrated(), "Should not be calibrated initially");

        java.util.Random rng = new java.util.Random(99);
        // Insert enough vectors to trigger auto-calibration (buffer size = min(10000, capacity))
        for (int i = 0; i < 200; i++) {
            index.add("doc-" + i, i, randomVector(rng, dims));
        }

        assertTrue(index.isCalibrated(), "Should be auto-calibrated after filling buffer");
    }

    @Test
    void preCalibrated_worksImmediately() {
        int dims = 16;
        float[][] samples = new float[50][dims];
        java.util.Random rng = new java.util.Random(7);
        for (int i = 0; i < 50; i++) {
            for (int d = 0; d < dims; d++) {
                samples[i][d] = rng.nextFloat() - 0.5f;
            }
        }

        ScalarQuantizer sq = ScalarQuantizer.calibrate(samples, dims);
        var index = new QuantizedHnswIndex(dims, 100,
                SimilarityFunction.COSINE, HnswParams.DEFAULT, sq);

        assertTrue(index.isCalibrated(), "Should be calibrated from start");

        for (int i = 0; i < 30; i++) {
            index.add("doc-" + i, i, samples[i % 50]);
        }

        ScoredResult[] results = index.search(samples[0], 5);
        assertTrue(results.length > 0);
    }

    @Test
    void recallQuality_highForTypicalEmbeddings() {
        int dims = 128;
        int numDocs = 1000;
        java.util.Random rng = new java.util.Random(42);

        // Build quantized index
        var quantizedIndex = new QuantizedHnswIndex(dims, numDocs + 10,
                SimilarityFunction.COSINE, HnswParams.DEFAULT);

        // Build exact index for comparison
        var exactIndex = new HnswIndex(dims, numDocs + 10, SimilarityFunction.COSINE);

        float[][] vectors = new float[numDocs][dims];
        for (int i = 0; i < numDocs; i++) {
            vectors[i] = randomVector(rng, dims);
            quantizedIndex.add("doc-" + i, i, vectors[i]);
            exactIndex.add("doc-" + i, i, vectors[i]);
        }

        // Query and measure recall
        int k = 10;
        int queryCount = 20;
        int totalHits = 0;

        for (int q = 0; q < queryCount; q++) {
            float[] query = randomVector(rng, dims);
            ScoredResult[] quantizedResults = quantizedIndex.search(query, k);
            ScoredResult[] exactResults = exactIndex.search(query, k);

            // Count how many of the exact top-K appear in quantized results
            java.util.Set<String> exactIds = new java.util.HashSet<>();
            for (ScoredResult r : exactResults) exactIds.add(r.id());

            for (ScoredResult r : quantizedResults) {
                if (exactIds.contains(r.id())) totalHits++;
            }
        }

        double recall = (double) totalHits / (queryCount * k);
        assertTrue(recall >= 0.8, "Recall should be >= 80% but was " + recall);
    }

    @Test
    void emptyIndex_returnsEmptyResults() {
        var index = new QuantizedHnswIndex(32, 100,
                SimilarityFunction.COSINE, HnswParams.DEFAULT);
        ScoredResult[] results = index.search(new float[32], 5);
        assertEquals(0, results.length);
    }

    private float[] randomVector(java.util.Random rng, int dims) {
        float[] v = new float[dims];
        float norm = 0;
        for (int i = 0; i < dims; i++) {
            v[i] = rng.nextFloat() - 0.5f;
            norm += v[i] * v[i];
        }
        norm = (float) Math.sqrt(norm);
        for (int i = 0; i < dims; i++) v[i] /= norm;
        return v;
    }
}
