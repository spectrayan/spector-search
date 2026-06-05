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
package com.spectrayan.spector.bench.cognitive.generator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.bench.cognitive.model.BenchmarkCorpusRecord;
import com.spectrayan.spector.bench.cognitive.model.BenchmarkQuery;
import com.spectrayan.spector.bench.cognitive.model.PersonaDef;
import com.spectrayan.spector.bench.cognitive.model.RelevanceJudgment;
import com.spectrayan.spector.memory.CognitiveProfile;

import tools.jackson.core.type.TypeReference;

/**
 * Generates benchmark queries with graded relevance judgments from the annotated corpus.
 *
 * <p>Creates queries targeting all expected subsystem types (TAG_GATING, VALENCE_FILTER,
 * IMPORTANCE_DECAY, HEBBIAN_GRAPH, TEMPORAL_CHAIN, ENTITY_GRAPH, VECTOR_SIMILARITY)
 * and distributes them across cognitive profiles to ensure comprehensive coverage.</p>
 *
 * <h3>Query Generation Strategy</h3>
 * <ul>
 *   <li>For each subsystem type, generate queries where that subsystem should be the
 *       primary differentiator between cognitive and baseline retrieval</li>
 *   <li>Assign graded relevance: 3=highly relevant, 2=relevant, 1=marginally relevant, 0=irrelevant</li>
 *   <li>Distribute across CognitiveProfile enum values</li>
 *   <li>Include temporal hints (RECENT, OLD) where appropriate</li>
 * </ul>
 */
public final class QueryGenerator {

    private static final Logger log = LoggerFactory.getLogger(QueryGenerator.class);

    /** Expected subsystem types for which queries must be generated. */
    private static final List<String> SUBSYSTEM_TYPES = List.of(
            "VECTOR_SIMILARITY", "TAG_GATING", "VALENCE_FILTER",
            "IMPORTANCE_DECAY", "HEBBIAN_GRAPH", "TEMPORAL_CHAIN", "ENTITY_GRAPH"
    );

    private static final String SYSTEM_PROMPT = """
            You are a query generator for a cognitive memory benchmark. Generate natural queries
            that a user would ask their AI companion when trying to recall specific memories.
            
            For each query, provide:
            - "text": the query in natural language (10-100 chars, first person)
            - "cognitive_profile": one of BALANCED, DEBUGGING, CREATIVE_FLOW, EMOTIONAL_SUPPORT,
              LEARNING, PLANNING, SOCIAL, HEALTH, WORK_FOCUS, REFLECTION, EXPLORATION, DEFAULT_MODE_NETWORK
            - "synaptic_filter_tags": 0-5 tags that should filter results (can be empty)
            - "min_valence": integer or null (minimum valence filter)
            - "max_valence": integer or null (maximum valence filter)
            - "temporal_hint": "RECENT", "OLD", or null
            - "relevant_memory_ids": array of corpus IDs that are highly relevant (grade 3)
            - "partially_relevant_ids": array of corpus IDs that are partially relevant (grade 2)
            - "marginally_relevant_ids": array of corpus IDs that are marginally relevant (grade 1)
            
            Respond ONLY with a valid JSON array. No markdown, no explanation.
            """;

    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE =
            new TypeReference<>() {};

    private final OllamaCompletionClient client;
    private final PersonaDef persona;

    /**
     * Creates a query generator.
     *
     * @param client  the Ollama completion client for LLM generation
     * @param persona the persona definition providing query context
     */
    public QueryGenerator(OllamaCompletionClient client, PersonaDef persona) {
        this.client = client;
        this.persona = persona;
    }

    /**
     * Generates queries with relevance judgments covering all subsystem types.
     *
     * @param corpus the annotated corpus records available for relevance linking
     * @return a result containing generated queries and their relevance judgments
     */
    public QueryResult generate(List<BenchmarkCorpusRecord> corpus) {
        log.info("Generating queries for {} corpus records across {} subsystem types",
                corpus.size(), SUBSYSTEM_TYPES.size());

        List<BenchmarkQuery> allQueries = new ArrayList<>();
        List<RelevanceJudgment> allJudgments = new ArrayList<>();
        int queryIdCounter = 1;

        for (String subsystem : SUBSYSTEM_TYPES) {
            int queriesPerSubsystem = Math.max(5, 200 / SUBSYSTEM_TYPES.size());
            List<BenchmarkCorpusRecord> relevantCorpus = selectRelevantCorpus(corpus, subsystem);

            if (relevantCorpus.isEmpty()) {
                log.warn("No relevant corpus found for subsystem {}, skipping", subsystem);
                continue;
            }

            try {
                String userPrompt = buildSubsystemPrompt(subsystem, relevantCorpus, queriesPerSubsystem);
                List<Map<String, Object>> rawQueries = client.completeAsJson(SYSTEM_PROMPT, userPrompt, LIST_MAP_TYPE);

                for (Map<String, Object> raw : rawQueries) {
                    String queryId = String.format("q-%03d", queryIdCounter++);
                    BenchmarkQuery query = parseQuery(raw, queryId, subsystem);
                    allQueries.add(query);

                    // Extract relevance judgments
                    List<RelevanceJudgment> judgments = parseJudgments(raw, queryId);
                    allJudgments.addAll(judgments);
                }
            } catch (OllamaCompletionException e) {
                log.warn("Query generation failed for subsystem {}: {}", subsystem, e.getMessage());
            }
        }

        log.info("Generated {} queries with {} relevance judgments", allQueries.size(), allJudgments.size());
        return new QueryResult(allQueries, allJudgments);
    }

    /**
     * Result of query generation containing queries and their relevance judgments.
     *
     * @param queries    the generated benchmark queries
     * @param judgments  relevance judgments linking queries to corpus records
     */
    public record QueryResult(List<BenchmarkQuery> queries, List<RelevanceJudgment> judgments) {}

    // ─────────────── Private helpers ───────────────

    private List<BenchmarkCorpusRecord> selectRelevantCorpus(List<BenchmarkCorpusRecord> corpus,
                                                             String subsystem) {
        // Select a subset of corpus records relevant to the subsystem type
        return switch (subsystem) {
            case "TAG_GATING" -> corpus.stream()
                    .filter(r -> r.synapticTags() != null && !r.synapticTags().isEmpty())
                    .limit(50).toList();
            case "VALENCE_FILTER" -> corpus.stream()
                    .filter(r -> r.valence() != 0)
                    .limit(50).toList();
            case "IMPORTANCE_DECAY" -> corpus.stream()
                    .filter(r -> r.importance() > 3.0f)
                    .limit(50).toList();
            case "TEMPORAL_CHAIN" -> corpus.stream()
                    .filter(r -> r.sessionId() != null && !r.sessionId().isBlank())
                    .limit(50).toList();
            case "ENTITY_GRAPH" -> corpus.stream()
                    .filter(r -> r.entityMentions() != null && r.entityMentions().size() >= 2)
                    .limit(50).toList();
            case "HEBBIAN_GRAPH" -> corpus.stream()
                    .filter(r -> r.synapticTags() != null && r.synapticTags().size() >= 3)
                    .limit(50).toList();
            default -> corpus.stream().limit(50).toList();
        };
    }

    private String buildSubsystemPrompt(String subsystem, List<BenchmarkCorpusRecord> corpus, int count) {
        StringBuilder sb = new StringBuilder();
        sb.append("Generate ").append(count).append(" queries targeting the ").append(subsystem);
        sb.append(" subsystem for this persona.\n\n");
        sb.append("Persona: ").append(persona.name()).append(", ")
          .append(persona.age()).append("yo ").append(persona.occupation()).append("\n\n");

        sb.append("Available corpus memories (use their IDs for relevance judgments):\n");
        int limit = Math.min(20, corpus.size());
        for (int i = 0; i < limit; i++) {
            BenchmarkCorpusRecord r = corpus.get(i);
            sb.append("- ").append(r.id()).append(": [").append(r.title()).append("] ");
            sb.append(truncate(r.text(), 80));
            if (r.synapticTags() != null && !r.synapticTags().isEmpty()) {
                sb.append(" tags=").append(r.synapticTags());
            }
            sb.append("\n");
        }

        sb.append("\nSubsystem guidance for ").append(subsystem).append(":\n");
        switch (subsystem) {
            case "TAG_GATING" -> sb.append("Queries should use synaptic_filter_tags to narrow results. "
                    + "Relevant memories share the specified tags.");
            case "VALENCE_FILTER" -> sb.append("Queries should specify min/max_valence to filter by emotion. "
                    + "E.g., only positive memories, or only negative/frustrating ones.");
            case "IMPORTANCE_DECAY" -> sb.append("Queries should target important memories that would rank "
                    + "higher due to their significance in the persona's life.");
            case "HEBBIAN_GRAPH" -> sb.append("Queries should trigger associative recall — asking about "
                    + "topics that connect multiple co-activated memories.");
            case "TEMPORAL_CHAIN" -> sb.append("Queries should ask about sequences of events within sessions. "
                    + "Set temporal_hint to RECENT or OLD as appropriate.");
            case "ENTITY_GRAPH" -> sb.append("Queries should ask about relationships between people, "
                    + "software, or organizations mentioned in memories.");
            case "VECTOR_SIMILARITY" -> sb.append("Queries should be semantically similar to target memories "
                    + "but without using exact tag/valence/entity filters.");
            default -> sb.append("General queries.");
        }

        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private BenchmarkQuery parseQuery(Map<String, Object> raw, String queryId, String subsystem) {
        String text = getStringOr(raw, "text", "What do I remember about this?");
        String profileStr = getStringOr(raw, "cognitive_profile", "BALANCED");
        CognitiveProfile profile = parseCognitiveProfile(profileStr);
        List<String> filterTags = parseStringList(raw.get("synaptic_filter_tags"));
        Byte minValence = parseNullableByte(raw.get("min_valence"));
        Byte maxValence = parseNullableByte(raw.get("max_valence"));
        String temporalHint = getStringOr(raw, "temporal_hint", null);

        if (temporalHint != null && !temporalHint.equals("RECENT") && !temporalHint.equals("OLD")) {
            temporalHint = null;
        }

        return new BenchmarkQuery(queryId, text, profile, filterTags,
                minValence, maxValence, subsystem, temporalHint);
    }

    @SuppressWarnings("unchecked")
    private List<RelevanceJudgment> parseJudgments(Map<String, Object> raw, String queryId) {
        List<RelevanceJudgment> judgments = new ArrayList<>();

        addJudgments(judgments, queryId, parseStringList(raw.get("relevant_memory_ids")), 3);
        addJudgments(judgments, queryId, parseStringList(raw.get("partially_relevant_ids")), 2);
        addJudgments(judgments, queryId, parseStringList(raw.get("marginally_relevant_ids")), 1);

        return judgments;
    }

    private static void addJudgments(List<RelevanceJudgment> judgments, String queryId,
                                      List<String> memoryIds, int grade) {
        for (String memId : memoryIds) {
            if (memId != null && !memId.isBlank()) {
                judgments.add(new RelevanceJudgment(queryId, memId, grade));
            }
        }
    }

    private static CognitiveProfile parseCognitiveProfile(String raw) {
        try {
            return CognitiveProfile.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return CognitiveProfile.BALANCED;
        }
    }

    private static Byte parseNullableByte(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number n) return (byte) Math.max(-128, Math.min(127, n.intValue()));
        if (raw instanceof String s) {
            if (s.isBlank() || "null".equalsIgnoreCase(s)) return null;
            try { return (byte) Math.max(-128, Math.min(127, Integer.parseInt(s))); }
            catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> parseStringList(Object raw) {
        if (raw instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof String s && !s.isBlank()) {
                    result.add(s);
                }
            }
            return result;
        }
        return List.of();
    }

    private static String getStringOr(Map<String, Object> map, String key, String defaultValue) {
        Object val = map.get(key);
        return val instanceof String s && !s.isBlank() ? s : defaultValue;
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
