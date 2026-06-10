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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.bench.cognitive.model.BenchmarkCorpusRecord;
import com.spectrayan.spector.bench.cognitive.model.EntityRelation;
import com.spectrayan.spector.bench.cognitive.model.HebbianEdgeDef;
import com.spectrayan.spector.bench.cognitive.model.TemporalChainDef;
import com.spectrayan.spector.embed.EmbeddingProvider;
import com.spectrayan.spector.memory.DefaultSpectorMemory;
import com.spectrayan.spector.memory.model.MemoryPersistenceMode;
import com.spectrayan.spector.memory.SpectorMemory;
import com.spectrayan.spector.memory.cortex.MemorySource;
import com.spectrayan.spector.memory.graph.EntityGraph;
import com.spectrayan.spector.memory.graph.EntityExtractionMode;
import com.spectrayan.spector.memory.graph.EntityType;
import com.spectrayan.spector.memory.graph.RelationType;
import com.spectrayan.spector.memory.hebbian.HebbianGraph;
import com.spectrayan.spector.memory.neurodivergent.IngestionHints;
import com.spectrayan.spector.memory.temporal.TemporalChain;

/**
 * Bootstraps a {@link SpectorMemory} instance populated with the benchmark corpus.
 *
 * <p>Configures graphs (Hebbian, Temporal, Entity) from dataset definitions.
 * Uses {@code RecallMode.OBSERVE} semantics — the memory instance is read-only
 * after setup completes, preventing side effects during benchmark queries.</p>
 *
 * <p>Implements {@link AutoCloseable} to ensure off-heap resources (Arena-backed
 * MemorySegments in HebbianGraph, TemporalChain, EntityGraph) are properly released.</p>
 */
public final class BenchmarkSetup implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkSetup.class);

    private SpectorMemory memory;
    private Map<String, Integer> idToSlot;

    /**
     * Creates a fully-populated memory instance from the dataset.
     *
     * <p>Ingests all corpus records using the provided embedding provider,
     * then loads Hebbian edges, temporal chains, and entity relations from
     * the dataset definitions.</p>
     *
     * @param dataset  the loaded and validated benchmark dataset
     * @param embedder the embedding provider for vectorizing corpus text
     * @return a fully configured SpectorMemory instance ready for benchmarking
     */
    public SpectorMemory createMemoryInstance(DatasetLoader.LoadedDataset dataset,
                                              EmbeddingProvider embedder) {
        List<BenchmarkCorpusRecord> corpus = dataset.corpus();
        int corpusSize = corpus.size();

        log.info("Creating benchmark memory instance: {} corpus records, {} dimensions",
                corpusSize, embedder.dimensions());

        // Build in-memory SpectorMemory with sufficient capacity
        memory = DefaultSpectorMemory.builder()
                .dimensions(embedder.dimensions())
                .embeddingProvider(embedder)
                .persistenceMode(MemoryPersistenceMode.IN_MEMORY)
                .workingCapacity(Math.max(50, corpusSize / 10))
                .episodicPartitionCapacity(corpusSize + 100)
                .semanticCapacity(corpusSize + 100)
                .proceduralCapacity(Math.max(50, corpusSize / 5))
                .hebbianGraphCapacity(corpusSize + 100)
                .temporalChainCapacity(corpusSize + 100)
                .entityGraphCapacity(Math.max(200, corpusSize))
                .entityExtractionMode(EntityExtractionMode.CUSTOM)
                .build();

        // Ingest all corpus records and build idToSlot mapping
        Map<String, Integer> idToSlot = new LinkedHashMap<>(corpusSize);
        int slot = 0;
        for (BenchmarkCorpusRecord record : corpus) {
            try {
                IngestionHints hints = new IngestionHints(
                        record.interest(), record.challenge(), record.urgency(),
                        record.valence(),
                        (byte) record.arousal()
                );

                // Use IngestionContext to pass the corpus record's original timestamp
                // into the cognitive header, preserving temporal accuracy for decay and
                // temporal chain ordering across the 180-day benchmark span.
                var context = com.spectrayan.spector.memory.model.IngestionContext.builder()
                        .hints(hints)
                        .overrideTimestampMs(record.timestampMs())
                        .build();

                memory.remember(
                        record.id(),
                        record.text(),
                        record.memoryType(),
                        MemorySource.OBSERVED,
                        context,
                        record.synapticTags().toArray(String[]::new)
                ).get(30, TimeUnit.SECONDS);

                idToSlot.put(record.id(), slot);
                slot++;
            } catch (Exception e) {
                log.warn("Failed to ingest corpus record '{}': {}", record.id(), e.getMessage());
            }
        }

        log.info("Ingested {} of {} corpus records", idToSlot.size(), corpusSize);

        // Store for external access (subsystem contribution detection)
        this.idToSlot = idToSlot;

        // Load graph structures from dataset definitions (null-safe: subsystems may be unconfigured)
        if (memory.hebbianGraph() != null) {
            loadHebbianEdges(memory.hebbianGraph(), dataset.hebbianEdges(), idToSlot);
        } else {
            log.warn("HebbianGraph is null — skipping {} edge definitions", dataset.hebbianEdges().size());
        }
        if (memory.temporalChain() != null) {
            loadTemporalChains(memory.temporalChain(), dataset.temporalChains(), idToSlot);
        } else {
            log.warn("TemporalChain is null — skipping {} chain definitions", dataset.temporalChains().size());
        }
        if (memory.entityGraph() != null) {
            loadEntityGraph(memory.entityGraph(), dataset.entityRelations(), corpus);
        } else {
            log.warn("EntityGraph is null — skipping {} entity relation definitions", dataset.entityRelations().size());
        }

        log.info("Benchmark memory setup complete: hebbian edges={}, temporal chains={}, entity relations={}",
                dataset.hebbianEdges().size(), dataset.temporalChains().size(),
                dataset.entityRelations().size());

        return memory;
    }

    /**
     * Populates the HebbianGraph from hebbian_edges.jsonl definitions.
     *
     * <p>Creates bidirectional weighted edges between memory slots. Edges referencing
     * memory IDs not present in the idToSlot mapping are silently skipped with a
     * logged warning (per Requirement 5.5).</p>
     *
     * @param graph    the Hebbian graph to populate
     * @param edges    edge definitions from the dataset
     * @param idToSlot mapping from corpus record IDs to their slot indices
     */
    void loadHebbianEdges(HebbianGraph graph, List<HebbianEdgeDef> edges,
                          Map<String, Integer> idToSlot) {
        int loaded = 0;
        int skipped = 0;

        for (HebbianEdgeDef edge : edges) {
            Integer slotA = idToSlot.get(edge.memoryIdA());
            Integer slotB = idToSlot.get(edge.memoryIdB());

            if (slotA == null || slotB == null) {
                skipped++;
                log.warn("Skipping Hebbian edge: missing ID(s) — A='{}' ({}), B='{}' ({})",
                        edge.memoryIdA(), slotA != null ? "found" : "MISSING",
                        edge.memoryIdB(), slotB != null ? "found" : "MISSING");
                continue;
            }

            // Strengthen the edge with weight derived from co-activation count.
            // HebbianGraph.strengthen() already creates bidirectional edges.
            float weight = (float) edge.coActivationCount();
            graph.strengthen(slotA, slotB, weight);
            loaded++;
        }

        log.info("Loaded {} Hebbian edges ({} skipped due to missing IDs)", loaded, skipped);
    }

    /**
     * Populates the TemporalChain from temporal_chains.jsonl definitions.
     *
     * <p>Establishes doubly-linked lists for each session chain in the specified order.
     * Each consecutive pair of memory IDs in a chain's orderedMemoryIds is linked,
     * forming a forward/backward traversable chain.</p>
     *
     * @param chain    the temporal chain to populate
     * @param chains   chain definitions from the dataset
     * @param idToSlot mapping from corpus record IDs to their slot indices
     */
    void loadTemporalChains(TemporalChain chain, List<TemporalChainDef> chains,
                            Map<String, Integer> idToSlot) {
        int linkedCount = 0;
        int skipped = 0;

        for (TemporalChainDef chainDef : chains) {
            List<String> orderedIds = chainDef.orderedMemoryIds();
            int sessionHash = chainDef.sessionId().hashCode();

            Integer previousSlot = null;
            for (String memoryId : orderedIds) {
                Integer currentSlot = idToSlot.get(memoryId);
                if (currentSlot == null) {
                    log.warn("Skipping temporal link: memory ID '{}' not found in session '{}'",
                            memoryId, chainDef.sessionId());
                    skipped++;
                    previousSlot = null; // break the chain at missing nodes
                    continue;
                }

                if (previousSlot != null) {
                    // link(currentIdx, previousIdx, sessionId) creates bidirectional links
                    chain.link(currentSlot, previousSlot, sessionHash);
                    linkedCount++;
                }
                previousSlot = currentSlot;
            }
        }

        log.info("Loaded {} temporal links across {} chains ({} skipped)",
                linkedCount, chains.size(), skipped);
    }

    /**
     * Populates the EntityGraph from entities.jsonl definitions.
     *
     * <p>Constructs typed entity nodes and typed edges matching specified relation types.
     * Links entities to their source memory indices based on the sourceMemoryIds field.</p>
     *
     * @param graph     the entity graph to populate
     * @param relations entity relation definitions from the dataset
     * @param corpus    the corpus records (used for entity mention → memory linking)
     */
    void loadEntityGraph(EntityGraph graph, List<EntityRelation> relations,
                         List<BenchmarkCorpusRecord> corpus) {
        // Build a lookup from memory ID to corpus index
        Map<String, Integer> idToIndex = new HashMap<>(corpus.size());
        for (int i = 0; i < corpus.size(); i++) {
            idToIndex.put(corpus.get(i).id(), i);
        }

        int relationsLoaded = 0;

        for (EntityRelation relation : relations) {
            // Pass type strings directly — TypeRegistry auto-registers unknown types
            int fromEntityId = graph.addEntity(relation.fromEntity().name(), relation.fromEntity().type());
            if (fromEntityId < 0) {
                log.warn("Failed to add from-entity '{}' to graph", relation.fromEntity().name());
                continue;
            }

            int toEntityId = graph.addEntity(relation.toEntity().name(), relation.toEntity().type());
            if (toEntityId < 0) {
                log.warn("Failed to add to-entity '{}' to graph", relation.toEntity().name());
                continue;
            }

            // Add the typed relation
            graph.addRelation(fromEntityId, toEntityId, relation.relationType());
            relationsLoaded++;

            // Link entities to their source memories
            for (String memoryId : relation.sourceMemoryIds()) {
                Integer memIdx = idToIndex.get(memoryId);
                if (memIdx != null) {
                    graph.linkEntityToMemory(fromEntityId, memIdx);
                    graph.linkEntityToMemory(toEntityId, memIdx);
                } else {
                    log.warn("Entity relation source memory '{}' not found in corpus", memoryId);
                }
            }
        }

        log.info("Loaded {} entity relations into graph (entities={})",
                relationsLoaded, graph.entityCount());
    }

    /**
     * Releases all off-heap resources held by the SpectorMemory instance.

     *
     * <p>Closes the underlying SpectorMemory which in turn releases Arena-backed
     * MemorySegments for HebbianGraph, TemporalChain, EntityGraph, tier stores,
     * and other subsystems.</p>
     */
    @Override
    public void close() {
        if (memory != null) {
            log.info("Closing benchmark memory instance");
            memory.close();
            memory = null;
        }
    }

    /**
     * Returns the currently active SpectorMemory instance, or null if not yet created or closed.
     */
    public SpectorMemory memory() {
        return memory;
    }

    /**
     * Returns the mapping from corpus record IDs to their slot indices in the
     * off-heap structures (HebbianGraph, TemporalChain).
     *
     * <p>This mapping is populated during {@link #createMemoryInstance} and is
     * needed by {@link ContributingSubsystem#detect} for graph reachability checks.</p>
     *
     * @return unmodifiable view of the ID-to-slot mapping, or empty map if not yet created
     */
    public Map<String, Integer> idToSlot() {
        return idToSlot != null ? java.util.Collections.unmodifiableMap(idToSlot) : Map.of();
    }
}
