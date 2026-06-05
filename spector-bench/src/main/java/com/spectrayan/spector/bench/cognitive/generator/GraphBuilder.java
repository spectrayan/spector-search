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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.bench.cognitive.model.BenchmarkCorpusRecord;
import com.spectrayan.spector.bench.cognitive.model.EntityMention;
import com.spectrayan.spector.bench.cognitive.model.EntityRelation;
import com.spectrayan.spector.bench.cognitive.model.HebbianEdgeDef;
import com.spectrayan.spector.bench.cognitive.model.TemporalChainDef;

/**
 * Builds graph structures (entity relations, temporal chains, Hebbian edges) from annotated corpus.
 *
 * <p>Analyzes the annotated corpus records to extract structural relationships:</p>
 * <ul>
 *   <li><b>Entity graph</b> — typed relations between entities co-mentioned in memories</li>
 *   <li><b>Temporal chains</b> — session-based chronological ordering of memories</li>
 *   <li><b>Hebbian edges</b> — co-activation associations between related memories</li>
 * </ul>
 *
 * <h3>Construction Strategy</h3>
 * <p>Entity relations are inferred from co-occurrence of entity mentions within memories.
 * Temporal chains are built by grouping memories by session ID and ordering by timestamp.
 * Hebbian edges are generated based on shared synaptic tags and temporal proximity.</p>
 */
public final class GraphBuilder {

    private static final Logger log = LoggerFactory.getLogger(GraphBuilder.class);

    /** Minimum shared tags required to form a Hebbian edge. */
    private static final int MIN_SHARED_TAGS_FOR_EDGE = 2;

    /** Maximum co-activation count for Hebbian edges. */
    private static final int MAX_CO_ACTIVATION = 10;

    /**
     * Builds entity relations from entity mentions in the corpus.
     *
     * <p>For each pair of entities that co-occur within a memory, creates a directed
     * relation (from the first-mentioned to the second-mentioned). Relations are
     * deduplicated and backed by the source memory IDs where the co-occurrence appears.</p>
     *
     * @param corpus the annotated corpus records
     * @return list of entity relations
     */
    public List<EntityRelation> buildEntityGraph(List<BenchmarkCorpusRecord> corpus) {
        log.info("Building entity graph from {} corpus records", corpus.size());

        // Track entity pair → relation with source memories
        Map<String, EntityRelationBuilder> relationMap = new LinkedHashMap<>();

        for (BenchmarkCorpusRecord record : corpus) {
            List<EntityMention> entities = record.entityMentions();
            if (entities == null || entities.size() < 2) {
                continue;
            }

            // Create relations for all entity pairs within a memory
            for (int i = 0; i < entities.size(); i++) {
                for (int j = i + 1; j < entities.size(); j++) {
                    EntityMention from = entities.get(i);
                    EntityMention to = entities.get(j);
                    String key = from.name() + "|" + from.type() + "->" + to.name() + "|" + to.type();

                    relationMap.computeIfAbsent(key, k -> new EntityRelationBuilder(from, to))
                            .addSourceMemory(record.id());
                }
            }
        }

        List<EntityRelation> relations = relationMap.values().stream()
                .map(EntityRelationBuilder::build)
                .collect(Collectors.toList());

        log.info("Built {} entity relations", relations.size());
        return relations;
    }

    /**
     * Builds temporal chains by grouping memories by session and ordering by timestamp.
     *
     * <p>Each session with 2 or more memories produces a temporal chain with memories
     * ordered by ascending {@code timestampMs}.</p>
     *
     * @param corpus the annotated corpus records
     * @return list of temporal chain definitions
     */
    public List<TemporalChainDef> buildTemporalChains(List<BenchmarkCorpusRecord> corpus) {
        log.info("Building temporal chains from {} corpus records", corpus.size());

        // Group by sessionId
        Map<String, List<BenchmarkCorpusRecord>> sessionGroups = corpus.stream()
                .filter(r -> r.sessionId() != null && !r.sessionId().isBlank())
                .collect(Collectors.groupingBy(BenchmarkCorpusRecord::sessionId));

        List<TemporalChainDef> chains = new ArrayList<>();
        for (Map.Entry<String, List<BenchmarkCorpusRecord>> entry : sessionGroups.entrySet()) {
            List<BenchmarkCorpusRecord> sessionRecords = entry.getValue();
            if (sessionRecords.size() < 2) {
                continue;
            }

            // Sort by timestamp
            List<String> orderedIds = sessionRecords.stream()
                    .sorted(Comparator.comparingLong(BenchmarkCorpusRecord::timestampMs))
                    .map(BenchmarkCorpusRecord::id)
                    .collect(Collectors.toList());

            chains.add(new TemporalChainDef(entry.getKey(), orderedIds));
        }

        log.info("Built {} temporal chains", chains.size());
        return chains;
    }

    /**
     * Builds Hebbian co-activation edges between memories with shared context.
     *
     * <p>Edges are created between memory pairs that share at least
     * {@value #MIN_SHARED_TAGS_FOR_EDGE} synaptic tags. The co-activation count
     * is proportional to the number of shared tags, capped at {@value #MAX_CO_ACTIVATION}.</p>
     *
     * @param corpus the annotated corpus records
     * @return list of Hebbian edge definitions
     */
    public List<HebbianEdgeDef> buildHebbianEdges(List<BenchmarkCorpusRecord> corpus) {
        log.info("Building Hebbian edges from {} corpus records", corpus.size());

        // Index: tag → list of memory IDs
        Map<String, List<String>> tagIndex = new HashMap<>();

        for (BenchmarkCorpusRecord record : corpus) {
            if (record.synapticTags() == null || record.synapticTags().isEmpty()) {
                continue;
            }
            Set<String> tags = new HashSet<>(record.synapticTags());
            for (String tag : tags) {
                tagIndex.computeIfAbsent(tag, k -> new ArrayList<>()).add(record.id());
            }
        }

        // Build edges based on shared tags
        Map<String, Integer> edgeCounts = new LinkedHashMap<>();
        for (List<String> coMemories : tagIndex.values()) {
            if (coMemories.size() > 100) {
                // Skip overly common tags to avoid O(n²) explosion
                continue;
            }
            for (int i = 0; i < coMemories.size(); i++) {
                for (int j = i + 1; j < coMemories.size(); j++) {
                    String a = coMemories.get(i);
                    String b = coMemories.get(j);
                    String edgeKey = a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
                    edgeCounts.merge(edgeKey, 1, Integer::sum);
                }
            }
        }

        // Filter to edges with sufficient shared tags
        List<HebbianEdgeDef> edges = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : edgeCounts.entrySet()) {
            int sharedCount = entry.getValue();
            if (sharedCount >= MIN_SHARED_TAGS_FOR_EDGE) {
                String[] parts = entry.getKey().split("\\|");
                int coActivation = Math.min(sharedCount, MAX_CO_ACTIVATION);
                edges.add(new HebbianEdgeDef(parts[0], parts[1], coActivation));
            }
        }

        log.info("Built {} Hebbian edges", edges.size());
        return edges;
    }

    // ─────────────── Internal builder ───────────────

    private static class EntityRelationBuilder {
        private final EntityMention from;
        private final EntityMention to;
        private final List<String> sourceMemoryIds = new ArrayList<>();

        EntityRelationBuilder(EntityMention from, EntityMention to) {
            this.from = from;
            this.to = to;
        }

        void addSourceMemory(String memoryId) {
            if (!sourceMemoryIds.contains(memoryId)) {
                sourceMemoryIds.add(memoryId);
            }
        }

        EntityRelation build() {
            String relationType = inferRelationType(from, to);
            return new EntityRelation(from, to, relationType, List.copyOf(sourceMemoryIds));
        }

        /**
         * Infers relation type based on entity types.
         */
        private static String inferRelationType(EntityMention from, EntityMention to) {
            String fromType = from.type().toUpperCase();
            String toType = to.type().toUpperCase();

            if ("PERSON".equals(fromType) && "ORGANIZATION".equals(toType)) return "WORKS_ON";
            if ("PERSON".equals(fromType) && "SOFTWARE".equals(toType)) return "USES";
            if ("PERSON".equals(fromType) && "PERSON".equals(toType)) return "KNOWS";
            if ("SOFTWARE".equals(fromType) && "SOFTWARE".equals(toType)) return "DEPENDS_ON";
            if ("PERSON".equals(fromType) && "LOCATION".equals(toType)) return "LOCATED_AT";
            if ("SOFTWARE".equals(fromType) && "CONCEPT".equals(toType)) return "IMPLEMENTS";
            return "RELATED_TO";
        }
    }
}
