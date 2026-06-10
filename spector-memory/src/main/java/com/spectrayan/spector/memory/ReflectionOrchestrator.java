/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Business Source License 1.1 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://github.com/spectrayan/spector/blob/main/spector-memory/LICENSE
 *
 * Change Date: May 27, 2030
 * Change License: Apache License, Version 2.0
 */
package com.spectrayan.spector.memory;

import com.spectrayan.spector.memory.cortex.TierRouter;
import com.spectrayan.spector.memory.error.SpectorGraphDecayException;
import com.spectrayan.spector.memory.graph.EntityGraph;
// RelationType enum replaced by open-schema strings via TypeRegistry
import com.spectrayan.spector.memory.hebbian.HebbianGraph;
import com.spectrayan.spector.memory.hippocampus.ReflectDaemon;
import com.spectrayan.spector.memory.index.MemoryIndex;
import com.spectrayan.spector.memory.model.MemoryType;
import com.spectrayan.spector.memory.model.ReflectReport;
import com.spectrayan.spector.memory.sync.MemoryWal;
import com.spectrayan.spector.memory.sync.WalEvent;
import com.spectrayan.spector.memory.temporal.TemporalChain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates sleep consolidation (reflection) cycles.
 *
 * <p>Coordinates the following phases during a single {@link #reflect} call:</p>
 * <ol>
 *   <li><b>REM cycle</b> — delegates to {@link ReflectDaemon} for episodic→semantic consolidation</li>
 *   <li><b>Hebbian decay</b> — decays weak co-activation edges (synaptic homeostasis)</li>
 *   <li><b>Temporal pruning</b> — removes causal links older than the retention window</li>
 *   <li><b>Cross-layer promotion</b> — promotes strong Hebbian edges into entity RELATED_TO relations</li>
 *   <li><b>Entity maintenance</b> — decays entity edges and merges near-duplicate entities</li>
 * </ol>
 *
 * <p>Thread-safe: individual subsystem operations are thread-safe; the orchestrator
 * itself does not maintain mutable state.</p>
 */
final class ReflectionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ReflectionOrchestrator.class);

    /** Minimum Hebbian weight to qualify for cross-layer promotion to entity graph. */
    private static final float HEBBIAN_PROMOTION_MIN_WEIGHT = 3.0f;

    /** Hebbian decay factor per reflection cycle (10% decay = multiply by 0.9). */
    private static final float HEBBIAN_DECAY_FACTOR = 0.9f;

    /** Entity edge decay factor per cycle (5% decay). */
    private static final float ENTITY_DECAY_FACTOR = 0.95f;

    /** Entity edge pruning threshold (edges below this weight are removed). */
    private static final float ENTITY_PRUNE_THRESHOLD = 0.5f;

    /** Levenshtein distance threshold for merging near-duplicate entities. */
    private static final int ENTITY_MERGE_DISTANCE = 2;

    private final ReflectDaemon reflectDaemon;
    private final HebbianGraph hebbianGraph;
    private final TemporalChain temporalChain;
    private final EntityGraph entityGraph;
    private final MemoryWal wal;
    private final int temporalRetentionDays;

    ReflectionOrchestrator(ReflectDaemon reflectDaemon,
                           HebbianGraph hebbianGraph,
                           TemporalChain temporalChain,
                           EntityGraph entityGraph,
                           MemoryWal wal,
                           int temporalRetentionDays) {
        this.reflectDaemon = reflectDaemon;
        this.hebbianGraph = hebbianGraph;
        this.temporalChain = temporalChain;
        this.entityGraph = entityGraph;
        this.wal = wal;
        this.temporalRetentionDays = temporalRetentionDays;
    }

    /**
     * Runs a full reflection cycle: REM consolidation, graph decay, temporal pruning,
     * cross-layer promotion, and entity maintenance.
     *
     * @param tierRouter the current tier router
     * @param index      the memory index (for text lookups during consolidation)
     * @return a {@link ReflectReport} summarizing what was consolidated, pruned, and promoted
     */
    ReflectReport reflect(TierRouter tierRouter, MemoryIndex index) {
        log.info("Manual reflection triggered");

        // Phase 1: REM cycle — episodic → semantic consolidation
        var semanticTarget = tierRouter.semantic();
        ReflectReport daemonReport = reflectDaemon.runCycle(
                tierRouter.episodic(), semanticTarget,
                offset -> index.findTextByOffset(MemoryType.EPISODIC, offset));

        // Phase 2: Hebbian decay (synaptic homeostasis)
        decayHebbianEdges();

        // Phase 3: Temporal chain pruning
        int temporalPruned = pruneTemporalChain();

        // Phase 4: Cross-layer promotion (Hebbian → Entity)
        promoteCrossLayer();

        // Phase 5: Entity graph maintenance
        maintainEntityGraph();

        // Append WAL event
        wal.append(WalEvent.EventType.REFLECT, "system", null);

        // Overlay temporal pruning count onto the daemon's report
        return new ReflectReport(
                daemonReport.consolidatedCount(), daemonReport.tombstonedCount(),
                daemonReport.compactedPartitions(), temporalPruned, daemonReport.duration());
    }

    // ── Phase 2: Hebbian Decay ──

    private void decayHebbianEdges() {
        try {
            int decayed = hebbianGraph.decayEdges(HEBBIAN_DECAY_FACTOR);
            if (decayed > 0) {
                log.info("Reflect: Hebbian graph decayed {} weak edges", decayed);
            }
        } catch (RuntimeException e) {
            SpectorGraphDecayException ex = new SpectorGraphDecayException("Hebbian edge decay", e);
            log.warn(ex.getMessage());
        }
    }

    // ── Phase 3: Temporal Pruning ──

    private int pruneTemporalChain() {
        if (temporalChain == null) return 0;
        try {
            long cutoffMs = System.currentTimeMillis()
                    - (long) temporalRetentionDays * 24 * 60 * 60 * 1000;
            return temporalChain.pruneOlderThan(cutoffMs);
        } catch (RuntimeException e) {
            log.warn("Temporal chain pruning failed: {}", e.getMessage());
            return 0;
        }
    }

    // ── Phase 4: Cross-Layer Promotion (Hebbian → Entity) ──

    private void promoteCrossLayer() {
        try {
            int crossPromoted = promoteHebbianToEntity(HEBBIAN_PROMOTION_MIN_WEIGHT);
            if (crossPromoted > 0) {
                log.info("Reflect: cross-layer promoted {} Hebbian edges to entity relations",
                        crossPromoted);
            }
        } catch (RuntimeException e) {
            log.warn("Cross-layer promotion failed: {}", e.getMessage());
        }
    }

    /**
     * Promotes strong Hebbian co-activation edges into entity-level RELATED_TO edges.
     *
     * <p>For each Hebbian edge with weight ≥ {@code minWeight}, scans both endpoint
     * memories' entity associations and creates RELATED_TO edges between all entity
     * pairs. This bridges the statistical co-occurrence layer (Hebbian) with the
     * structured knowledge layer (Entity graph).</p>
     *
     * @param minWeight minimum Hebbian weight to qualify for promotion
     * @return number of entity relations created or strengthened
     */
    private int promoteHebbianToEntity(float minWeight) {
        if (entityGraph == null || entityGraph.entityCount() == 0) return 0;

        // Build reverse index: memoryIdx → List<entityId>
        int ecnt = entityGraph.entityCount();
        Map<Integer, List<Integer>> memToEntities = new HashMap<>();
        for (int e = 0; e < ecnt; e++) {
            int refCount = entityGraph.memoryRefCount(e);
            for (int r = 0; r < refCount; r++) {
                int memIdx = entityGraph.memoryRefAt(e, r);
                if (memIdx >= 0) {
                    memToEntities.computeIfAbsent(memIdx, k -> new ArrayList<>(2)).add(e);
                }
            }
        }

        int promoted = 0;
        int capacity = hebbianGraph.capacity();

        for (int nodeA = 0; nodeA < capacity; nodeA++) {
            var edges = hebbianGraph.neighbors(nodeA);
            for (var edge : edges) {
                if (edge.weight() < minWeight) break; // sorted descending
                int nodeB = edge.neighborIndex();
                if (nodeB <= nodeA) continue; // avoid double-processing A↔B

                var entitiesA = memToEntities.get(nodeA);
                var entitiesB = memToEntities.get(nodeB);
                if (entitiesA == null || entitiesB == null) continue;

                for (int eA : entitiesA) {
                    for (int eB : entitiesB) {
                        if (eA != eB) {
                            entityGraph.addRelation(eA, eB, "RELATED_TO");
                            promoted++;
                        }
                    }
                }
            }
        }
        return promoted;
    }

    // ── Phase 5: Entity Graph Maintenance ──

    private void maintainEntityGraph() {
        if (entityGraph == null || entityGraph.entityCount() == 0) return;
        try {
            int entityDecayed = entityGraph.decayEdges(ENTITY_DECAY_FACTOR, ENTITY_PRUNE_THRESHOLD);
            if (entityDecayed > 0) {
                log.info("Reflect: entity graph decayed {} weak edges", entityDecayed);
            }
            int entityMerged = entityGraph.mergeSimilarEntities(ENTITY_MERGE_DISTANCE);
            if (entityMerged > 0) {
                log.info("Reflect: merged {} similar entities", entityMerged);
            }
        } catch (RuntimeException e) {
            log.warn("Entity graph maintenance failed: {}", e.getMessage());
        }
    }
}
