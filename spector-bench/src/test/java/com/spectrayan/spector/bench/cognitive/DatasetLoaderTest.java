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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.spectrayan.spector.bench.cognitive.model.BenchmarkCorpusRecord;
import com.spectrayan.spector.bench.cognitive.model.BenchmarkQuery;
import com.spectrayan.spector.bench.cognitive.model.EntityRelation;
import com.spectrayan.spector.bench.cognitive.model.HebbianEdgeDef;
import com.spectrayan.spector.bench.cognitive.model.PersonaDef;
import com.spectrayan.spector.bench.cognitive.model.TemporalChainDef;
import com.spectrayan.spector.memory.CognitiveProfile;
import com.spectrayan.spector.memory.MemoryType;

/**
 * Unit tests for {@link DatasetLoader#loadCorpus(Path)} and {@link DatasetLoader#loadQueries(Path)}.
 */
class DatasetLoaderTest {

    private DatasetLoader loader;
    private Path corpusFile;
    private Path queriesFile;
    private Path qrelsFile;
    private Path entitiesFile;
    private Path temporalChainsFile;
    private Path hebbianEdgesFile;
    private Path personaFile;

    @BeforeEach
    void setUp() {
        loader = new DatasetLoader();
        corpusFile = Path.of("src/test/resources/cognitive-benchmark-mini/corpus.jsonl");
        queriesFile = Path.of("src/test/resources/cognitive-benchmark-mini/queries.jsonl");
        qrelsFile = Path.of("src/test/resources/cognitive-benchmark-mini/qrels.tsv");
        entitiesFile = Path.of("src/test/resources/cognitive-benchmark-mini/entities.jsonl");
        temporalChainsFile = Path.of("src/test/resources/cognitive-benchmark-mini/temporal_chains.jsonl");
        hebbianEdgesFile = Path.of("src/test/resources/cognitive-benchmark-mini/hebbian_edges.jsonl");
        personaFile = Path.of("src/test/resources/cognitive-benchmark-mini/persona.json");
    }

    @Test
    void loadCorpus_parsesValidRecords() {
        List<BenchmarkCorpusRecord> records = loader.loadCorpus(corpusFile);

        assertEquals(50, records.size());

        BenchmarkCorpusRecord first = records.get(0);
        assertEquals("mem-001", first.id());
        assertEquals("Debugging Race Condition", first.title());
        assertEquals(List.of("debugging", "payments", "concurrency"), first.synapticTags());
        assertEquals(45, first.valence());
        assertEquals(6.5f, first.importance(), 0.001f);
        assertEquals(120, first.arousal());
        assertEquals("session-2025-06-15-work", first.sessionId());
        assertEquals(1750000000000L, first.timestampMs());
        assertEquals(2, first.entityMentions().size());
        assertEquals("payment service", first.entityMentions().get(0).name());
        assertEquals("SOFTWARE", first.entityMentions().get(0).type());
        assertEquals(MemoryType.EPISODIC, first.memoryType());
        assertEquals(2, first.recallCount());
    }

    @Test
    void loadCorpus_clampsLowImportance() {
        List<BenchmarkCorpusRecord> records = loader.loadCorpus(corpusFile);
        BenchmarkCorpusRecord clamped = records.get(2); // mem-003 has importance=0.01
        assertEquals(0.05f, clamped.importance(), 0.001f);
    }

    @Test
    void loadCorpus_clampsHighImportance() {
        List<BenchmarkCorpusRecord> records = loader.loadCorpus(corpusFile);
        BenchmarkCorpusRecord clamped = records.get(3); // mem-004 has importance=15.0
        assertEquals(10.0f, clamped.importance(), 0.001f);
    }

    @Test
    void loadCorpus_clampsNegativeArousal() {
        List<BenchmarkCorpusRecord> records = loader.loadCorpus(corpusFile);
        BenchmarkCorpusRecord clamped = records.get(2); // mem-003 has arousal=-5
        assertEquals(0, clamped.arousal());
    }

    @Test
    void loadCorpus_clampsHighArousal() {
        List<BenchmarkCorpusRecord> records = loader.loadCorpus(corpusFile);
        BenchmarkCorpusRecord clamped = records.get(3); // mem-004 has arousal=300
        assertEquals(255, clamped.arousal());
    }

    @Test
    void loadCorpus_clampsNegativeRecallCount() {
        List<BenchmarkCorpusRecord> records = loader.loadCorpus(corpusFile);
        BenchmarkCorpusRecord clamped = records.get(2); // mem-003 has recall_count=-3
        assertEquals(0, clamped.recallCount());
    }

    @Test
    void loadCorpus_mapsMemoryTypeCorrectly() {
        List<BenchmarkCorpusRecord> records = loader.loadCorpus(corpusFile);
        assertEquals(MemoryType.EPISODIC, records.get(0).memoryType());
        assertEquals(MemoryType.EPISODIC, records.get(1).memoryType());
        assertEquals(MemoryType.SEMANTIC, records.get(2).memoryType());
        assertEquals(MemoryType.PROCEDURAL, records.get(3).memoryType());
    }

    @Test
    void loadCorpus_throwsDatasetParseExceptionForMissingFile() {
        assertThrows(DatasetParseException.class,
                () -> loader.loadCorpus(Path.of("nonexistent.jsonl")));
    }

    @Test
    void loadCorpus_throwsDatasetParseExceptionForInvalidJson() throws Exception {
        Path invalidFile = Path.of("src/test/resources/cognitive-benchmark-mini/invalid.jsonl");
        java.nio.file.Files.writeString(invalidFile, "not valid json\n");
        try {
            assertThrows(DatasetParseException.class,
                    () -> loader.loadCorpus(invalidFile));
        } finally {
            java.nio.file.Files.deleteIfExists(invalidFile);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // loadQueries tests
    // ══════════════════════════════════════════════════════════════

    @Test
    void loadQueries_parsesValidRecords() {
        List<BenchmarkQuery> queries = loader.loadQueries(queriesFile);

        assertEquals(20, queries.size());

        BenchmarkQuery first = queries.get(0);
        assertEquals("q-001", first.id());
        assertEquals("What was the concurrency bug I fixed recently?", first.text());
        assertEquals(CognitiveProfile.DEBUGGING, first.cognitiveProfile());
        assertEquals(List.of("debugging", "concurrency"), first.synapticFilterTags());
        assertNull(first.minValence());
        assertEquals((byte) -10, first.maxValence());
        assertEquals("TAG_GATING", first.expectedSubsystem());
        assertEquals("RECENT", first.temporalHint());
    }

    @Test
    void loadQueries_handlesNullableFields() {
        List<BenchmarkQuery> queries = loader.loadQueries(queriesFile);

        BenchmarkQuery second = queries.get(1);
        assertEquals("q-002", second.id());
        assertEquals(CognitiveProfile.RECALLING, second.cognitiveProfile());
        assertEquals((byte) 10, second.minValence());
        assertNull(second.maxValence());
        assertNull(second.temporalHint());
    }

    @Test
    void loadQueries_defaultsUnknownProfileToBalanced() {
        List<BenchmarkQuery> queries = loader.loadQueries(queriesFile);

        BenchmarkQuery third = queries.get(2);
        assertEquals("q-003", third.id());
        assertEquals(CognitiveProfile.BALANCED, third.cognitiveProfile());
        assertEquals("OLD", third.temporalHint());
    }

    @Test
    void loadQueries_handlesEmptyTagsList() {
        List<BenchmarkQuery> queries = loader.loadQueries(queriesFile);

        BenchmarkQuery fourth = queries.get(3);
        assertEquals("q-004", fourth.id());
        assertEquals(CognitiveProfile.DIVERGENT, fourth.cognitiveProfile());
        assertEquals(List.of(), fourth.synapticFilterTags());
        assertEquals((byte) -50, fourth.minValence());
        assertEquals((byte) 50, fourth.maxValence());
    }

    @Test
    void loadQueries_throwsDatasetParseExceptionForMissingFile() {
        assertThrows(DatasetParseException.class,
                () -> loader.loadQueries(Path.of("nonexistent.jsonl")));
    }

    @Test
    void loadQueries_throwsDatasetParseExceptionForInvalidJson() throws Exception {
        Path invalidFile = Path.of("src/test/resources/cognitive-benchmark-mini/invalid-queries.jsonl");
        java.nio.file.Files.writeString(invalidFile, "not valid json\n");
        try {
            assertThrows(DatasetParseException.class,
                    () -> loader.loadQueries(invalidFile));
        } finally {
            java.nio.file.Files.deleteIfExists(invalidFile);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // loadQrels tests
    // ══════════════════════════════════════════════════════════════

    @Test
    void loadQrels_parsesValidRecords() {
        Map<String, Map<String, Integer>> qrels = loader.loadQrels(qrelsFile);

        assertEquals(20, qrels.size());
        assertTrue(qrels.containsKey("q-001"));
        assertTrue(qrels.containsKey("q-002"));
        assertTrue(qrels.containsKey("q-003"));
        assertTrue(qrels.containsKey("q-004"));
    }

    @Test
    void loadQrels_buildsNestedMapCorrectly() {
        Map<String, Map<String, Integer>> qrels = loader.loadQrels(qrelsFile);

        Map<String, Integer> q001 = qrels.get("q-001");
        assertEquals(6, q001.size());
        assertEquals(3, q001.get("mem-001"));
        assertEquals(2, q001.get("mem-002"));
        assertEquals(1, q001.get("mem-003"));

        Map<String, Integer> q002 = qrels.get("q-002");
        assertEquals(5, q002.size());
        assertEquals(3, q002.get("mem-002"));
        assertEquals(1, q002.get("mem-004"));
    }

    @Test
    void loadQrels_skipsHeaderLine() {
        Map<String, Map<String, Integer>> qrels = loader.loadQrels(qrelsFile);

        // "query_id" should not appear as a key
        assertTrue(!qrels.containsKey("query_id"));
    }

    @Test
    void loadQrels_skipsBlankAndCommentLines() throws Exception {
        Path testFile = Path.of("src/test/resources/cognitive-benchmark-mini/qrels-comments.tsv");
        java.nio.file.Files.writeString(testFile,
                "# This is a comment\n" +
                "query_id\tcorpus_id\trelevance_grade\n" +
                "\n" +
                "q-001\tmem-001\t3\n" +
                "# Another comment\n" +
                "\n" +
                "q-001\tmem-002\t1\n");
        try {
            Map<String, Map<String, Integer>> qrels = loader.loadQrels(testFile);
            assertEquals(1, qrels.size());
            assertEquals(2, qrels.get("q-001").size());
            assertEquals(3, qrels.get("q-001").get("mem-001"));
            assertEquals(1, qrels.get("q-001").get("mem-002"));
        } finally {
            java.nio.file.Files.deleteIfExists(testFile);
        }
    }

    @Test
    void loadQrels_throwsDatasetParseExceptionForMissingFile() {
        assertThrows(DatasetParseException.class,
                () -> loader.loadQrels(Path.of("nonexistent.tsv")));
    }

    @Test
    void loadQrels_throwsDatasetParseExceptionForMalformedLine() throws Exception {
        Path malformedFile = Path.of("src/test/resources/cognitive-benchmark-mini/qrels-malformed.tsv");
        java.nio.file.Files.writeString(malformedFile, "q-001\tmem-001\n");
        try {
            assertThrows(DatasetParseException.class,
                    () -> loader.loadQrels(malformedFile));
        } finally {
            java.nio.file.Files.deleteIfExists(malformedFile);
        }
    }

    @Test
    void loadQrels_throwsDatasetParseExceptionForInvalidGrade() throws Exception {
        Path invalidFile = Path.of("src/test/resources/cognitive-benchmark-mini/qrels-invalid-grade.tsv");
        java.nio.file.Files.writeString(invalidFile, "q-001\tmem-001\tnot_a_number\n");
        try {
            assertThrows(DatasetParseException.class,
                    () -> loader.loadQrels(invalidFile));
        } finally {
            java.nio.file.Files.deleteIfExists(invalidFile);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // loadEntities tests
    // ══════════════════════════════════════════════════════════════

    @Test
    void loadEntities_parsesValidRecords() {
        List<EntityRelation> relations = loader.loadEntities(entitiesFile);

        assertEquals(15, relations.size());

        EntityRelation first = relations.get(0);
        assertEquals("Alice", first.fromEntity().name());
        assertEquals("PERSON", first.fromEntity().type());
        assertEquals("payment service", first.toEntity().name());
        assertEquals("SOFTWARE", first.toEntity().type());
        assertEquals("WORKS_ON", first.relationType());
        assertEquals(List.of("mem-001", "mem-002"), first.sourceMemoryIds());
    }

    @Test
    void loadEntities_parsesKnownRelationTypes() {
        List<EntityRelation> relations = loader.loadEntities(entitiesFile);

        assertEquals("WORKS_ON", relations.get(0).relationType());
        assertEquals("MANAGES", relations.get(1).relationType());
        assertEquals("DEPENDS_ON", relations.get(2).relationType());
    }

    @Test
    void loadEntities_defaultsUnknownRelationTypeToOther() {
        List<EntityRelation> relations = loader.loadEntities(entitiesFile);

        // Fourth entry has "UNKNOWN_RELATION" which should default to "OTHER"
        assertEquals("OTHER", relations.get(3).relationType());
    }

    @Test
    void loadEntities_throwsDatasetParseExceptionForMissingFile() {
        assertThrows(DatasetParseException.class,
                () -> loader.loadEntities(Path.of("nonexistent.jsonl")));
    }

    // ══════════════════════════════════════════════════════════════
    // loadTemporalChains tests
    // ══════════════════════════════════════════════════════════════

    @Test
    void loadTemporalChains_parsesValidRecords() {
        List<TemporalChainDef> chains = loader.loadTemporalChains(temporalChainsFile);

        assertEquals(7, chains.size());

        TemporalChainDef first = chains.get(0);
        assertEquals("session-2025-06-15-work", first.sessionId());
        assertEquals(List.of("mem-001", "mem-003", "mem-004"), first.orderedMemoryIds());

        TemporalChainDef second = chains.get(1);
        assertEquals("session-2025-06-15-personal", second.sessionId());
        assertEquals(List.of("mem-012", "mem-002"), second.orderedMemoryIds());
    }

    @Test
    void loadTemporalChains_throwsDatasetParseExceptionForMissingFile() {
        assertThrows(DatasetParseException.class,
                () -> loader.loadTemporalChains(Path.of("nonexistent.jsonl")));
    }

    // ══════════════════════════════════════════════════════════════
    // loadHebbianEdges tests
    // ══════════════════════════════════════════════════════════════

    @Test
    void loadHebbianEdges_parsesValidRecords() {
        List<HebbianEdgeDef> edges = loader.loadHebbianEdges(hebbianEdgesFile);

        assertEquals(12, edges.size());

        HebbianEdgeDef first = edges.get(0);
        assertEquals("mem-001", first.memoryIdA());
        assertEquals("mem-002", first.memoryIdB());
        assertEquals(5, first.coActivationCount());

        HebbianEdgeDef second = edges.get(1);
        assertEquals("mem-002", second.memoryIdA());
        assertEquals("mem-003", second.memoryIdB());
        assertEquals(3, second.coActivationCount());
    }

    @Test
    void loadHebbianEdges_throwsDatasetParseExceptionForMissingFile() {
        assertThrows(DatasetParseException.class,
                () -> loader.loadHebbianEdges(Path.of("nonexistent.jsonl")));
    }

    // ══════════════════════════════════════════════════════════════
    // loadPersona tests
    // ══════════════════════════════════════════════════════════════

    @Test
    void loadPersona_parsesValidPersona() {
        PersonaDef persona = loader.loadPersona(personaFile);

        assertEquals("Jordan Chen", persona.name());
        assertEquals(32, persona.age());
        assertEquals("Senior Software Engineer", persona.occupation());
        assertEquals(5, persona.interests().size());
        assertTrue(persona.interests().contains("distributed systems"));
        assertTrue(persona.interests().contains("rock climbing"));
        assertEquals(5, persona.personalityTraits().size());
        assertTrue(persona.personalityTraits().contains("analytical"));
        assertTrue(persona.lifeContext().length() >= 50);
        assertTrue(persona.companionRelationship().length() >= 50);
    }

    @Test
    void loadPersona_throwsValidationExceptionForInvalidAge() throws Exception {
        Path invalidFile = Path.of("src/test/resources/cognitive-benchmark-mini/persona-invalid-age.json");
        java.nio.file.Files.writeString(invalidFile, """
                {
                  "name": "Test",
                  "age": 150,
                  "occupation": "Tester",
                  "interests": ["a", "b", "c"],
                  "life_context": "This is a life context string that is definitely longer than fifty characters for testing purposes right here.",
                  "personality_traits": ["trait1", "trait2", "trait3"],
                  "companion_relationship": "This is a companion relationship string that is longer than fifty characters for the test."
                }
                """);
        try {
            assertThrows(DatasetValidationException.class,
                    () -> loader.loadPersona(invalidFile));
        } finally {
            java.nio.file.Files.deleteIfExists(invalidFile);
        }
    }

    @Test
    void loadPersona_throwsValidationExceptionForTooFewInterests() throws Exception {
        Path invalidFile = Path.of("src/test/resources/cognitive-benchmark-mini/persona-few-interests.json");
        java.nio.file.Files.writeString(invalidFile, """
                {
                  "name": "Test",
                  "age": 30,
                  "occupation": "Tester",
                  "interests": ["a"],
                  "life_context": "This is a life context string that is definitely longer than fifty characters for testing purposes right here.",
                  "personality_traits": ["trait1", "trait2", "trait3"],
                  "companion_relationship": "This is a companion relationship string that is longer than fifty characters for the test."
                }
                """);
        try {
            assertThrows(DatasetValidationException.class,
                    () -> loader.loadPersona(invalidFile));
        } finally {
            java.nio.file.Files.deleteIfExists(invalidFile);
        }
    }

    @Test
    void loadPersona_throwsDatasetParseExceptionForMissingFile() {
        assertThrows(DatasetParseException.class,
                () -> loader.loadPersona(Path.of("nonexistent.json")));
    }

    // ══════════════════════════════════════════════════════════════
    // load(Path) orchestrator tests
    // ══════════════════════════════════════════════════════════════

    @Test
    void load_returnsCompleteLoadedDataset() {
        Path datasetDir = Path.of("src/test/resources/cognitive-benchmark-mini");
        DatasetLoader.LoadedDataset dataset = loader.load(datasetDir);

        assertEquals(50, dataset.corpus().size());
        assertEquals(20, dataset.queries().size());
        assertEquals(20, dataset.qrels().size());
        assertEquals(15, dataset.entityRelations().size());
        assertEquals(7, dataset.temporalChains().size());
        assertEquals(12, dataset.hebbianEdges().size());
        assertEquals("Jordan Chen", dataset.persona().name());
    }

    @Test
    void load_throwsDatasetParseExceptionForMissingDirectory() {
        assertThrows(DatasetParseException.class,
                () -> loader.load(Path.of("nonexistent-dir")));
    }

    @Test
    void load_filtersHebbianEdgesWithMissingCorpusIds() throws Exception {
        // Create a temp dataset directory with a hebbian edge referencing non-existent IDs
        Path tempDir = java.nio.file.Files.createTempDirectory("dataset-test");
        try {
            java.nio.file.Files.copy(
                    Path.of("src/test/resources/cognitive-benchmark-mini/corpus.jsonl"),
                    tempDir.resolve("corpus.jsonl"));
            java.nio.file.Files.copy(
                    Path.of("src/test/resources/cognitive-benchmark-mini/queries.jsonl"),
                    tempDir.resolve("queries.jsonl"));
            java.nio.file.Files.copy(
                    Path.of("src/test/resources/cognitive-benchmark-mini/qrels.tsv"),
                    tempDir.resolve("qrels.tsv"));
            java.nio.file.Files.copy(
                    Path.of("src/test/resources/cognitive-benchmark-mini/entities.jsonl"),
                    tempDir.resolve("entities.jsonl"));
            java.nio.file.Files.copy(
                    Path.of("src/test/resources/cognitive-benchmark-mini/temporal_chains.jsonl"),
                    tempDir.resolve("temporal_chains.jsonl"));
            java.nio.file.Files.copy(
                    Path.of("src/test/resources/cognitive-benchmark-mini/persona.json"),
                    tempDir.resolve("persona.json"));

            // Write hebbian edges with one valid and one invalid (referencing non-existent ID)
            java.nio.file.Files.writeString(tempDir.resolve("hebbian_edges.jsonl"), """
                    {"memory_id_a": "mem-001", "memory_id_b": "mem-002", "co_activation_count": 5}
                    {"memory_id_a": "mem-001", "memory_id_b": "mem-NONEXISTENT", "co_activation_count": 3}
                    {"memory_id_a": "mem-003", "memory_id_b": "mem-004", "co_activation_count": 2}
                    """);

            DatasetLoader.LoadedDataset dataset = loader.load(tempDir);

            // Should have 2 valid edges (the one with mem-NONEXISTENT should be skipped)
            assertEquals(2, dataset.hebbianEdges().size());
            assertEquals("mem-001", dataset.hebbianEdges().get(0).memoryIdA());
            assertEquals("mem-002", dataset.hebbianEdges().get(0).memoryIdB());
            assertEquals("mem-003", dataset.hebbianEdges().get(1).memoryIdA());
            assertEquals("mem-004", dataset.hebbianEdges().get(1).memoryIdB());
        } finally {
            // Cleanup temp directory
            try (var files = java.nio.file.Files.walk(tempDir)) {
                files.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> { try { java.nio.file.Files.delete(p); } catch (Exception ignored) {} });
            }
        }
    }

    @Test
    void load_throwsValidationExceptionForInvalidQrelQueryId() throws Exception {
        Path tempDir = java.nio.file.Files.createTempDirectory("dataset-test");
        try {
            java.nio.file.Files.copy(
                    Path.of("src/test/resources/cognitive-benchmark-mini/corpus.jsonl"),
                    tempDir.resolve("corpus.jsonl"));
            java.nio.file.Files.copy(
                    Path.of("src/test/resources/cognitive-benchmark-mini/queries.jsonl"),
                    tempDir.resolve("queries.jsonl"));
            java.nio.file.Files.copy(
                    Path.of("src/test/resources/cognitive-benchmark-mini/entities.jsonl"),
                    tempDir.resolve("entities.jsonl"));
            java.nio.file.Files.copy(
                    Path.of("src/test/resources/cognitive-benchmark-mini/temporal_chains.jsonl"),
                    tempDir.resolve("temporal_chains.jsonl"));
            java.nio.file.Files.copy(
                    Path.of("src/test/resources/cognitive-benchmark-mini/hebbian_edges.jsonl"),
                    tempDir.resolve("hebbian_edges.jsonl"));
            java.nio.file.Files.copy(
                    Path.of("src/test/resources/cognitive-benchmark-mini/persona.json"),
                    tempDir.resolve("persona.json"));

            // Write qrels with a query_id that doesn't exist in queries
            java.nio.file.Files.writeString(tempDir.resolve("qrels.tsv"),
                    "query_id\tcorpus_id\trelevance_grade\nq-NONEXISTENT\tmem-001\t3\n");

            DatasetValidationException ex = assertThrows(DatasetValidationException.class,
                    () -> loader.load(tempDir));
            assertTrue(ex.getMessage().contains("q-NONEXISTENT"));
        } finally {
            try (var files = java.nio.file.Files.walk(tempDir)) {
                files.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> { try { java.nio.file.Files.delete(p); } catch (Exception ignored) {} });
            }
        }
    }

    @Test
    void load_throwsValidationExceptionForInvalidQrelCorpusId() throws Exception {
        Path tempDir = java.nio.file.Files.createTempDirectory("dataset-test");
        try {
            java.nio.file.Files.copy(
                    Path.of("src/test/resources/cognitive-benchmark-mini/corpus.jsonl"),
                    tempDir.resolve("corpus.jsonl"));
            java.nio.file.Files.copy(
                    Path.of("src/test/resources/cognitive-benchmark-mini/queries.jsonl"),
                    tempDir.resolve("queries.jsonl"));
            java.nio.file.Files.copy(
                    Path.of("src/test/resources/cognitive-benchmark-mini/entities.jsonl"),
                    tempDir.resolve("entities.jsonl"));
            java.nio.file.Files.copy(
                    Path.of("src/test/resources/cognitive-benchmark-mini/temporal_chains.jsonl"),
                    tempDir.resolve("temporal_chains.jsonl"));
            java.nio.file.Files.copy(
                    Path.of("src/test/resources/cognitive-benchmark-mini/hebbian_edges.jsonl"),
                    tempDir.resolve("hebbian_edges.jsonl"));
            java.nio.file.Files.copy(
                    Path.of("src/test/resources/cognitive-benchmark-mini/persona.json"),
                    tempDir.resolve("persona.json"));

            // Write qrels with a corpus_id that doesn't exist in corpus
            java.nio.file.Files.writeString(tempDir.resolve("qrels.tsv"),
                    "query_id\tcorpus_id\trelevance_grade\nq-001\tmem-NONEXISTENT\t3\n");

            DatasetValidationException ex = assertThrows(DatasetValidationException.class,
                    () -> loader.load(tempDir));
            assertTrue(ex.getMessage().contains("mem-NONEXISTENT"));
        } finally {
            try (var files = java.nio.file.Files.walk(tempDir)) {
                files.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> { try { java.nio.file.Files.delete(p); } catch (Exception ignored) {} });
            }
        }
    }

    @Test
    void load_throwsValidationExceptionForTemporalChainMissingMemoryId() throws Exception {
        Path tempDir = java.nio.file.Files.createTempDirectory("dataset-test");
        try {
            java.nio.file.Files.copy(
                    Path.of("src/test/resources/cognitive-benchmark-mini/corpus.jsonl"),
                    tempDir.resolve("corpus.jsonl"));
            java.nio.file.Files.copy(
                    Path.of("src/test/resources/cognitive-benchmark-mini/queries.jsonl"),
                    tempDir.resolve("queries.jsonl"));
            java.nio.file.Files.copy(
                    Path.of("src/test/resources/cognitive-benchmark-mini/qrels.tsv"),
                    tempDir.resolve("qrels.tsv"));
            java.nio.file.Files.copy(
                    Path.of("src/test/resources/cognitive-benchmark-mini/entities.jsonl"),
                    tempDir.resolve("entities.jsonl"));
            java.nio.file.Files.copy(
                    Path.of("src/test/resources/cognitive-benchmark-mini/hebbian_edges.jsonl"),
                    tempDir.resolve("hebbian_edges.jsonl"));
            java.nio.file.Files.copy(
                    Path.of("src/test/resources/cognitive-benchmark-mini/persona.json"),
                    tempDir.resolve("persona.json"));

            // Write temporal chains referencing a non-existent memory ID
            java.nio.file.Files.writeString(tempDir.resolve("temporal_chains.jsonl"),
                    """
                    {"session_id": "session-test", "ordered_memory_ids": ["mem-001", "mem-NONEXISTENT"]}
                    """);

            DatasetValidationException ex = assertThrows(DatasetValidationException.class,
                    () -> loader.load(tempDir));
            assertTrue(ex.getMessage().contains("mem-NONEXISTENT"));
            assertTrue(ex.getMessage().contains("temporal chain"));
        } finally {
            try (var files = java.nio.file.Files.walk(tempDir)) {
                files.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> { try { java.nio.file.Files.delete(p); } catch (Exception ignored) {} });
            }
        }
    }

    @Test
    void load_throwsValidationExceptionForTemporalChainOutOfOrder() throws Exception {
        Path tempDir = java.nio.file.Files.createTempDirectory("dataset-test");
        try {
            java.nio.file.Files.copy(
                    Path.of("src/test/resources/cognitive-benchmark-mini/corpus.jsonl"),
                    tempDir.resolve("corpus.jsonl"));
            java.nio.file.Files.copy(
                    Path.of("src/test/resources/cognitive-benchmark-mini/queries.jsonl"),
                    tempDir.resolve("queries.jsonl"));
            java.nio.file.Files.copy(
                    Path.of("src/test/resources/cognitive-benchmark-mini/qrels.tsv"),
                    tempDir.resolve("qrels.tsv"));
            java.nio.file.Files.copy(
                    Path.of("src/test/resources/cognitive-benchmark-mini/entities.jsonl"),
                    tempDir.resolve("entities.jsonl"));
            java.nio.file.Files.copy(
                    Path.of("src/test/resources/cognitive-benchmark-mini/hebbian_edges.jsonl"),
                    tempDir.resolve("hebbian_edges.jsonl"));
            java.nio.file.Files.copy(
                    Path.of("src/test/resources/cognitive-benchmark-mini/persona.json"),
                    tempDir.resolve("persona.json"));

            // Write temporal chains with memory IDs in wrong timestamp order
            // mem-003 has timestamp 1750010000000, mem-001 has timestamp 1750000000000
            java.nio.file.Files.writeString(tempDir.resolve("temporal_chains.jsonl"),
                    """
                    {"session_id": "session-test", "ordered_memory_ids": ["mem-003", "mem-001"]}
                    """);

            DatasetValidationException ex = assertThrows(DatasetValidationException.class,
                    () -> loader.load(tempDir));
            assertTrue(ex.getMessage().contains("out of ascending order"));
        } finally {
            try (var files = java.nio.file.Files.walk(tempDir)) {
                files.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> { try { java.nio.file.Files.delete(p); } catch (Exception ignored) {} });
            }
        }
    }

    @Test
    void load_throwsValidationExceptionForEntityRelationMissingSourceMemoryId() throws Exception {
        Path tempDir = java.nio.file.Files.createTempDirectory("dataset-test");
        try {
            java.nio.file.Files.copy(
                    Path.of("src/test/resources/cognitive-benchmark-mini/corpus.jsonl"),
                    tempDir.resolve("corpus.jsonl"));
            java.nio.file.Files.copy(
                    Path.of("src/test/resources/cognitive-benchmark-mini/queries.jsonl"),
                    tempDir.resolve("queries.jsonl"));
            java.nio.file.Files.copy(
                    Path.of("src/test/resources/cognitive-benchmark-mini/qrels.tsv"),
                    tempDir.resolve("qrels.tsv"));
            java.nio.file.Files.copy(
                    Path.of("src/test/resources/cognitive-benchmark-mini/temporal_chains.jsonl"),
                    tempDir.resolve("temporal_chains.jsonl"));
            java.nio.file.Files.copy(
                    Path.of("src/test/resources/cognitive-benchmark-mini/hebbian_edges.jsonl"),
                    tempDir.resolve("hebbian_edges.jsonl"));
            java.nio.file.Files.copy(
                    Path.of("src/test/resources/cognitive-benchmark-mini/persona.json"),
                    tempDir.resolve("persona.json"));

            // Write entities with a source_memory_id that doesn't exist
            java.nio.file.Files.writeString(tempDir.resolve("entities.jsonl"),
                    """
                    {"from_entity": {"name": "Alice", "type": "PERSON"}, "to_entity": {"name": "Bob", "type": "PERSON"}, "relation_type": "KNOWS", "source_memory_ids": ["mem-001", "mem-NONEXISTENT"]}
                    """);

            DatasetValidationException ex = assertThrows(DatasetValidationException.class,
                    () -> loader.load(tempDir));
            assertTrue(ex.getMessage().contains("mem-NONEXISTENT"));
            assertTrue(ex.getMessage().contains("entity relation"));
        } finally {
            try (var files = java.nio.file.Files.walk(tempDir)) {
                files.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> { try { java.nio.file.Files.delete(p); } catch (Exception ignored) {} });
            }
        }
    }
}
