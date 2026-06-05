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

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.spectrayan.spector.memory.ScoreBreakdown;
import com.spectrayan.spector.memory.graph.EntityGraph;
import com.spectrayan.spector.memory.hebbian.HebbianGraph;
import com.spectrayan.spector.memory.hebbian.HebbianGraph.HebbianEdge;
import com.spectrayan.spector.memory.temporal.TemporalChain;

/**
 * Identifies which cognitive subsystem(s) contributed to a retrieval result
 * appearing in the cognitive top-10 but not in the baseline top-10.
 *
 * <p>When the cognitive retriever finds a memory that the baseline (vector-only)
 * retriever missed, this enum's {@link #detect} method determines <em>why</em>
 * the cognitive pipeline surfaced it — enabling per-subsystem contribution
 * metrics in the benchmark report.</p>
 *
 * <h3>Detection Heuristics</h3>
 * <ol>
 *   <li><b>HEBBIAN_GRAPH</b> — memory is reachable via Hebbian edges from a baseline seed</li>
 *   <li><b>TEMPORAL_CHAIN</b> — memory is in the temporal chain (±3 hops) of a baseline seed</li>
 *   <li><b>ENTITY_GRAPH</b> — memory is linked via entity graph to a baseline seed</li>
 *   <li><b>IMPORTANCE_DECAY</b> — high importance compensated for low vector similarity</li>
 *   <li><b>VALENCE_FILTER</b> — valence alignment boosted this memory's score</li>
 *   <li><b>TAG_GATING</b> — tag matching gated this memory into the result set</li>
 * </ol>
 */
public enum ContributingSubsystem {

    /** Memory discovered via Hebbian spreading activation from baseline seeds. */
    HEBBIAN_GRAPH,

    /** Memory discovered via temporal chain traversal from baseline seeds. */
    TEMPORAL_CHAIN,

    /** Memory discovered via entity graph multi-hop traversal from baseline seeds. */
    ENTITY_GRAPH,

    /** Memory surfaced due to high importance compensating for low vector similarity. */
    IMPORTANCE_DECAY,

    /** Memory surfaced due to valence alignment boosting its score. */
    VALENCE_FILTER,

    /** Memory surfaced due to tag matching gating it into the result set. */
    TAG_GATING;

    /** Maximum hops for Hebbian reachability check (matches pipeline's 2-hop limit). */
    private static final int HEBBIAN_MAX_HOPS = 2;

    /** Maximum hops for temporal chain reachability check (3 forward + 3 backward). */
    private static final int TEMPORAL_MAX_HOPS = 3;

    /** Threshold for considering importance/decay as significant contributor. */
    private static final float IMPORTANCE_DECAY_THRESHOLD = 0.3f;

    /** Threshold for considering tag boost as significant contributor. */
    private static final float TAG_BOOST_THRESHOLD = 1.05f;

    /** Threshold for considering valence alignment as significant contributor. */
    private static final float VALENCE_ALIGNMENT_THRESHOLD = 1.05f;

    /** Threshold for considering graph boost as significant contributor. */
    private static final float GRAPH_BOOST_THRESHOLD = 1.01f;

    /**
     * For a result in cognitive top-10 but absent from baseline top-10,
     * determines the contributing subsystem(s) by checking:
     * <ol>
     *   <li>Is the memory reachable via Hebbian edges from a baseline seed? → HEBBIAN</li>
     *   <li>Is the memory in the temporal chain of a baseline seed? → TEMPORAL</li>
     *   <li>Is the memory linked via entity graph to a baseline seed? → ENTITY</li>
     *   <li>Does the memory have high importance that compensated for low similarity? → IMPORTANCE</li>
     *   <li>Does valence alignment contribute significantly? → VALENCE</li>
     *   <li>Was the memory gated in via tag matching? → TAG_GATING</li>
     * </ol>
     *
     * <p>Multiple subsystems may contribute to a single result (the result set
     * can contain more than one enum value).</p>
     *
     * @param memoryId      the memory ID found in cognitive top-10 but not baseline top-10
     * @param baselineTop10 the set of memory IDs in the baseline retriever's top-10
     * @param hebbian       the Hebbian association graph (may be null if disabled)
     * @param temporal      the temporal chain (may be null if disabled)
     * @param entity        the entity graph (may be null if disabled)
     * @param breakdown     the score breakdown for this result (may be null)
     * @param idToSlot      mapping from memory IDs to their slot indices in the graphs
     * @return the set of contributing subsystems (never null, may be empty)
     */
    public static Set<ContributingSubsystem> detect(
            String memoryId,
            Set<String> baselineTop10,
            HebbianGraph hebbian,
            TemporalChain temporal,
            EntityGraph entity,
            ScoreBreakdown breakdown,
            Map<String, Integer> idToSlot) {

        EnumSet<ContributingSubsystem> contributions = EnumSet.noneOf(ContributingSubsystem.class);

        Integer targetSlot = idToSlot.get(memoryId);

        // ── Graph-based detection (requires slot resolution) ──
        if (targetSlot != null) {
            // Check Hebbian reachability from any baseline seed
            if (hebbian != null && isHebbianReachable(targetSlot, baselineTop10, hebbian, idToSlot)) {
                contributions.add(HEBBIAN_GRAPH);
            }

            // Check temporal chain adjacency with any baseline seed
            if (temporal != null && isTemporallyReachable(targetSlot, baselineTop10, temporal, idToSlot)) {
                contributions.add(TEMPORAL_CHAIN);
            }

            // Check entity graph connectivity with any baseline seed
            if (entity != null && isEntityReachable(targetSlot, baselineTop10, entity, idToSlot)) {
                contributions.add(ENTITY_GRAPH);
            }
        }

        // ── Score-breakdown-based detection ──
        if (breakdown != null) {
            // Importance/decay significant contributor: importance×decay component
            // dominates or is substantial relative to similarity
            if (breakdown.importanceDecay() > IMPORTANCE_DECAY_THRESHOLD
                    && breakdown.importanceDecay() >= breakdown.similarity() * 0.5f) {
                contributions.add(IMPORTANCE_DECAY);
            }

            // Valence alignment boosted this result
            if (breakdown.valenceAlignment() > VALENCE_ALIGNMENT_THRESHOLD) {
                contributions.add(VALENCE_FILTER);
            }

            // Tag boost gated this result in
            if (breakdown.tagBoostFactor() > TAG_BOOST_THRESHOLD) {
                contributions.add(TAG_GATING);
            }

            // Also check graph boost from breakdown as a fallback signal
            if (breakdown.graphBoost() > GRAPH_BOOST_THRESHOLD && contributions.isEmpty()) {
                // Graph boost present but couldn't pinpoint which graph — add all that are non-null
                if (hebbian != null) contributions.add(HEBBIAN_GRAPH);
                if (temporal != null) contributions.add(TEMPORAL_CHAIN);
                if (entity != null) contributions.add(ENTITY_GRAPH);
            }
        }

        return contributions;
    }

    /**
     * Checks if the target memory slot is reachable from any baseline seed
     * via Hebbian spreading activation within {@value #HEBBIAN_MAX_HOPS} hops.
     */
    private static boolean isHebbianReachable(int targetSlot, Set<String> baselineTop10,
                                               HebbianGraph hebbian, Map<String, Integer> idToSlot) {
        for (String baselineId : baselineTop10) {
            Integer seedSlot = idToSlot.get(baselineId);
            if (seedSlot == null) continue;

            List<HebbianEdge> activated = hebbian.activateNeighbors(seedSlot, HEBBIAN_MAX_HOPS);
            for (HebbianEdge edge : activated) {
                if (edge.neighborIndex() == targetSlot) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks if the target memory slot is reachable from any baseline seed
     * via temporal chain traversal (forward or backward, up to {@value #TEMPORAL_MAX_HOPS} hops).
     */
    private static boolean isTemporallyReachable(int targetSlot, Set<String> baselineTop10,
                                                   TemporalChain temporal, Map<String, Integer> idToSlot) {
        for (String baselineId : baselineTop10) {
            Integer seedSlot = idToSlot.get(baselineId);
            if (seedSlot == null) continue;

            // Check forward chain
            int[] forward = temporal.followForward(seedSlot, TEMPORAL_MAX_HOPS);
            for (int slot : forward) {
                if (slot == targetSlot) return true;
            }

            // Check backward chain
            int[] backward = temporal.followBackward(seedSlot, TEMPORAL_MAX_HOPS);
            for (int slot : backward) {
                if (slot == targetSlot) return true;
            }
        }
        return false;
    }

    /**
     * Checks if the target memory slot is reachable from any baseline seed
     * via entity graph traversal (the target memory is linked to an entity
     * that connects to a baseline seed's entity within 2 hops).
     */
    private static boolean isEntityReachable(int targetSlot, Set<String> baselineTop10,
                                              EntityGraph entity, Map<String, Integer> idToSlot) {
        // Collect all entity IDs that reference the target memory
        // and all entity IDs that reference baseline seeds, then check connectivity

        for (String baselineId : baselineTop10) {
            Integer seedSlot = idToSlot.get(baselineId);
            if (seedSlot == null) continue;

            // For each entity in the graph, check if it links to the seed
            // and if any connected entity links to the target
            Map<String, Integer> nameIndex = entity.nameIndex();
            for (int entityId : nameIndex.values()) {
                // Check if this entity references the seed memory
                if (entityReferencesMemory(entity, entityId, seedSlot)) {
                    // Collect all memories reachable from this entity within 2 hops
                    Set<Integer> reachableMemories = entity.collectMemories(entityId, null, 2);
                    if (reachableMemories.contains(targetSlot)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Checks if a given entity has a memory reference to the specified slot.
     */
    private static boolean entityReferencesMemory(EntityGraph entity, int entityId, int memorySlot) {
        int refCount = entity.memoryRefCount(entityId);
        for (int i = 0; i < refCount; i++) {
            if (entity.memoryRefAt(entityId, i) == memorySlot) {
                return true;
            }
        }
        return false;
    }

    /**
     * Computes per-subsystem contribution percentages across all benchmark queries.
     *
     * <p>For each subsystem, counts the number of queries where that subsystem
     * contributed at least one result with relevance ≥ 2 (relevant or highly relevant).
     * The percentage is computed as: count / totalQueries × 100.</p>
     *
     * @param perQueryContributions map from query ID to the set of subsystems that
     *                              contributed relevant results for that query
     * @param totalQueries          total number of queries executed
     * @return a SubsystemContributions record with per-subsystem percentages
     */
    public static ReportWriter.SubsystemContributions computeContributionPercentages(
            Map<String, Set<ContributingSubsystem>> perQueryContributions,
            int totalQueries) {

        if (totalQueries == 0) {
            return new ReportWriter.SubsystemContributions(0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        }

        int hebbianCount = 0;
        int temporalCount = 0;
        int entityCount = 0;
        int importanceCount = 0;
        int valenceCount = 0;
        int tagGatingCount = 0;

        for (Set<ContributingSubsystem> queryContributions : perQueryContributions.values()) {
            if (queryContributions.contains(HEBBIAN_GRAPH)) hebbianCount++;
            if (queryContributions.contains(TEMPORAL_CHAIN)) temporalCount++;
            if (queryContributions.contains(ENTITY_GRAPH)) entityCount++;
            if (queryContributions.contains(IMPORTANCE_DECAY)) importanceCount++;
            if (queryContributions.contains(VALENCE_FILTER)) valenceCount++;
            if (queryContributions.contains(TAG_GATING)) tagGatingCount++;
        }

        double total = totalQueries;
        return new ReportWriter.SubsystemContributions(
                (hebbianCount / total) * 100.0,
                (temporalCount / total) * 100.0,
                (entityCount / total) * 100.0,
                (importanceCount / total) * 100.0,
                (valenceCount / total) * 100.0,
                (tagGatingCount / total) * 100.0
        );
    }
}
