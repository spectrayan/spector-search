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

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.bench.cognitive.model.BenchmarkCorpusRecord;
import com.spectrayan.spector.bench.cognitive.model.BenchmarkQuery;
import com.spectrayan.spector.bench.cognitive.model.EntityMention;
import com.spectrayan.spector.bench.cognitive.model.EntityRelation;
import com.spectrayan.spector.bench.cognitive.model.HebbianEdgeDef;
import com.spectrayan.spector.bench.cognitive.model.PersonaDef;
import com.spectrayan.spector.bench.cognitive.model.TemporalChainDef;
import com.spectrayan.spector.memory.CognitiveProfile;
import com.spectrayan.spector.memory.MemoryType;
import com.spectrayan.spector.memory.graph.RelationType;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses all cognitive benchmark dataset files into typed Java records and validates
 * referential integrity on load.
 *
 * <p>The dataset directory is expected to contain the following files:</p>
 * <ul>
 *   <li>{@code corpus.jsonl} — JSONL corpus memories</li>
 *   <li>{@code queries.jsonl} — JSONL benchmark queries</li>
 *   <li>{@code qrels.tsv} — TSV relevance judgments</li>
 *   <li>{@code entities.jsonl} — JSONL entity relations</li>
 *   <li>{@code temporal_chains.jsonl} — JSONL temporal chain definitions</li>
 *   <li>{@code hebbian_edges.jsonl} — JSONL Hebbian co-activation edges</li>
 *   <li>{@code persona.json} — JSON user persona definition</li>
 * </ul>
 *
 * <p>After parsing, the loader validates referential integrity: all IDs referenced
 * in qrels, temporal chains, Hebbian edges, and entity relations must exist in the
 * corpus. Hebbian edges referencing missing IDs are skipped with a warning (per
 * Requirement 5.5). Other violations throw {@link DatasetValidationException}.</p>
 */
public final class DatasetLoader {

    private static final Logger log = LoggerFactory.getLogger(DatasetLoader.class);

    private static final float IMPORTANCE_MIN = 0.05f;
    private static final float IMPORTANCE_MAX = 10.0f;
    private static final int AROUSAL_MIN = 0;
    private static final int AROUSAL_MAX = 255;
    private static final int RECALL_COUNT_MIN = 0;

    private final ObjectMapper mapper;

    public DatasetLoader() {
        this.mapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /**
     * Aggregates all parsed dataset components into a single immutable record.
     *
     * @param corpus          parsed corpus memories from {@code corpus.jsonl}
     * @param queries         parsed benchmark queries from {@code queries.jsonl}
     * @param qrels           relevance judgments as queryId → (corpusId → grade)
     * @param entityRelations parsed entity relations from {@code entities.jsonl}
     * @param temporalChains  parsed temporal chain definitions from {@code temporal_chains.jsonl}
     * @param hebbianEdges    parsed Hebbian edges from {@code hebbian_edges.jsonl}
     * @param persona         parsed user persona from {@code persona.json}
     */
    public record LoadedDataset(
            List<BenchmarkCorpusRecord> corpus,
            List<BenchmarkQuery> queries,
            Map<String, Map<String, Integer>> qrels,
            List<EntityRelation> entityRelations,
            List<TemporalChainDef> temporalChains,
            List<HebbianEdgeDef> hebbianEdges,
            PersonaDef persona
    ) {}

    /**
     * Loads the complete cognitive benchmark dataset from a directory.
     *
     * <p>Parses all dataset files and validates referential integrity across them.
     * Hebbian edges referencing non-existent corpus IDs are silently skipped
     * (per Requirement 5.5); all other referential integrity violations cause
     * a {@link DatasetValidationException}.</p>
     *
     * @param datasetDir path to the directory containing all dataset files
     * @return the fully parsed and validated dataset
     * @throws DatasetValidationException if referential integrity checks fail
     * @throws DatasetParseException      if any file cannot be parsed
     */
    public LoadedDataset load(Path datasetDir) {
        List<BenchmarkCorpusRecord> corpus = loadCorpus(datasetDir.resolve("corpus.jsonl"));
        List<BenchmarkQuery> queries = loadQueries(datasetDir.resolve("queries.jsonl"));
        Map<String, Map<String, Integer>> qrels = loadQrels(datasetDir.resolve("qrels.tsv"));
        List<EntityRelation> entityRelations = loadEntities(datasetDir.resolve("entities.jsonl"));
        List<TemporalChainDef> temporalChains = loadTemporalChains(datasetDir.resolve("temporal_chains.jsonl"));
        List<HebbianEdgeDef> hebbianEdges = loadHebbianEdges(datasetDir.resolve("hebbian_edges.jsonl"));
        PersonaDef persona = loadPersona(datasetDir.resolve("persona.json"));

        List<HebbianEdgeDef> validatedEdges = validateReferentialIntegrity(
                corpus, queries, qrels, entityRelations, temporalChains, hebbianEdges);

        return new LoadedDataset(corpus, queries, qrels, entityRelations, temporalChains, validatedEdges, persona);
    }

    /**
     * Validates referential integrity across all dataset components.
     *
     * <p>Checks that all IDs referenced in qrels, temporal chains, entity relations,
     * and Hebbian edges exist in the corpus. For Hebbian edges, missing IDs cause
     * the edge to be skipped with a warning (per Requirement 5.5). All other
     * violations are collected and thrown as a {@link DatasetValidationException}.</p>
     *
     * @param corpus          the loaded corpus records
     * @param queries         the loaded benchmark queries
     * @param qrels           the loaded relevance judgments
     * @param entityRelations the loaded entity relations
     * @param temporalChains  the loaded temporal chain definitions
     * @param hebbianEdges    the loaded Hebbian edge definitions
     * @return the filtered list of Hebbian edges (edges with missing IDs removed)
     * @throws DatasetValidationException if any non-Hebbian referential integrity violations exist
     */
    private List<HebbianEdgeDef> validateReferentialIntegrity(
            List<BenchmarkCorpusRecord> corpus,
            List<BenchmarkQuery> queries,
            Map<String, Map<String, Integer>> qrels,
            List<EntityRelation> entityRelations,
            List<TemporalChainDef> temporalChains,
            List<HebbianEdgeDef> hebbianEdges) {

        List<String> violations = new ArrayList<>();

        // Build lookup sets
        Set<String> corpusIds = corpus.stream()
                .map(BenchmarkCorpusRecord::id)
                .collect(Collectors.toSet());

        Set<String> queryIds = queries.stream()
                .map(BenchmarkQuery::id)
                .collect(Collectors.toSet());

        // Build corpus timestamp lookup for temporal chain ordering validation
        Map<String, Long> corpusTimestamps = corpus.stream()
                .collect(Collectors.toMap(BenchmarkCorpusRecord::id, BenchmarkCorpusRecord::timestampMs));

        // 1. Check all query_ids in qrels exist in queries
        for (String qrelQueryId : qrels.keySet()) {
            if (!queryIds.contains(qrelQueryId)) {
                violations.add("qrels references query_id '%s' which does not exist in queries"
                        .formatted(qrelQueryId));
            }
        }

        // 2. Check all corpus_ids in qrels exist in corpus
        for (Map.Entry<String, Map<String, Integer>> entry : qrels.entrySet()) {
            for (String corpusId : entry.getValue().keySet()) {
                if (!corpusIds.contains(corpusId)) {
                    violations.add("qrels references corpus_id '%s' (for query '%s') which does not exist in corpus"
                            .formatted(corpusId, entry.getKey()));
                }
            }
        }

        // 3. Check temporal chain memory_ids exist in corpus AND are in ascending timestamp order
        for (TemporalChainDef chain : temporalChains) {
            long previousTimestamp = Long.MIN_VALUE;
            for (String memoryId : chain.orderedMemoryIds()) {
                if (!corpusIds.contains(memoryId)) {
                    violations.add("temporal chain (session '%s') references memory_id '%s' which does not exist in corpus"
                            .formatted(chain.sessionId(), memoryId));
                } else {
                    long timestamp = corpusTimestamps.get(memoryId);
                    if (timestamp < previousTimestamp) {
                        violations.add("temporal chain (session '%s') has memory_id '%s' (timestamp %d) out of ascending order (previous timestamp was %d)"
                                .formatted(chain.sessionId(), memoryId, timestamp, previousTimestamp));
                    }
                    previousTimestamp = timestamp;
                }
            }
        }

        // 4. Hebbian edges: skip edges with missing IDs (per Req 5.5) with logged warning
        List<HebbianEdgeDef> validEdges = new ArrayList<>(hebbianEdges.size());
        for (HebbianEdgeDef edge : hebbianEdges) {
            boolean aExists = corpusIds.contains(edge.memoryIdA());
            boolean bExists = corpusIds.contains(edge.memoryIdB());
            if (!aExists || !bExists) {
                log.warn("Skipping hebbian edge ({} ↔ {}): {} not found in corpus",
                        edge.memoryIdA(), edge.memoryIdB(),
                        !aExists && !bExists
                                ? "both memory_id_a and memory_id_b"
                                : (!aExists ? "memory_id_a '%s'".formatted(edge.memoryIdA())
                                            : "memory_id_b '%s'".formatted(edge.memoryIdB())));
            } else {
                validEdges.add(edge);
            }
        }

        // 5. Check entity relation source_memory_ids exist in corpus
        for (EntityRelation relation : entityRelations) {
            for (String sourceMemoryId : relation.sourceMemoryIds()) {
                if (!corpusIds.contains(sourceMemoryId)) {
                    violations.add("entity relation (%s → %s, type '%s') references source_memory_id '%s' which does not exist in corpus"
                            .formatted(relation.fromEntity().name(), relation.toEntity().name(),
                                    relation.relationType(), sourceMemoryId));
                }
            }
        }

        // Throw if violations exist
        if (!violations.isEmpty()) {
            throw new DatasetValidationException(violations);
        }

        return validEdges;
    }

    /**
     * Loads and parses the corpus from a {@code corpus.jsonl} file.
     *
     * <p>Uses streaming JSONL parsing via Jackson. Out-of-range field values are
     * clamped to valid boundaries with logged warnings.</p>
     *
     * @param corpusFile path to the {@code corpus.jsonl} file
     * @return list of parsed corpus records
     * @throws DatasetParseException if the file cannot be read or contains invalid JSON
     */
    public List<BenchmarkCorpusRecord> loadCorpus(Path corpusFile) {
        List<BenchmarkCorpusRecord> records = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(corpusFile)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) continue;
                try {
                    BenchmarkCorpusRecord record = parseCorpusLine(line, lineNumber, corpusFile);
                    records.add(record);
                } catch (Exception e) {
                    throw new DatasetParseException(
                            "Failed to parse corpus line %d in %s: %s".formatted(
                                    lineNumber, corpusFile, e.getMessage()), e);
                }
            }
        } catch (IOException e) {
            throw new DatasetParseException(
                    "Failed to read corpus file: " + corpusFile, e);
        }
        return records;
    }

    private BenchmarkCorpusRecord parseCorpusLine(String line, int lineNumber, Path file) {
        JsonNode node = mapper.readTree(line);

        String id = node.get("id").asText();
        String text = node.get("text").asText();
        String title = node.get("title").asText();

        // synaptic_tags → synapticTags
        List<String> synapticTags = new ArrayList<>();
        JsonNode tagsNode = node.get("synaptic_tags");
        if (tagsNode != null && tagsNode.isArray()) {
            for (JsonNode tag : tagsNode) {
                synapticTags.add(tag.asText());
            }
        }

        byte valence = (byte) node.get("valence").asInt();

        // importance: clamp to [0.05, 10.0]
        float importance = (float) node.get("importance").asDouble();
        if (importance < IMPORTANCE_MIN) {
            log.warn("Corpus line {} in {}: importance {} below minimum, clamping to {}",
                    lineNumber, file, importance, IMPORTANCE_MIN);
            importance = IMPORTANCE_MIN;
        } else if (importance > IMPORTANCE_MAX) {
            log.warn("Corpus line {} in {}: importance {} above maximum, clamping to {}",
                    lineNumber, file, importance, IMPORTANCE_MAX);
            importance = IMPORTANCE_MAX;
        }

        // arousal: clamp to [0, 255]
        int arousal = node.get("arousal").asInt();
        if (arousal < AROUSAL_MIN) {
            log.warn("Corpus line {} in {}: arousal {} below minimum, clamping to {}",
                    lineNumber, file, arousal, AROUSAL_MIN);
            arousal = AROUSAL_MIN;
        } else if (arousal > AROUSAL_MAX) {
            log.warn("Corpus line {} in {}: arousal {} above maximum, clamping to {}",
                    lineNumber, file, arousal, AROUSAL_MAX);
            arousal = AROUSAL_MAX;
        }

        // session_id → sessionId
        String sessionId = node.get("session_id").asText();

        // timestamp_ms → timestampMs
        long timestampMs = node.get("timestamp_ms").asLong();

        // entity_mentions → entityMentions
        List<EntityMention> entityMentions = new ArrayList<>();
        JsonNode mentionsNode = node.get("entity_mentions");
        if (mentionsNode != null && mentionsNode.isArray()) {
            for (JsonNode mention : mentionsNode) {
                String name = mention.get("name").asText();
                String type = mention.get("type").asText();
                entityMentions.add(new EntityMention(name, type));
            }
        }

        // memory_type → memoryType
        String memoryTypeStr = node.get("memory_type").asText();
        MemoryType memoryType = MemoryType.valueOf(memoryTypeStr);

        // recall_count → recallCount, clamp minimum to 0
        int recallCount = node.get("recall_count").asInt();
        if (recallCount < RECALL_COUNT_MIN) {
            log.warn("Corpus line {} in {}: recall_count {} below minimum, clamping to {}",
                    lineNumber, file, recallCount, RECALL_COUNT_MIN);
            recallCount = RECALL_COUNT_MIN;
        }

        return new BenchmarkCorpusRecord(
                id, text, title, synapticTags, valence, importance, arousal,
                sessionId, timestampMs, entityMentions, memoryType, recallCount
        );
    }

    /**
     * Loads and parses queries from a {@code queries.jsonl} file.
     *
     * <p>Uses streaming JSONL parsing via Jackson. Unknown {@code CognitiveProfile}
     * names default to {@code BALANCED} with a logged warning.</p>
     *
     * @param queriesFile path to the {@code queries.jsonl} file
     * @return list of parsed benchmark queries
     * @throws DatasetParseException if the file cannot be read or contains invalid JSON
     */
    public List<BenchmarkQuery> loadQueries(Path queriesFile) {
        List<BenchmarkQuery> queries = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(queriesFile)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) continue;
                try {
                    BenchmarkQuery query = parseQueryLine(line, lineNumber, queriesFile);
                    queries.add(query);
                } catch (Exception e) {
                    throw new DatasetParseException(
                            "Failed to parse query line %d in %s: %s".formatted(
                                    lineNumber, queriesFile, e.getMessage()), e);
                }
            }
        } catch (IOException e) {
            throw new DatasetParseException(
                    "Failed to read queries file: " + queriesFile, e);
        }
        return queries;
    }

    private BenchmarkQuery parseQueryLine(String line, int lineNumber, Path file) {
        JsonNode node = mapper.readTree(line);

        String id = node.get("id").asText();
        String text = node.get("text").asText();

        // cognitive_profile → cognitiveProfile, default to BALANCED for unknown values
        String profileStr = node.get("cognitive_profile").asText();
        CognitiveProfile cognitiveProfile;
        try {
            cognitiveProfile = CognitiveProfile.valueOf(profileStr);
        } catch (IllegalArgumentException e) {
            log.warn("Query line {} in {}: unknown cognitive_profile '{}', defaulting to BALANCED",
                    lineNumber, file, profileStr);
            cognitiveProfile = CognitiveProfile.BALANCED;
        }

        // synaptic_filter_tags → synapticFilterTags
        List<String> synapticFilterTags = new ArrayList<>();
        JsonNode tagsNode = node.get("synaptic_filter_tags");
        if (tagsNode != null && tagsNode.isArray()) {
            for (JsonNode tag : tagsNode) {
                synapticFilterTags.add(tag.asText());
            }
        }

        // min_valence → minValence (nullable)
        Byte minValence = null;
        JsonNode minValenceNode = node.get("min_valence");
        if (minValenceNode != null && !minValenceNode.isNull()) {
            minValence = (byte) minValenceNode.asInt();
        }

        // max_valence → maxValence (nullable)
        Byte maxValence = null;
        JsonNode maxValenceNode = node.get("max_valence");
        if (maxValenceNode != null && !maxValenceNode.isNull()) {
            maxValence = (byte) maxValenceNode.asInt();
        }

        // expected_subsystem → expectedSubsystem
        String expectedSubsystem = node.get("expected_subsystem").asText();

        // temporal_hint → temporalHint (nullable)
        String temporalHint = null;
        JsonNode temporalHintNode = node.get("temporal_hint");
        if (temporalHintNode != null && !temporalHintNode.isNull()) {
            temporalHint = temporalHintNode.asText();
        }

        return new BenchmarkQuery(
                id, text, cognitiveProfile, synapticFilterTags,
                minValence, maxValence, expectedSubsystem, temporalHint
        );
    }

    /**
     * Loads and parses relevance judgments from a {@code qrels.tsv} file.
     *
     * <p>Parses TSV with columns: query_id, corpus_id, relevance_grade.
     * Returns a nested map structure for efficient lookup during metric computation.</p>
     *
     * @param qrelsFile path to the {@code qrels.tsv} file
     * @return nested map of queryId → (corpusId → relevance grade)
     * @throws DatasetParseException if the file cannot be read or contains malformed TSV
     */
    public Map<String, Map<String, Integer>> loadQrels(Path qrelsFile) {
        Map<String, Map<String, Integer>> qrels = new java.util.LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(qrelsFile)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank() || line.startsWith("#")) continue;

                String[] columns = line.split("\t");

                // Skip header line
                if (columns.length > 0 && columns[0].equals("query_id")) continue;

                if (columns.length != 3) {
                    throw new DatasetParseException(
                            "Malformed qrels line %d in %s: expected 3 tab-separated columns but got %d"
                                    .formatted(lineNumber, qrelsFile, columns.length));
                }

                String queryId = columns[0].trim();
                String corpusId = columns[1].trim();
                int grade;
                try {
                    grade = Integer.parseInt(columns[2].trim());
                } catch (NumberFormatException e) {
                    throw new DatasetParseException(
                            "Failed to parse relevance grade on line %d in %s: '%s'"
                                    .formatted(lineNumber, qrelsFile, columns[2].trim()), e);
                }

                qrels.computeIfAbsent(queryId, k -> new java.util.LinkedHashMap<>())
                        .put(corpusId, grade);
            }
        } catch (IOException e) {
            throw new DatasetParseException(
                    "Failed to read qrels file: " + qrelsFile, e);
        }
        return qrels;
    }

    /**
     * Loads and parses entity relations from an {@code entities.jsonl} file.
     *
     * <p>Uses streaming JSONL parsing via Jackson. Unknown {@code RelationType}
     * values default to {@code OTHER} with a logged warning.</p>
     *
     * @param entitiesFile path to the {@code entities.jsonl} file
     * @return list of parsed entity relations
     * @throws DatasetParseException if the file cannot be read or contains invalid JSON
     */
    public List<EntityRelation> loadEntities(Path entitiesFile) {
        List<EntityRelation> relations = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(entitiesFile)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) continue;
                try {
                    EntityRelation relation = parseEntityLine(line, lineNumber, entitiesFile);
                    relations.add(relation);
                } catch (Exception e) {
                    throw new DatasetParseException(
                            "Failed to parse entity line %d in %s: %s".formatted(
                                    lineNumber, entitiesFile, e.getMessage()), e);
                }
            }
        } catch (IOException e) {
            throw new DatasetParseException(
                    "Failed to read entities file: " + entitiesFile, e);
        }
        return relations;
    }

    private EntityRelation parseEntityLine(String line, int lineNumber, Path file) {
        JsonNode node = mapper.readTree(line);

        // from_entity → fromEntity
        JsonNode fromNode = node.get("from_entity");
        EntityMention fromEntity = new EntityMention(
                fromNode.get("name").asText(),
                fromNode.get("type").asText()
        );

        // to_entity → toEntity
        JsonNode toNode = node.get("to_entity");
        EntityMention toEntity = new EntityMention(
                toNode.get("name").asText(),
                toNode.get("type").asText()
        );

        // relation_type → relationType, default to OTHER for unknown values
        String relationTypeStr = node.get("relation_type").asText();
        try {
            RelationType.valueOf(relationTypeStr);
        } catch (IllegalArgumentException e) {
            log.warn("Entity line {} in {}: unknown relation_type '{}', defaulting to OTHER",
                    lineNumber, file, relationTypeStr);
            relationTypeStr = RelationType.OTHER.name();
        }

        // source_memory_ids → sourceMemoryIds
        List<String> sourceMemoryIds = new ArrayList<>();
        JsonNode idsNode = node.get("source_memory_ids");
        if (idsNode != null && idsNode.isArray()) {
            for (JsonNode idNode : idsNode) {
                sourceMemoryIds.add(idNode.asText());
            }
        }

        return new EntityRelation(fromEntity, toEntity, relationTypeStr, sourceMemoryIds);
    }

    /**
     * Loads and parses temporal chain definitions from a {@code temporal_chains.jsonl} file.
     *
     * @param temporalChainsFile path to the {@code temporal_chains.jsonl} file
     * @return list of parsed temporal chain definitions
     * @throws DatasetParseException if the file cannot be read or contains invalid JSON
     */
    public List<TemporalChainDef> loadTemporalChains(Path temporalChainsFile) {
        List<TemporalChainDef> chains = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(temporalChainsFile)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) continue;
                try {
                    JsonNode node = mapper.readTree(line);

                    // session_id → sessionId
                    String sessionId = node.get("session_id").asText();

                    // ordered_memory_ids → orderedMemoryIds
                    List<String> orderedMemoryIds = new ArrayList<>();
                    JsonNode idsNode = node.get("ordered_memory_ids");
                    if (idsNode != null && idsNode.isArray()) {
                        for (JsonNode idNode : idsNode) {
                            orderedMemoryIds.add(idNode.asText());
                        }
                    }

                    chains.add(new TemporalChainDef(sessionId, orderedMemoryIds));
                } catch (Exception e) {
                    throw new DatasetParseException(
                            "Failed to parse temporal chain line %d in %s: %s".formatted(
                                    lineNumber, temporalChainsFile, e.getMessage()), e);
                }
            }
        } catch (IOException e) {
            throw new DatasetParseException(
                    "Failed to read temporal chains file: " + temporalChainsFile, e);
        }
        return chains;
    }

    /**
     * Loads and parses Hebbian edge definitions from a {@code hebbian_edges.jsonl} file.
     *
     * @param hebbianEdgesFile path to the {@code hebbian_edges.jsonl} file
     * @return list of parsed Hebbian edge definitions
     * @throws DatasetParseException if the file cannot be read or contains invalid JSON
     */
    public List<HebbianEdgeDef> loadHebbianEdges(Path hebbianEdgesFile) {
        List<HebbianEdgeDef> edges = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(hebbianEdgesFile)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) continue;
                try {
                    JsonNode node = mapper.readTree(line);

                    // memory_id_a → memoryIdA
                    String memoryIdA = node.get("memory_id_a").asText();

                    // memory_id_b → memoryIdB
                    String memoryIdB = node.get("memory_id_b").asText();

                    // co_activation_count → coActivationCount
                    int coActivationCount = node.get("co_activation_count").asInt();

                    edges.add(new HebbianEdgeDef(memoryIdA, memoryIdB, coActivationCount));
                } catch (Exception e) {
                    throw new DatasetParseException(
                            "Failed to parse hebbian edge line %d in %s: %s".formatted(
                                    lineNumber, hebbianEdgesFile, e.getMessage()), e);
                }
            }
        } catch (IOException e) {
            throw new DatasetParseException(
                    "Failed to read hebbian edges file: " + hebbianEdgesFile, e);
        }
        return edges;
    }

    /**
     * Loads and parses the user persona from a {@code persona.json} file.
     *
     * <p>Validates schema constraints: age in [18, 100], interests array with
     * 3–20 items, lifeContext 50–2000 characters, personalityTraits 3–10 items,
     * companionRelationship 50–500 characters.</p>
     *
     * @param personaFile path to the {@code persona.json} file
     * @return the parsed and validated persona definition
     * @throws DatasetParseException      if the file cannot be read or contains invalid JSON
     * @throws DatasetValidationException if schema constraints are violated
     */
    public PersonaDef loadPersona(Path personaFile) {
        try {
            String content = Files.readString(personaFile);
            JsonNode node = mapper.readTree(content);

            String name = node.get("name").asText();
            int age = node.get("age").asInt();
            String occupation = node.get("occupation").asText();

            // interests (array)
            List<String> interests = new ArrayList<>();
            JsonNode interestsNode = node.get("interests");
            if (interestsNode != null && interestsNode.isArray()) {
                for (JsonNode item : interestsNode) {
                    interests.add(item.asText());
                }
            }

            // life_context → lifeContext
            String lifeContext = node.get("life_context").asText();

            // personality_traits → personalityTraits
            List<String> personalityTraits = new ArrayList<>();
            JsonNode traitsNode = node.get("personality_traits");
            if (traitsNode != null && traitsNode.isArray()) {
                for (JsonNode item : traitsNode) {
                    personalityTraits.add(item.asText());
                }
            }

            // companion_relationship → companionRelationship
            String companionRelationship = node.get("companion_relationship").asText();

            // Schema validation
            List<String> violations = new ArrayList<>();

            if (age < 18 || age > 100) {
                violations.add("age must be between 18 and 100, got: " + age);
            }

            if (interests.size() < 3 || interests.size() > 20) {
                violations.add("interests must have 3-20 items, got: " + interests.size());
            }

            if (lifeContext.length() < 50 || lifeContext.length() > 2000) {
                violations.add("lifeContext must be 50-2000 characters, got: " + lifeContext.length());
            }

            if (personalityTraits.size() < 3 || personalityTraits.size() > 10) {
                violations.add("personalityTraits must have 3-10 items, got: " + personalityTraits.size());
            }

            if (companionRelationship.length() < 50 || companionRelationship.length() > 500) {
                violations.add("companionRelationship must be 50-500 characters, got: " + companionRelationship.length());
            }

            if (!violations.isEmpty()) {
                throw new DatasetValidationException(violations);
            }

            return new PersonaDef(name, age, occupation, interests, lifeContext,
                    personalityTraits, companionRelationship);

        } catch (DatasetValidationException e) {
            throw e;
        } catch (IOException e) {
            throw new DatasetParseException(
                    "Failed to read persona file: " + personaFile, e);
        } catch (Exception e) {
            throw new DatasetParseException(
                    "Failed to parse persona file %s: %s".formatted(personaFile, e.getMessage()), e);
        }
    }
}
