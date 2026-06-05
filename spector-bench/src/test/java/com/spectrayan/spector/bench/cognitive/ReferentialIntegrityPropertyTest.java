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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.spectrayan.spector.bench.cognitive.model.BenchmarkCorpusRecord;
import com.spectrayan.spector.bench.cognitive.model.BenchmarkQuery;
import com.spectrayan.spector.bench.cognitive.model.HebbianEdgeDef;
import com.spectrayan.spector.memory.CognitiveProfile;
import com.spectrayan.spector.memory.MemoryType;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Property-based tests for referential integrity violation detection.
 *
 * <p><b>Validates: Requirements 1.3, 1.5, 1.6, 18.7</b>
 *
 * <p>Property 2: For any dataset with injected referential integrity violations,
 * the DatasetLoader validator SHALL detect and report every violation without
 * false negatives.
 */
class ReferentialIntegrityPropertyTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final DatasetLoader loader = new DatasetLoader();

    // ══════════════════════════════════════════════════════════════
    // Property 2a: Dangling qrels query_id always detected
    // ══════════════════════════════════════════════════════════════

    /**
     * Property 2a: A qrels file referencing a non-existent query ID shall always
     * be detected as a referential integrity violation.
     *
     * <p><b>Validates: Requirements 1.3</b>
     */
    @Property(tries = 100)
    void danglingQrelQueryId_alwaysDetected(
            @ForAll @IntRange(min = 3, max = 10) int corpusSize) throws IOException {

        Path tempDir = Files.createTempDirectory("ref-integrity-test");
        try {
            // Create a valid corpus
            List<BenchmarkCorpusRecord> corpus = createCorpus(corpusSize);
            writeCorpus(tempDir, corpus);

            // Create a valid query
            List<BenchmarkQuery> queries = List.of(
                    new BenchmarkQuery("q-001", "test query", CognitiveProfile.BALANCED,
                            List.of(), null, null, "TAG_GATING", null));
            writeQueries(tempDir, queries);

            // Create qrels with a DANGLING query ID (non-existent)
            String danglingQueryId = "q-nonexistent-999";
            String qrelsContent = danglingQueryId + "\t" + corpus.getFirst().id() + "\t3\n";
            Files.writeString(tempDir.resolve("qrels.tsv"), qrelsContent);

            // Write empty graphs
            writeEmptyGraphs(tempDir);
            writePersona(tempDir);

            // Attempt load — should throw DatasetValidationException
            boolean exceptionThrown = false;
            try {
                loader.load(tempDir);
            } catch (DatasetValidationException e) {
                exceptionThrown = true;
                assert e.getMessage().contains(danglingQueryId)
                        || e.getMessage().toLowerCase().contains("query")
                        : "Exception should mention the dangling query ID: " + e.getMessage();
            }
            assert exceptionThrown : "DatasetValidationException should be thrown for dangling qrel query_id";
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /**
     * Property 2b: A hebbian edge referencing a non-existent corpus ID shall always
     * be detected (edges with missing IDs are skipped per Req 5.5, but validation logs them).
     *
     * <p><b>Validates: Requirements 1.6</b>
     */
    @Property(tries = 100)
    void danglingHebbianEdge_isHandled(
            @ForAll @IntRange(min = 3, max = 10) int corpusSize) throws IOException {

        Path tempDir = Files.createTempDirectory("ref-integrity-test");
        try {
            List<BenchmarkCorpusRecord> corpus = createCorpus(corpusSize);
            writeCorpus(tempDir, corpus);

            List<BenchmarkQuery> queries = List.of(
                    new BenchmarkQuery("q-001", "test query", CognitiveProfile.BALANCED,
                            List.of(), null, null, "TAG_GATING", null));
            writeQueries(tempDir, queries);

            // Valid qrels
            String qrelsContent = "q-001\t" + corpus.getFirst().id() + "\t2\n";
            Files.writeString(tempDir.resolve("qrels.tsv"), qrelsContent);

            // Hebbian edge with dangling reference
            HebbianEdgeDef danglingEdge = new HebbianEdgeDef(
                    corpus.getFirst().id(), "mem-nonexistent-xyz", 5);
            Files.writeString(tempDir.resolve("hebbian_edges.jsonl"),
                    String.format("{\"memory_id_a\":\"%s\",\"memory_id_b\":\"%s\",\"co_activation_count\":%d}",
                            danglingEdge.memoryIdA(), danglingEdge.memoryIdB(), danglingEdge.coActivationCount()) + "\n");

            Files.writeString(tempDir.resolve("temporal_chains.jsonl"), "");
            Files.writeString(tempDir.resolve("entities.jsonl"), "");
            writePersona(tempDir);

            // Per Req 5.5, edges with missing IDs are skipped — load should succeed
            // but the edge should be excluded from the loaded dataset
            var dataset = loader.load(tempDir);
            // The dangling edge should have been skipped
            boolean edgeSkipped = dataset.hebbianEdges().stream()
                    .noneMatch(e -> "mem-nonexistent-xyz".equals(e.memoryIdA())
                            || "mem-nonexistent-xyz".equals(e.memoryIdB()));
            assert edgeSkipped : "Dangling hebbian edge should be skipped";
        } finally {
            deleteRecursively(tempDir);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════

    private List<BenchmarkCorpusRecord> createCorpus(int size) {
        return IntStream.range(0, size)
                .mapToObj(i -> new BenchmarkCorpusRecord(
                        "mem-" + String.format("%03d", i),
                        "Memory text content number " + i,
                        "Title " + i,
                        List.of("tag-" + i),
                        (byte) 0,
                        5.0f,
                        100,
                        "session-001",
                        1750000000000L + i * 60000L,
                        List.of(),
                        MemoryType.EPISODIC,
                        0))
                .collect(Collectors.toList());
    }

    private void writeCorpus(Path dir, List<BenchmarkCorpusRecord> corpus) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (var record : corpus) {
            sb.append(String.format(
                    "{\"id\":\"%s\",\"text\":\"%s\",\"title\":\"%s\"," +
                    "\"synaptic_tags\":%s,\"valence\":%d,\"importance\":%.4f," +
                    "\"arousal\":%d,\"session_id\":\"%s\",\"timestamp_ms\":%d," +
                    "\"entity_mentions\":[],\"memory_type\":\"%s\",\"recall_count\":%d}",
                    record.id(), record.text(), record.title(),
                    mapper.writeValueAsString(record.synapticTags()),
                    record.valence(), record.importance(),
                    record.arousal(), record.sessionId(), record.timestampMs(),
                    record.memoryType().name(), record.recallCount()))
                .append("\n");
        }
        Files.writeString(dir.resolve("corpus.jsonl"), sb.toString());
    }

    private void writeQueries(Path dir, List<BenchmarkQuery> queries) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (var query : queries) {
            sb.append(String.format(
                    "{\"id\":\"%s\",\"text\":\"%s\",\"cognitive_profile\":\"%s\"," +
                    "\"synaptic_filter_tags\":[],\"min_valence\":null,\"max_valence\":null," +
                    "\"expected_subsystem\":\"TAG_GATING\",\"temporal_hint\":null}",
                    query.id(), query.text(), query.cognitiveProfile().name()))
                .append("\n");
        }
        Files.writeString(dir.resolve("queries.jsonl"), sb.toString());
    }

    private void writeEmptyGraphs(Path dir) throws IOException {
        Files.writeString(dir.resolve("hebbian_edges.jsonl"), "");
        Files.writeString(dir.resolve("temporal_chains.jsonl"), "");
        Files.writeString(dir.resolve("entities.jsonl"), "");
    }

    private void writePersona(Path dir) throws IOException {
        String personaJson = "{\"name\":\"Test Person\",\"age\":30,\"occupation\":\"Engineer\"," +
                "\"interests\":[\"coding\",\"testing\",\"design\"]," +
                "\"life_context\":\"A test persona for unit testing purposes with enough context to satisfy validation requirements\"," +
                "\"personality_traits\":[\"analytical\",\"curious\",\"detail-oriented\"]," +
                "\"companion_relationship\":\"Has been using the AI companion for testing purposes for several months now in a testing context\"}";
        Files.writeString(dir.resolve("persona.json"), personaJson);
    }

    private void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var entries = Files.list(path)) {
                for (Path entry : entries.toList()) {
                    deleteRecursively(entry);
                }
            }
        }
        Files.deleteIfExists(path);
    }
}
