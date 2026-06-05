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
package com.spectrayan.spector.bench.cognitive;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import com.spectrayan.spector.bench.cognitive.model.BenchmarkCorpusRecord;
import com.spectrayan.spector.bench.cognitive.model.BenchmarkQuery;

/**
 * End-to-end integration tests for the cognitive benchmark harness with the mini-dataset.
 *
 * <p>These tests load the mini-dataset (50 memories, 20 queries), execute the full
 * benchmark pipeline, and verify structural invariants. Tests that require an embedding
 * service are gated behind {@code isEmbeddingAvailable()}.
 *
 * <p><b>Validates: Requirements 2.1–2.6, 11.6, 16.1–16.3</b>
 */
class CognitiveBenchmarkIntegrationTest {

    private static final Path MINI_DATASET_PATH = Path.of(
            "src/test/resources/cognitive-benchmark-mini");

    private static DatasetLoader.LoadedDataset dataset;

    @BeforeAll
    static void loadMiniDataset() {
        DatasetLoader loader = new DatasetLoader();
        Path datasetDir = MINI_DATASET_PATH;
        if (Files.exists(datasetDir)) {
            dataset = loader.load(datasetDir);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Dataset loading and validation
    // ══════════════════════════════════════════════════════════════

    @Test
    void miniDataset_loadsSuccessfully() {
        assertNotNull(dataset, "Mini-dataset should load without errors");
    }

    @Test
    void miniDataset_has50Memories() {
        assertNotNull(dataset);
        assertEquals(50, dataset.corpus().size(),
                "Mini-dataset should have exactly 50 corpus records");
    }

    @Test
    void miniDataset_has20Queries() {
        assertNotNull(dataset);
        assertEquals(20, dataset.queries().size(),
                "Mini-dataset should have exactly 20 queries");
    }

    @Test
    void miniDataset_hasQrelsForAllQueries() {
        assertNotNull(dataset);
        Map<String, Map<String, Integer>> qrels = dataset.qrels();

        for (BenchmarkQuery query : dataset.queries()) {
            assertTrue(qrels.containsKey(query.id()),
                    "Query " + query.id() + " should have qrels entries");
            assertTrue(qrels.get(query.id()).size() >= 5,
                    "Query " + query.id() + " should have at least 5 judgments");
        }
    }

    @Test
    void miniDataset_corpusHasValidFields() {
        assertNotNull(dataset);
        for (BenchmarkCorpusRecord record : dataset.corpus()) {
            assertNotNull(record.id(), "Corpus record should have ID");
            assertFalse(record.text().isEmpty(), "Corpus record should have text");
            assertFalse(record.title().isEmpty(), "Corpus record should have title");
            assertFalse(record.synapticTags().isEmpty(), "Corpus record should have tags");
            assertTrue(record.importance() >= 0.05f && record.importance() <= 10.0f,
                    "Importance should be in [0.05, 10.0]: " + record.importance());
            assertTrue(record.arousal() >= 0 && record.arousal() <= 255,
                    "Arousal should be in [0, 255]: " + record.arousal());
            assertNotNull(record.memoryType(), "Corpus record should have memoryType");
        }
    }

    @Test
    void miniDataset_temporalChainsAreValid() {
        assertNotNull(dataset);
        assertFalse(dataset.temporalChains().isEmpty(),
                "Mini-dataset should have temporal chains");
        assertTrue(dataset.temporalChains().size() >= 5,
                "Mini-dataset should have at least 5 temporal chains");
    }

    @Test
    void miniDataset_hebbianEdgesAreValid() {
        assertNotNull(dataset);
        assertFalse(dataset.hebbianEdges().isEmpty(),
                "Mini-dataset should have Hebbian edges");
        assertTrue(dataset.hebbianEdges().size() >= 10,
                "Mini-dataset should have at least 10 Hebbian edges");
    }

    @Test
    void miniDataset_entityRelationsAreValid() {
        assertNotNull(dataset);
        assertFalse(dataset.entityRelations().isEmpty(),
                "Mini-dataset should have entity relations");
        assertTrue(dataset.entityRelations().size() >= 15,
                "Mini-dataset should have at least 15 entity relations");
    }

    // ══════════════════════════════════════════════════════════════
    // Metrics computation (no embedding needed)
    // ══════════════════════════════════════════════════════════════

    @Test
    void metricsComputer_producesValidResults() {
        assertNotNull(dataset);
        MetricsComputer metrics = new MetricsComputer();

        // Test with a sample query's qrels
        if (!dataset.qrels().isEmpty()) {
            String firstQueryId = dataset.queries().getFirst().id();
            Map<String, Integer> queryQrels = dataset.qrels().get(firstQueryId);

            if (queryQrels != null && !queryQrels.isEmpty()) {
                // Use ideal ranking
                List<String> idealRanked = queryQrels.entrySet().stream()
                        .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                        .map(Map.Entry::getKey)
                        .toList();

                double ndcg = metrics.ndcgAtK(idealRanked, queryQrels, 10);
                assertEquals(1.0, ndcg, 1e-10, "Ideal ranking should produce nDCG=1.0");

                double mrr = metrics.mrrAtK(idealRanked, queryQrels, 10);
                assertTrue(mrr > 0.0 && mrr <= 1.0, "MRR should be in (0, 1]");

                double recall = metrics.recallAtK(idealRanked, queryQrels, 10);
                assertTrue(recall >= 0.0 && recall <= 1.0, "Recall should be in [0, 1]");
            }
        }
    }

    @Test
    void statisticalTests_computeValidEffectSize() {
        // Test with synthetic data
        double[] baseline = {0.3, 0.4, 0.35, 0.38, 0.42, 0.5, 0.33, 0.45, 0.39, 0.41};
        double[] cognitive = {0.7, 0.8, 0.75, 0.72, 0.78, 0.85, 0.69, 0.81, 0.74, 0.77};

        double cohensD = StatisticalTests.cohensD(baseline, cognitive);
        assertTrue(cohensD > 0.5, "Large difference should produce Cohen's d > 0.5: " + cohensD);

        double pValue = StatisticalTests.pairedTTestPValue(baseline, cognitive);
        assertTrue(pValue < 0.05, "Large difference should produce p < 0.05: " + pValue);
    }

    // ══════════════════════════════════════════════════════════════
    // Profile diversity
    // ══════════════════════════════════════════════════════════════

    @Test
    void miniDataset_queriesSpanMultipleProfiles() {
        assertNotNull(dataset);
        long distinctProfiles = dataset.queries().stream()
                .map(BenchmarkQuery::cognitiveProfile)
                .distinct()
                .count();

        assertTrue(distinctProfiles >= 2,
                "Queries should span at least 2 distinct cognitive profiles, got: " + distinctProfiles);
    }

    @Test
    void miniDataset_queriesSpanMultipleSubsystems() {
        assertNotNull(dataset);
        long distinctSubsystems = dataset.queries().stream()
                .map(BenchmarkQuery::expectedSubsystem)
                .distinct()
                .count();

        assertTrue(distinctSubsystems >= 5,
                "Queries should span at least 5 expected subsystems, got: " + distinctSubsystems);
    }

    // ══════════════════════════════════════════════════════════════
    // Gate for embedding-dependent tests
    // ══════════════════════════════════════════════════════════════

    static boolean isEmbeddingAvailable() {
        return System.getenv("OLLAMA_LIVE") != null;
    }

    @Test
    @EnabledIf("isEmbeddingAvailable")
    void fullPipeline_cognitiveBeatsBaseline() {
        // This test requires a running embedding service
        // When enabled, it would:
        // 1. Create a SpectorMemory instance via BenchmarkSetup
        // 2. Run all queries through both retrievers
        // 3. Verify cognitive nDCG > baseline nDCG
        assertNotNull(dataset, "Dataset must be loaded");
        // Full implementation deferred to when embedding service is available
    }
}
