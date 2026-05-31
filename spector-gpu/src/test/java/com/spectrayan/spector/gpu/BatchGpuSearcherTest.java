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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BatchGpuSearcher}.
 *
 * <p>Tests validate batching configuration, sub-batch partitioning,
 * per-query error isolation, and top-K result extraction.</p>
 */
class BatchGpuSearcherTest {

    private static final long BUDGET_512MB = 512L * 1024 * 1024;
    private static final int DIMENSIONS = 32;
    private static final int NUM_VECTORS = 100;

    private GpuMemoryManager memoryManager;
    private SimilarityKernel stubKernel;
    private BatchGpuSearcher searcher;

    @BeforeEach
    void setUp() {
        memoryManager = new GpuMemoryManager(BUDGET_512MB, true);
        stubKernel = new StubDotProductKernel();
        searcher = new BatchGpuSearcher(stubKernel, memoryManager, Duration.ofMillis(10), 1024);
    }

    @AfterEach
    void tearDown() {
        if (searcher != null) searcher.close();
        if (memoryManager != null) memoryManager.close();
    }

    // ── Configuration Tests ─────────────────────────────────────────────────

    @Test
    void constructor_rejectsNullKernel() {
        assertThrows(SpectorValidationException.class, () ->
                new BatchGpuSearcher(null, memoryManager, Duration.ofMillis(10), 1024));
    }

    @Test
    void constructor_rejectsNullMemoryManager() {
        assertThrows(SpectorValidationException.class, () ->
                new BatchGpuSearcher(stubKernel, null, Duration.ofMillis(10), 1024));
    }

    @Test
    void constructor_rejectsWindowBelowMinimum() {
        assertThrows(SpectorValidationException.class, () ->
                new BatchGpuSearcher(stubKernel, memoryManager, Duration.ofMillis(0), 1024));
    }

    @Test
    void constructor_rejectsWindowAboveMaximum() {
        assertThrows(SpectorValidationException.class, () ->
                new BatchGpuSearcher(stubKernel, memoryManager, Duration.ofMillis(101), 1024));
    }

    @Test
    void constructor_rejectsBatchSizeZero() {
        assertThrows(SpectorValidationException.class, () ->
                new BatchGpuSearcher(stubKernel, memoryManager, Duration.ofMillis(10), 0));
    }

    @Test
    void constructor_rejectsBatchSizeAboveMax() {
        assertThrows(SpectorValidationException.class, () ->
                new BatchGpuSearcher(stubKernel, memoryManager, Duration.ofMillis(10), 1025));
    }

    @Test
    void constructor_acceptsMinimumValidWindow() {
        try (var s = new BatchGpuSearcher(stubKernel, memoryManager, Duration.ofMillis(1), 1024)) {
            assertEquals(Duration.ofMillis(1), s.getBatchingWindow());
        }
    }

    @Test
    void constructor_acceptsMaximumValidWindow() {
        try (var s = new BatchGpuSearcher(stubKernel, memoryManager, Duration.ofMillis(100), 1024)) {
            assertEquals(Duration.ofMillis(100), s.getBatchingWindow());
        }
    }

    @Test
    void constructor_defaultConstructorUsesDefaults() {
        try (var s = new BatchGpuSearcher(stubKernel, memoryManager)) {
            assertEquals(Duration.ofMillis(10), s.getBatchingWindow());
            assertEquals(1024, s.getMaxBatchSize());
        }
    }

    // ── Search Tests ────────────────────────────────────────────────────────

    @Test
    void batchSearch_emptyQueriesReturnsEmptyMap() {
        float[] database = createDatabase(NUM_VECTORS, DIMENSIONS);
        Map<Integer, BatchQueryResult> results = searcher.batchSearch(
                List.of(), database, NUM_VECTORS, DIMENSIONS, 10);
        assertTrue(results.isEmpty());
    }

    @Test
    void batchSearch_singleQueryReturnsCorrectTopK() {
        float[] database = createDatabase(NUM_VECTORS, DIMENSIONS);
        float[] query = createQuery(DIMENSIONS, 1.0f);

        Map<Integer, BatchQueryResult> results = searcher.batchSearch(
                List.of(query), database, NUM_VECTORS, DIMENSIONS, 5);

        assertEquals(1, results.size());
        assertTrue(results.containsKey(0));
        BatchQueryResult result = results.get(0);
        assertTrue(result.isSuccess());
        assertEquals(5, result.results().size());
    }

    @Test
    void batchSearch_multipleQueriesReturnIndividualResults() {
        float[] database = createDatabase(NUM_VECTORS, DIMENSIONS);
        List<float[]> queries = List.of(
                createQuery(DIMENSIONS, 1.0f),
                createQuery(DIMENSIONS, 2.0f),
                createQuery(DIMENSIONS, 3.0f)
        );

        Map<Integer, BatchQueryResult> results = searcher.batchSearch(
                queries, database, NUM_VECTORS, DIMENSIONS, 10);

        assertEquals(3, results.size());
        for (int i = 0; i < 3; i++) {
            assertTrue(results.containsKey(i));
            assertTrue(results.get(i).isSuccess());
            assertEquals(10, results.get(i).results().size());
        }
    }

    @Test
    void batchSearch_topKLargerThanDatabaseReturnsAllVectors() {
        int smallDb = 5;
        float[] database = createDatabase(smallDb, DIMENSIONS);
        float[] query = createQuery(DIMENSIONS, 1.0f);

        Map<Integer, BatchQueryResult> results = searcher.batchSearch(
                List.of(query), database, smallDb, DIMENSIONS, 100);

        BatchQueryResult result = results.get(0);
        assertTrue(result.isSuccess());
        assertEquals(smallDb, result.results().size());
    }

    @Test
    void batchSearch_resultsOrderedByDescendingScore() {
        float[] database = createDatabase(NUM_VECTORS, DIMENSIONS);
        float[] query = createQuery(DIMENSIONS, 1.0f);

        Map<Integer, BatchQueryResult> results = searcher.batchSearch(
                List.of(query), database, NUM_VECTORS, DIMENSIONS, 10);

        List<BatchSearchResult> topK = results.get(0).results();
        for (int i = 0; i < topK.size() - 1; i++) {
            assertTrue(topK.get(i).score() >= topK.get(i + 1).score(),
                    "Results should be in descending score order");
        }
    }

    // ── Error Isolation Tests ───────────────────────────────────────────────

    @Test
    void batchSearch_nanQueryIsolatedFromOtherQueries() {
        float[] database = createDatabase(NUM_VECTORS, DIMENSIONS);
        float[] validQuery = createQuery(DIMENSIONS, 1.0f);
        float[] nanQuery = new float[DIMENSIONS];
        nanQuery[0] = Float.NaN;

        List<float[]> queries = List.of(validQuery, nanQuery, validQuery);

        Map<Integer, BatchQueryResult> results = searcher.batchSearch(
                queries, database, NUM_VECTORS, DIMENSIONS, 5);

        assertEquals(3, results.size());
        assertTrue(results.get(0).isSuccess(), "Valid query 0 should succeed");
        assertFalse(results.get(1).isSuccess(), "NaN query should fail");
        assertNotNull(results.get(1).error());
        assertTrue(results.get(2).isSuccess(), "Valid query 2 should succeed");
    }

    @Test
    void batchSearch_infinityQueryIsolatedFromOtherQueries() {
        float[] database = createDatabase(NUM_VECTORS, DIMENSIONS);
        float[] validQuery = createQuery(DIMENSIONS, 1.0f);
        float[] infQuery = new float[DIMENSIONS];
        infQuery[0] = Float.POSITIVE_INFINITY;

        List<float[]> queries = List.of(validQuery, infQuery);

        Map<Integer, BatchQueryResult> results = searcher.batchSearch(
                queries, database, NUM_VECTORS, DIMENSIONS, 5);

        assertTrue(results.get(0).isSuccess());
        assertFalse(results.get(1).isSuccess());
        assertTrue(results.get(1).error().contains("infinity"));
    }

    @Test
    void batchSearch_nullQueryIsolated() {
        float[] database = createDatabase(NUM_VECTORS, DIMENSIONS);
        float[] validQuery = createQuery(DIMENSIONS, 1.0f);

        List<float[]> queries = new ArrayList<>();
        queries.add(validQuery);
        queries.add(null);
        queries.add(validQuery);

        Map<Integer, BatchQueryResult> results = searcher.batchSearch(
                queries, database, NUM_VECTORS, DIMENSIONS, 5);

        assertTrue(results.get(0).isSuccess());
        assertFalse(results.get(1).isSuccess());
        assertTrue(results.get(2).isSuccess());
    }

    // ── Batch Size Limit Tests ──────────────────────────────────────────────

    @Test
    void batchSearch_clampsToMaxBatchSize() {
        int maxBatch = 4;
        try (var smallBatchSearcher = new BatchGpuSearcher(
                stubKernel, memoryManager, Duration.ofMillis(10), maxBatch)) {

            float[] database = createDatabase(NUM_VECTORS, DIMENSIONS);
            List<float[]> queries = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                queries.add(createQuery(DIMENSIONS, (float) i));
            }

            Map<Integer, BatchQueryResult> results = smallBatchSearcher.batchSearch(
                    queries, database, NUM_VECTORS, DIMENSIONS, 5);

            // Only processes up to maxBatchSize queries
            assertEquals(maxBatch, results.size());
        }
    }

    // ── Closed State Tests ──────────────────────────────────────────────────

    @Test
    void batchSearch_throwsWhenClosed() {
        searcher.close();
        float[] database = createDatabase(NUM_VECTORS, DIMENSIONS);
        float[] query = createQuery(DIMENSIONS, 1.0f);

        assertThrows(SpectorException.class, () ->
                searcher.batchSearch(List.of(query), database, NUM_VECTORS, DIMENSIONS, 5));
    }

    // ── Input Validation Tests ──────────────────────────────────────────────

    @Test
    void batchSearch_rejectsNullQueries() {
        float[] database = createDatabase(NUM_VECTORS, DIMENSIONS);
        assertThrows(SpectorValidationException.class, () ->
                searcher.batchSearch(null, database, NUM_VECTORS, DIMENSIONS, 5));
    }

    @Test
    void batchSearch_rejectsNullDatabase() {
        float[] query = createQuery(DIMENSIONS, 1.0f);
        assertThrows(SpectorValidationException.class, () ->
                searcher.batchSearch(List.of(query), null, NUM_VECTORS, DIMENSIONS, 5));
    }

    @Test
    void batchSearch_rejectsInvalidTopK() {
        float[] database = createDatabase(NUM_VECTORS, DIMENSIONS);
        float[] query = createQuery(DIMENSIONS, 1.0f);

        assertThrows(SpectorValidationException.class, () ->
                searcher.batchSearch(List.of(query), database, NUM_VECTORS, DIMENSIONS, 0));
        assertThrows(SpectorValidationException.class, () ->
                searcher.batchSearch(List.of(query), database, NUM_VECTORS, DIMENSIONS, 1001));
    }

    @Test
    void batchSearch_rejectsNegativeDimensions() {
        float[] database = createDatabase(NUM_VECTORS, DIMENSIONS);
        float[] query = createQuery(DIMENSIONS, 1.0f);
        assertThrows(SpectorValidationException.class, () ->
                searcher.batchSearch(List.of(query), database, NUM_VECTORS, -1, 5));
    }

    // ── Memory Partitioning Tests ───────────────────────────────────────────

    @Test
    void batchSearch_handlesLargeBatchesWithMemoryConstraint() {
        // Use a small budget so partitioning kicks in
        try (var smallMem = new GpuMemoryManager(256L * 1024 * 1024, true)) {
            try (var constrained = new BatchGpuSearcher(
                    stubKernel, smallMem, Duration.ofMillis(10), 1024)) {

                float[] database = createDatabase(NUM_VECTORS, DIMENSIONS);
                List<float[]> queries = new ArrayList<>();
                for (int i = 0; i < 50; i++) {
                    queries.add(createQuery(DIMENSIONS, (float) i));
                }

                Map<Integer, BatchQueryResult> results = constrained.batchSearch(
                        queries, database, NUM_VECTORS, DIMENSIONS, 5);

                // All queries should get results regardless of partitioning
                assertEquals(50, results.size());
                for (int i = 0; i < 50; i++) {
                    assertTrue(results.get(i).isSuccess());
                }
            }
        }
    }

    // ── Helper Methods ──────────────────────────────────────────────────────

    private float[] createDatabase(int numVectors, int dimensions) {
        float[] database = new float[numVectors * dimensions];
        for (int i = 0; i < database.length; i++) {
            database[i] = (float) (i % dimensions) / dimensions;
        }
        return database;
    }

    private float[] createQuery(int dimensions, float baseValue) {
        float[] query = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            query[i] = baseValue * (i + 1) / dimensions;
        }
        return query;
    }

    /**
     * Stub kernel that computes simple dot-product on CPU for testing purposes.
     */
    private static class StubDotProductKernel implements SimilarityKernel {

        @Override
        public String name() {
            return "stub-dot-product";
        }

        @Override
        public float[] compute(float[] query, float[] database, int numVectors, int dimensions) {
            float[] results = new float[numVectors];
            for (int v = 0; v < numVectors; v++) {
                float sum = 0;
                int offset = v * dimensions;
                for (int d = 0; d < dimensions; d++) {
                    sum += query[d] * database[offset + d];
                }
                results[v] = sum;
            }
            return results;
        }

        @Override
        public boolean isGpuActive() {
            return false;
        }
    }
}
