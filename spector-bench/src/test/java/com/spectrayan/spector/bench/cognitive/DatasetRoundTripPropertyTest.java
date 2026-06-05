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

import com.spectrayan.spector.bench.cognitive.model.BenchmarkCorpusRecord;
import com.spectrayan.spector.bench.cognitive.model.BenchmarkQuery;
import com.spectrayan.spector.bench.cognitive.model.HebbianEdgeDef;
import com.spectrayan.spector.memory.CognitiveProfile;
import com.spectrayan.spector.memory.MemoryType;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Property-based tests for dataset format round-trip serialization.
 *
 * <p><b>Validates: Requirements 1.1, 1.2, 1.4, 1.5, 1.6</b>
 *
 * <p>Property 1: For any valid model record, serializing to JSONL and parsing back
 * SHALL produce an equivalent record.
 */
class DatasetRoundTripPropertyTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final DatasetLoader loader = new DatasetLoader();

    // ══════════════════════════════════════════════════════════════
    // Property 1a: BenchmarkCorpusRecord round-trip
    // ══════════════════════════════════════════════════════════════

    /**
     * Property 1a: Serializing a BenchmarkCorpusRecord to JSON and parsing it back
     * produces an equivalent record.
     *
     * <p><b>Validates: Requirements 1.1</b>
     */
    @Property(tries = 100)
    void corpusRecord_roundTrip(@ForAll("corpusRecords") BenchmarkCorpusRecord original) throws IOException {
        Path tempDir = Files.createTempDirectory("round-trip-test");
        try {
            Path corpusFile = tempDir.resolve("corpus.jsonl");
            // Write JSON in snake_case format matching corpus.jsonl schema
            String json = String.format(
                    "{\"id\":\"%s\",\"text\":\"%s\",\"title\":\"%s\"," +
                    "\"synaptic_tags\":%s,\"valence\":%d,\"importance\":%.4f," +
                    "\"arousal\":%d,\"session_id\":\"%s\",\"timestamp_ms\":%d," +
                    "\"entity_mentions\":[],\"memory_type\":\"%s\",\"recall_count\":%d}",
                    original.id(), original.text(), original.title(),
                    mapper.writeValueAsString(original.synapticTags()),
                    original.valence(), original.importance(),
                    original.arousal(), original.sessionId(), original.timestampMs(),
                    original.memoryType().name(), original.recallCount());
            Files.writeString(corpusFile, json + "\n");

            List<BenchmarkCorpusRecord> parsed = loader.loadCorpus(corpusFile);
            assert parsed.size() == 1 : "Expected 1 record, got " + parsed.size();

            BenchmarkCorpusRecord result = parsed.getFirst();
            assert original.id().equals(result.id()) : "ID mismatch";
            assert original.text().equals(result.text()) : "Text mismatch";
            assert original.title().equals(result.title()) : "Title mismatch";
            assert original.synapticTags().equals(result.synapticTags()) : "Tags mismatch";
            assert original.valence() == result.valence() : "Valence mismatch";
            // Importance may be clamped, but valid values should round-trip
            assert Math.abs(original.importance() - result.importance()) < 0.001f : "Importance mismatch";
            assert original.arousal() == result.arousal() : "Arousal mismatch";
            assert original.sessionId().equals(result.sessionId()) : "SessionId mismatch";
            assert original.timestampMs() == result.timestampMs() : "Timestamp mismatch";
            assert original.memoryType() == result.memoryType() : "MemoryType mismatch";
            assert original.recallCount() == result.recallCount() : "RecallCount mismatch";
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /**
     * Property 1b: Serializing a BenchmarkQuery to JSON and parsing it back
     * produces an equivalent record.
     *
     * <p><b>Validates: Requirements 1.2</b>
     */
    @Property(tries = 100)
    void benchmarkQuery_roundTrip(@ForAll("queries") BenchmarkQuery original) throws IOException {
        Path tempDir = Files.createTempDirectory("round-trip-test");
        try {
            Path queryFile = tempDir.resolve("queries.jsonl");
            String json = String.format(
                    "{\"id\":\"%s\",\"text\":\"%s\",\"cognitive_profile\":\"%s\"," +
                    "\"synaptic_filter_tags\":%s,\"min_valence\":null,\"max_valence\":null," +
                    "\"expected_subsystem\":\"%s\",\"temporal_hint\":null}",
                    original.id(), original.text(), original.cognitiveProfile().name(),
                    mapper.writeValueAsString(original.synapticFilterTags()),
                    original.expectedSubsystem());
            Files.writeString(queryFile, json + "\n");

            List<BenchmarkQuery> parsed = loader.loadQueries(queryFile);
            assert parsed.size() == 1 : "Expected 1 query, got " + parsed.size();

            BenchmarkQuery result = parsed.getFirst();
            assert original.id().equals(result.id()) : "ID mismatch";
            assert original.text().equals(result.text()) : "Text mismatch";
            assert original.cognitiveProfile() == result.cognitiveProfile() : "Profile mismatch";
            assert original.synapticFilterTags().equals(result.synapticFilterTags()) : "Filter tags mismatch";
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /**
     * Property 1c: Serializing a HebbianEdgeDef to JSON and parsing it back
     * produces an equivalent record.
     *
     * <p><b>Validates: Requirements 1.6</b>
     */
    @Property(tries = 100)
    void hebbianEdge_roundTrip(@ForAll("hebbianEdges") HebbianEdgeDef original) throws IOException {
        Path tempDir = Files.createTempDirectory("round-trip-test");
        try {
            Path edgeFile = tempDir.resolve("hebbian_edges.jsonl");
            String json = String.format(
                    "{\"memory_id_a\":\"%s\",\"memory_id_b\":\"%s\",\"co_activation_count\":%d}",
                    original.memoryIdA(), original.memoryIdB(), original.coActivationCount());
            Files.writeString(edgeFile, json + "\n");

            List<HebbianEdgeDef> parsed = loader.loadHebbianEdges(edgeFile);
            assert parsed.size() == 1 : "Expected 1 edge, got " + parsed.size();

            HebbianEdgeDef result = parsed.getFirst();
            assert original.memoryIdA().equals(result.memoryIdA()) : "MemoryIdA mismatch";
            assert original.memoryIdB().equals(result.memoryIdB()) : "MemoryIdB mismatch";
            assert original.coActivationCount() == result.coActivationCount() : "CoActivationCount mismatch";
        } finally {
            deleteRecursively(tempDir);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Generators
    // ══════════════════════════════════════════════════════════════

    @Provide
    Arbitrary<BenchmarkCorpusRecord> corpusRecords() {
        // jqwik Combinators.combine supports max 8 params, so build in steps
        Arbitrary<String> ids = Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(10).map(s -> "mem-" + s);
        Arbitrary<String> texts = Arbitraries.strings().alpha().ofMinLength(10).ofMaxLength(50);
        Arbitrary<String> titles = Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(20);
        Arbitrary<List<String>> tags = Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(15)
                .list().ofMinSize(1).ofMaxSize(5);
        Arbitrary<Byte> valences = Arbitraries.bytes();
        Arbitrary<Float> importances = Arbitraries.floats().between(0.05f, 10.0f);
        Arbitrary<Integer> arousals = Arbitraries.integers().between(0, 255);
        Arbitrary<MemoryType> types = Arbitraries.of(MemoryType.values());

        return Combinators.combine(ids, texts, titles, tags, valences, importances, arousals, types)
                .as((id, text, title, tagList, valence, importance, arousal, type) ->
                        new BenchmarkCorpusRecord(id, text, title, tagList, valence, importance, arousal,
                                "session-test", 1750000000000L, List.of(), type, 0));
    }

    @Provide
    Arbitrary<BenchmarkQuery> queries() {
        return Combinators.combine(
                Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(8).map(s -> "q-" + s),
                Arbitraries.strings().alpha().ofMinLength(5).ofMaxLength(50),
                Arbitraries.of(CognitiveProfile.BALANCED, CognitiveProfile.DEBUGGING,
                        CognitiveProfile.EXPLORING, CognitiveProfile.RECALLING),
                Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(10)
                        .list().ofMinSize(0).ofMaxSize(3)
        ).as((id, text, profile, tags) ->
                new BenchmarkQuery(id, text, profile, tags, null, null, "TAG_GATING", null));
    }

    @Provide
    Arbitrary<HebbianEdgeDef> hebbianEdges() {
        return Combinators.combine(
                Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(8).map(s -> "mem-" + s),
                Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(8).map(s -> "mem-" + s),
                Arbitraries.integers().between(1, 100)
        ).as(HebbianEdgeDef::new);
    }

    // ══════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════

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
