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
package com.spectrayan.spector.index;


import com.spectrayan.spector.config.HnswParams;
import com.spectrayan.spector.core.quantization.QuantizationType;
import com.spectrayan.spector.core.similarity.SimilarityFunction;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end recall and correctness tests for SVASQ-quantized HNSW index.
 *
 * <p>These tests validate the full pipeline:
 * auto-calibration → retroactive encoding → asymmetric distance traversal → rescore.</p>
 */
class SvasqHnswIndexTest {

    private static final int    DIMS        = 128;
    private static final int    NUM_DOCS    = 1000;
    private static final int    K           = 10;
    private static final int    QUERY_COUNT = 20;
    private static final double MIN_RECALL  = 0.75;  // ≥ 75% recall@10 vs exact HNSW

    // ── Smoke tests ───────────────────────────────────────────────────────────

    @Test
    void svasq_factory_creates_correct_type() {
        var index = QuantizedHnswIndex.svasq(64, 100, SimilarityFunction.COSINE,
                HnswParams.DEFAULT, 1);
        assertEquals(QuantizationType.SVASQ, index.quantizationType());
        assertFalse(index.isCalibrated(), "Should not be calibrated before any insertions");
    }

    @Test
    void svasq_emptyIndex_returnsEmpty() {
        var index = QuantizedHnswIndex.svasq(32, 100, SimilarityFunction.EUCLIDEAN,
                HnswParams.DEFAULT, 1);
        ScoredResult[] results = index.search(new float[32], 5);
        assertEquals(0, results.length);
    }

    @Test
    void svasq_autoCalibrates_after_threshold() {
        int dims = 32;
        var index = QuantizedHnswIndex.svasq(dims, 1000, SimilarityFunction.COSINE,
                HnswParams.DEFAULT, 1);

        assertFalse(index.isCalibrated());

        Random rng = new Random(42L);
        for (int i = 0; i < 1000; i++) {
            index.add("doc-" + i, i, randomUnit(rng, dims));
        }

        assertTrue(index.isCalibrated(), "SVASQ should auto-calibrate after filling buffer");
    }

    @Test
    void svasq_basicSearch_returnsAndSorts() {
        int dims = 64;
        // Set capacity == numDocs so calibrationBuffer fills exactly when all docs are inserted
        int numDocs = 150;
        var index = QuantizedHnswIndex.svasq(dims, numDocs, SimilarityFunction.COSINE,
                HnswParams.DEFAULT, 1);

        Random rng = new Random(1L);
        for (int i = 0; i < numDocs; i++) {
            index.add("doc-" + i, i, randomUnit(rng, dims));
        }

        assertTrue(index.isCalibrated(), "Should be calibrated after filling capacity");

        float[] query = randomUnit(rng, dims);
        ScoredResult[] results = index.search(query, 5);

        assertNotNull(results);
        assertTrue(results.length > 0, "Should return results");
        assertTrue(results.length <= 5);

        // Cosine: higher is better → descending score order
        for (int i = 1; i < results.length; i++) {
            assertTrue(results[i - 1].score() >= results[i].score() - 1e-5f,
                    "Results must be sorted descending: " + results[i-1].score()
                    + " vs " + results[i].score());
        }
    }


    // ── Recall tests ──────────────────────────────────────────────────────────

    @Test
    void svasq_recall_cosine_noRescore() {
        double recall = measureRecall(SimilarityFunction.COSINE, /*oversample=*/1);
        assertTrue(recall >= MIN_RECALL,
                "SVASQ recall@" + K + " (no rescore) should be ≥ " + MIN_RECALL
                + " but was " + recall);
    }

    @Test
    void svasq_recall_cosine_withRescore3x() {
        double recall = measureRecall(SimilarityFunction.COSINE, /*oversample=*/3);
        // With 3× rescore, recall should be significantly better
        assertTrue(recall >= 0.85,
                "SVASQ recall@" + K + " (3× rescore) should be ≥ 0.85 but was " + recall);
    }

    @Test
    void svasq_recall_euclidean_noRescore() {
        double recall = measureRecall(SimilarityFunction.EUCLIDEAN, /*oversample=*/1);
        assertTrue(recall >= MIN_RECALL,
                "SVASQ L2 recall@" + K + " should be ≥ " + MIN_RECALL
                + " but was " + recall);
    }

    @Test
    void svasq_recall_euclidean_withRescore() {
        double recall = measureRecall(SimilarityFunction.EUCLIDEAN, /*oversample=*/3);
        assertTrue(recall >= 0.85,
                "SVASQ L2 recall@" + K + " (3× rescore) should be ≥ 0.85 but was " + recall);
    }

    // ── Correctness: same ID never appears twice ───────────────────────────────

    @Test
    void svasq_noDuplicates_inResults() {
        int dims = 64;
        var index = QuantizedHnswIndex.svasq(dims, 200, SimilarityFunction.COSINE,
                HnswParams.DEFAULT, 3);

        Random rng = new Random(2L);
        for (int i = 0; i < 100; i++) {
            index.add("doc-" + i, i, randomUnit(rng, dims));
        }

        float[] query = randomUnit(rng, dims);
        ScoredResult[] results = index.search(query, 10);

        Set<String> seen = new HashSet<>();
        for (ScoredResult r : results) {
            assertTrue(seen.add(r.id()), "Duplicate id in results: " + r.id());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private double measureRecall(SimilarityFunction fn, int oversample) {
        Random rng = new Random(42L);
        HnswParams params = new HnswParams(16, 128, 64);

        // SVASQ index
        var svasqIndex = QuantizedHnswIndex.svasq(DIMS, NUM_DOCS + 10, fn, params, oversample);

        // Exact HNSW for ground truth
        var exactIndex = new HnswIndex(DIMS, NUM_DOCS + 10, fn);

        float[][] vectors = new float[NUM_DOCS][DIMS];
        for (int i = 0; i < NUM_DOCS; i++) {
            vectors[i] = randomUnit(rng, DIMS);
            svasqIndex.add("doc-" + i, i, vectors[i]);
            exactIndex.add("doc-" + i, i, vectors[i]);
        }

        int totalHits = 0;
        for (int q = 0; q < QUERY_COUNT; q++) {
            float[] query = randomUnit(rng, DIMS);
            ScoredResult[] svasqResults  = svasqIndex.search(query, K);
            ScoredResult[] exactResults = exactIndex.search(query, K);

            Set<String> exactIds = new HashSet<>();
            for (ScoredResult r : exactResults) exactIds.add(r.id());

            for (ScoredResult r : svasqResults) {
                if (exactIds.contains(r.id())) totalHits++;
            }
        }

        return (double) totalHits / ((double) QUERY_COUNT * K);
    }

    /** Returns a random L2-normalized float vector. */
    private static float[] randomUnit(Random rng, int dims) {
        float[] v = new float[dims];
        double norm = 0;
        for (int i = 0; i < dims; i++) {
            v[i] = (float) rng.nextGaussian();
            norm += (double) v[i] * v[i];
        }
        float scale = (float) (1.0 / Math.sqrt(norm));
        for (int i = 0; i < dims; i++) v[i] *= scale;
        return v;
    }
}
