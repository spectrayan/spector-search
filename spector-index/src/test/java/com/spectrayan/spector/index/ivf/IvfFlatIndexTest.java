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
package com.spectrayan.spector.index.ivf;

import com.spectrayan.spector.commons.error.SpectorException;

import com.spectrayan.spector.commons.error.SpectorValidationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import com.spectrayan.spector.core.similarity.SimilarityFunction;
import com.spectrayan.spector.index.ScoredResult;

/**
 * Tests for {@link IvfFlatIndex} — IVF-Flat training, indexing, and search.
 */
class IvfFlatIndexTest {

    @Test
    void trainAndSearch_returnsResults() {
        int dims = 32;
        int n = 500;
        int numCells = 16;

        float[][] vectors = randomVectors(n, dims, 42);

        var index = new IvfFlatIndex(dims, SimilarityFunction.COSINE);
        index.train(vectors, numCells);
        assertTrue(index.isTrained());

        for (int i = 0; i < n; i++) {
            index.add("doc-" + i, i, vectors[i]);
        }
        assertEquals(n, index.size());

        ScoredResult[] results = index.search(vectors[0], 4, 5);
        assertNotNull(results);
        assertTrue(results.length > 0);
        assertTrue(results.length <= 5);
    }

    @Test
    void searchBeforeTraining_throws() {
        var index = new IvfFlatIndex(32, SimilarityFunction.COSINE);
        var ex = assertThrows(SpectorException.class,
                () -> index.search(new float[32], 5));
        assertTrue(ex.getMessage().contains("trained"));
    }

    @Test
    void addBeforeTraining_throws() {
        var index = new IvfFlatIndex(32, SimilarityFunction.COSINE);
        assertThrows(SpectorException.class,
                () -> index.add("doc-0", 0, new float[32]));
    }

    @Test
    void trainWithTooFewVectors_throws() {
        var index = new IvfFlatIndex(32, SimilarityFunction.COSINE);
        float[][] vectors = randomVectors(5, 32, 42);
        var ex = assertThrows(SpectorValidationException.class,
                () -> index.train(vectors, 10));
        assertTrue(ex.getMessage().contains("at least 10"));
    }

    @Test
    void trainWithCellsOutOfRange_throws() {
        var index = new IvfFlatIndex(32, SimilarityFunction.COSINE);
        float[][] vectors = randomVectors(100, 32, 42);

        assertThrows(SpectorValidationException.class,
                () -> index.train(vectors, 1)); // below MIN_CELLS

        var index2 = new IvfFlatIndex(32, SimilarityFunction.COSINE);
        assertThrows(SpectorValidationException.class,
                () -> index2.train(vectors, 65_537)); // above MAX_CELLS
    }

    @Test
    void emptyIndex_returnsEmpty() {
        int dims = 16;
        float[][] trainData = randomVectors(100, dims, 42);
        var index = new IvfFlatIndex(dims, SimilarityFunction.COSINE);
        index.train(trainData, 8);

        ScoredResult[] results = index.search(trainData[0], 4, 5);
        assertEquals(0, results.length);
    }

    @Test
    void exhaustiveSearch_matchesBruteForce() {
        int dims = 16;
        int n = 200;
        int numCells = 8;
        float[][] vectors = normalizedVectors(n, dims, 42);

        var index = new IvfFlatIndex(dims, SimilarityFunction.COSINE);
        index.train(vectors, numCells);

        for (int i = 0; i < n; i++) {
            index.add("doc-" + i, i, vectors[i]);
        }

        float[] query = vectors[0];

        // nprobe == numCells should give brute-force results
        ScoredResult[] ivfResults = index.search(query, numCells, 10);

        // Compute brute-force top-10
        ScoredResult[] bruteForce = bruteForceSearch(query, vectors, 10, SimilarityFunction.COSINE);

        // The rankings should be identical
        assertEquals(bruteForce.length, ivfResults.length);
        for (int i = 0; i < bruteForce.length; i++) {
            assertEquals(bruteForce[i].id(), ivfResults[i].id(),
                    "Ranking mismatch at position " + i);
        }
    }

    @Test
    void searchResults_areSortedByScore() {
        int dims = 32;
        int n = 300;
        float[][] vectors = randomVectors(n, dims, 42);

        var index = new IvfFlatIndex(dims, SimilarityFunction.COSINE);
        index.train(vectors, 16);

        for (int i = 0; i < n; i++) {
            index.add("doc-" + i, i, vectors[i]);
        }

        ScoredResult[] results = index.search(vectors[0], 8, 10);
        for (int i = 1; i < results.length; i++) {
            assertTrue(results[i - 1].score() >= results[i].score() - 1e-6f,
                    "Results should be sorted by score descending");
        }
    }

    @Test
    void euclideanDistance_works() {
        int dims = 16;
        int n = 200;
        float[][] vectors = randomVectors(n, dims, 42);

        var index = new IvfFlatIndex(dims, SimilarityFunction.EUCLIDEAN);
        index.train(vectors, 8);

        for (int i = 0; i < n; i++) {
            index.add("doc-" + i, i, vectors[i]);
        }

        ScoredResult[] results = index.search(vectors[0], 8, 5);
        assertNotNull(results);
        assertTrue(results.length > 0);
        // First result should be itself (or very close) with highest score
        assertEquals("doc-0", results[0].id());
    }

    @Test
    void selfSearch_findsExactMatch() {
        int dims = 16;
        int n = 100;
        float[][] vectors = normalizedVectors(n, dims, 42);

        var index = new IvfFlatIndex(dims, SimilarityFunction.COSINE);
        index.train(vectors, 4);

        for (int i = 0; i < n; i++) {
            index.add("doc-" + i, i, vectors[i]);
        }

        // With nprobe = numCells, searching for an indexed vector should find itself first
        ScoredResult[] results = index.search(vectors[5], 4, 1);
        assertEquals("doc-5", results[0].id());
    }

    @Test
    void invalidNprobe_throws() {
        int dims = 16;
        float[][] trainData = randomVectors(100, dims, 42);
        var index = new IvfFlatIndex(dims, SimilarityFunction.COSINE);
        index.train(trainData, 8);
        index.add("doc-0", 0, trainData[0]);

        assertThrows(SpectorValidationException.class,
                () -> index.search(trainData[0], 0, 5)); // nprobe < 1

        assertThrows(SpectorValidationException.class,
                () -> index.search(trainData[0], 9, 5)); // nprobe > numCells
    }

    @Test
    void numCells_isAccessible() {
        var index = new IvfFlatIndex(32, SimilarityFunction.COSINE);
        float[][] vectors = randomVectors(100, 32, 42);
        index.train(vectors, 10);
        assertEquals(10, index.numCells());
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

    private ScoredResult[] bruteForceSearch(float[] query, float[][] vectors, int k,
                                             SimilarityFunction simFn) {
        ScoredResult[] all = new ScoredResult[vectors.length];
        for (int i = 0; i < vectors.length; i++) {
            float score = simFn.compute(query, vectors[i]);
            if (!simFn.higherIsBetter()) {
                score = 1.0f / (1.0f + score);
            }
            all[i] = new ScoredResult("doc-" + i, i, score);
        }
        java.util.Arrays.sort(all); // descending by score
        return java.util.Arrays.copyOf(all, Math.min(k, all.length));
    }
}
