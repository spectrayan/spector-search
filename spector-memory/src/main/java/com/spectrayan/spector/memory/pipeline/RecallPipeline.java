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
package com.spectrayan.spector.memory.pipeline;

import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.events.GraphPulseTelemetry;
import com.spectrayan.spector.events.TelemetryScope;

import com.spectrayan.spector.memory.error.SpectorEntityGraphException;
import com.spectrayan.spector.memory.error.SpectorHebbianException;
import com.spectrayan.spector.memory.error.SpectorTemporalChainException;

import com.spectrayan.spector.commons.concurrent.ConcurrentTasks;
import com.spectrayan.spector.commons.concurrent.ConcurrentExecutionException;
import com.spectrayan.spector.embed.EmbeddingProvider;
import com.spectrayan.spector.memory.CognitiveResult;
import com.spectrayan.spector.memory.CognitiveResult.RetrievalMode;
import com.spectrayan.spector.memory.MemoryType;
import com.spectrayan.spector.memory.RecallMode;
import com.spectrayan.spector.memory.RecallOptions;
import com.spectrayan.spector.memory.ScoreBreakdown;
import com.spectrayan.spector.memory.cortex.EpisodicMemoryStore.EpisodicPartition;
import com.spectrayan.spector.memory.cortex.MemorySource;
import com.spectrayan.spector.memory.cortex.SemanticRecallStrategy;
import com.spectrayan.spector.memory.cortex.TierRouter;
import com.spectrayan.spector.memory.habituation.HabituationPenalty;
import com.spectrayan.spector.memory.index.MemoryIndex;
import com.spectrayan.spector.memory.inhibition.SuppressionSet;
import com.spectrayan.spector.memory.prospective.ProspectiveScheduler;
import com.spectrayan.spector.memory.prospective.Reminder;
import com.spectrayan.spector.memory.sync.MemoryWal;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout.CognitiveHeader;
import com.spectrayan.spector.memory.synapse.CognitiveScorer;
import com.spectrayan.spector.memory.synapse.CognitiveScorer.ScoredRecord;
import com.spectrayan.spector.memory.synapse.DecayStrategy;
import com.spectrayan.spector.memory.synapse.SynapticHeaderConstants;
import com.spectrayan.spector.memory.synapse.SynapticTagEncoder;
import com.spectrayan.spector.memory.hebbian.CoActivationTracker;
import com.spectrayan.spector.memory.hebbian.HebbianGraph;
import com.spectrayan.spector.memory.graph.EntityExtractor;
import com.spectrayan.spector.memory.graph.EntityGraph;
import com.spectrayan.spector.memory.graph.ExtractedEntity;
import com.spectrayan.spector.memory.temporal.TemporalChain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.spectrayan.spector.memory.cortex.EpisodicMemoryStore.EpisodicPartition.METADATA_HEADER_BYTES;

/**
 * 8-step recall pipeline for cognitive memory retrieval.
 *
 * <h3>Pipeline Steps</h3>
 * <pre>
 *   Step 1: Embed query text
 *   Step 2: Collect due prospective reminders
 *   Step 3: Score across each tier store (parallel via ConcurrentTasks)
 *   Step 4: Filter suppressed memories (inhibition)
 *   Step 5: Apply habituation penalty (anti-filter-bubble)
 *   Step 6: Sort by score descending, limit to topK
 *   Step 7: Fire async post-recall listeners (LTP + Hebbian)
 * </pre>
 *
 * <h3>Performance: Parallel Tier Scanning</h3>
 * <p>Step 3 fans out tier scans as parallel tasks via
 * {@link ConcurrentTasks#forkJoinAll}. Each scan operates on a disjoint
 * off-heap {@link MemorySegment} — zero contention. With 4 tiers + N episodic
 * partitions, recall latency = max(tier_latency) instead of sum(tier_latencies).</p>
 *
 * <h3>Performance: Async Post-Recall Hooks</h3>
 * <p>Steps 7–8 (LTP reconsolidation, Hebbian co-activation) fire on Virtual Threads
 * so the caller doesn't block on post-recall bookkeeping.</p>
 *
 * <h3>Design Patterns</h3>
 * <ul>
 *   <li><b>Template Method</b>: Pipeline skeleton is fixed; scoring delegated to
 *       {@link CognitiveScorer}</li>
 *   <li><b>Observer</b>: Post-recall hooks via {@link RecallListener}</li>
 * </ul>
 */
public final class RecallPipeline {

    private static final Logger log = LoggerFactory.getLogger(RecallPipeline.class);

    private final EmbeddingProvider embeddingProvider;
    private final TierRouter tierRouter;
    private final MemoryIndex index;
    private final SuppressionSet suppressionSet;
    private final HabituationPenalty habituationPenalty;
    private final ProspectiveScheduler prospectiveScheduler;
    private final MemoryWal wal;
    private final float[] calibrationMins;
    private final float[] calibrationScales;
    private final SemanticRecallStrategy semanticRecallStrategy; // nullable
    private final CoActivationTracker coActivationTracker; // nullable — for STDP causal boost
    private final GraphScoringPolicy graphScoringPolicy;

    private final List<RecallListener> listeners = new ArrayList<>();

    // ── 3-Layer Cognitive Graph (all nullable) ──
    private final HebbianGraph hebbianGraph;
    private final TemporalChain temporalChain;
    private final EntityGraph entityGraph;
    private final EntityExtractor entityExtractor;

    // ── Neurodivergent: Lateral feedback tracking ──
    // Maps memoryId → RetrievalMode for the most recent recall.
    // Used by SpectorMemory.reinforce()/suppress() to feed LateralEvaluator.
    // Entries expire implicitly via size cap (oldest evicted at 2000).
    private final ConcurrentHashMap<String, RetrievalMode> recentRetrievalModes
            = new ConcurrentHashMap<>();
    private static final int RETRIEVAL_MODE_CACHE_MAX = 2000;
    private RecallOptions lastRecallOptions; // for detecting hyperfocus mode

    // ── Semantic Satiation: Anti-looping LRU cache ──
    // Bounded LRU of last N result IDs. Any result that appears in this
    // hot cache gets a 0.5× penalty, breaking exact-query loops.
    private static final int SATIATION_CACHE_SIZE = 10;
    private static final float SATIATION_PENALTY = 0.5f;
    private final LinkedHashMap<String, Long> satiationCache =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > SATIATION_CACHE_SIZE;
                }
            };

    /**
     * Creates a recall pipeline with all required subsystems.
     */
    public RecallPipeline(EmbeddingProvider embeddingProvider,
                           TierRouter tierRouter,
                           MemoryIndex index,
                           SuppressionSet suppressionSet,
                           HabituationPenalty habituationPenalty,
                           ProspectiveScheduler prospectiveScheduler,
                           MemoryWal wal,
                           float[] calibrationMins,
                           float[] calibrationScales) {
        this(embeddingProvider, tierRouter, index, suppressionSet, habituationPenalty,
                prospectiveScheduler, wal, calibrationMins, calibrationScales, null, null,
                null, null, null, null, GraphScoringPolicy.DEFAULT);
    }

    /**
     * Creates a recall pipeline with optional fused semantic recall.
     *
     * @param semanticRecallStrategy nullable — when provided, semantic recall uses
     *                                HNSW vector search fused with cognitive scoring
     */
    public RecallPipeline(EmbeddingProvider embeddingProvider,
                           TierRouter tierRouter,
                           MemoryIndex index,
                           SuppressionSet suppressionSet,
                           HabituationPenalty habituationPenalty,
                           ProspectiveScheduler prospectiveScheduler,
                           MemoryWal wal,
                           float[] calibrationMins,
                           float[] calibrationScales,
                           SemanticRecallStrategy semanticRecallStrategy) {
        this(embeddingProvider, tierRouter, index, suppressionSet, habituationPenalty,
                prospectiveScheduler, wal, calibrationMins, calibrationScales,
                semanticRecallStrategy, null,
                null, null, null, null, GraphScoringPolicy.DEFAULT);
    }

    /**
     * Creates a recall pipeline with optional fused semantic recall and STDP.
     *
     * @param semanticRecallStrategy nullable — when provided, semantic recall uses
     *                                HNSW vector search fused with cognitive scoring
     * @param coActivationTracker    nullable — when provided, STDP causal boost is applied
     */
    public RecallPipeline(EmbeddingProvider embeddingProvider,
                           TierRouter tierRouter,
                           MemoryIndex index,
                           SuppressionSet suppressionSet,
                           HabituationPenalty habituationPenalty,
                           ProspectiveScheduler prospectiveScheduler,
                           MemoryWal wal,
                           float[] calibrationMins,
                           float[] calibrationScales,
                           SemanticRecallStrategy semanticRecallStrategy,
                           CoActivationTracker coActivationTracker) {
        this(embeddingProvider, tierRouter, index, suppressionSet, habituationPenalty,
                prospectiveScheduler, wal, calibrationMins, calibrationScales,
                semanticRecallStrategy, coActivationTracker,
                null, null, null, null, GraphScoringPolicy.DEFAULT);
    }

    /**
     * Creates a recall pipeline with optional fused semantic recall, STDP, and 3-Layer Cognitive Graph.
     */
    public RecallPipeline(EmbeddingProvider embeddingProvider,
                           TierRouter tierRouter,
                           MemoryIndex index,
                           SuppressionSet suppressionSet,
                           HabituationPenalty habituationPenalty,
                           ProspectiveScheduler prospectiveScheduler,
                           MemoryWal wal,
                           float[] calibrationMins,
                           float[] calibrationScales,
                           SemanticRecallStrategy semanticRecallStrategy,
                           CoActivationTracker coActivationTracker,
                           HebbianGraph hebbianGraph,
                           TemporalChain temporalChain,
                           EntityGraph entityGraph,
                           EntityExtractor entityExtractor,
                           GraphScoringPolicy graphScoringPolicy) {
        this.embeddingProvider = embeddingProvider;
        this.tierRouter = tierRouter;
        this.index = index;
        this.suppressionSet = suppressionSet;
        this.habituationPenalty = habituationPenalty;
        this.prospectiveScheduler = prospectiveScheduler;
        this.wal = wal;
        this.calibrationMins = calibrationMins;
        this.calibrationScales = calibrationScales;
        this.semanticRecallStrategy = semanticRecallStrategy;
        this.coActivationTracker = coActivationTracker;
        this.hebbianGraph = hebbianGraph;
        this.temporalChain = temporalChain;
        this.entityGraph = entityGraph;
        this.entityExtractor = entityExtractor;
        this.graphScoringPolicy = graphScoringPolicy != null ? graphScoringPolicy : GraphScoringPolicy.DEFAULT;
    }

    /**
     * Registers a post-recall listener (Observer pattern).
     *
     * @param listener called after each successful recall with the final results
     */
    public void addListener(RecallListener listener) {
        if (listener == null) { throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "listener"); } listeners.add(listener);
    }

    /**
     * Executes the full recall pipeline with parallel tier scanning.
     *
     * @param queryText the query text (will be embedded)
     * @param options   recall configuration
     * @return ranked list of cognitive results
     */
    public List<CognitiveResult> recall(String queryText, RecallOptions options) {
        if (queryText == null) { throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "queryText"); }
        if (options == null) options = RecallOptions.DEFAULT;

        if (options.recallMode() == RecallMode.REPLAY) {
            throw new SpectorValidationException(
                    ErrorCode.RECALL_MODE_NOT_IMPLEMENTED,
                    "REPLAY",
                    "Requires WAL point-in-time reconstruction. Use LEARN or OBSERVE instead.");
        }

        log.debug("Recall query: '{}', topK={}, mode={}", queryText, options.topK(), options.recallMode());
        this.lastRecallOptions = options; // for RetrievalMode detection in headerToResult

        // Step 1: Embed query
        float[] queryVector = embeddingProvider.embed(queryText).vector();

        long nowMs = System.currentTimeMillis();
        List<CognitiveResult> allResults = new ArrayList<>();

        // Step 2: Collect due prospective reminders
        List<Reminder> dueReminders = prospectiveScheduler.collectDue();
        for (Reminder r : dueReminders) {
            allResults.add(new CognitiveResult(
                    r.id(), r.text(), 10.0f, 10.0f, 0f,
                    (short) 0, (byte) 0, MemoryType.WORKING, MemorySource.PROCEDURAL,
                    new String[]{"prospective"}, 1.0f, 1.0f));
        }

        // Step 3: Parallel tier scanning via ConcurrentTasks.forkJoinAll
        MemoryType[] targetTypes = options.memoryTypes();
        List<Callable<List<CognitiveResult>>> scanTasks = buildScanTasks(
                queryVector, options, nowMs, targetTypes);

        if (!scanTasks.isEmpty()) {
            try {
                List<List<CognitiveResult>> tierResults = ConcurrentTasks.forkJoinAll(scanTasks);
                for (List<CognitiveResult> tier : tierResults) {
                    allResults.addAll(tier);
                }
            } catch (ConcurrentExecutionException e) {
                log.error("Parallel tier scan failed: {}", e.getMessage(), e);
                // Fallback: sequential scan
                allResults.addAll(sequentialScan(queryVector, options, nowMs, targetTypes));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Recall interrupted during parallel scan");
                return allResults;
            }
        }

        // Step 4: Filter suppressed memories (inhibition)
        allResults.removeIf(r -> suppressionSet.isSuppressed(r.id()));

        // Step 5: Apply habituation penalty + inhibition of return + semantic satiation
        for (int i = 0; i < allResults.size(); i++) {
            CognitiveResult r = allResults.get(i);
            float habPenalty = (options.recallMode() == RecallMode.LEARN)
                    ? habituationPenalty.recordAndComputePenalty(r.id())
                    : habituationPenalty.currentPenalty(r.id());
            float iorPenalty = habituationPenalty.computeInhibitionOfReturn(r.id(), nowMs);
            float combinedPenalty = Math.min(habPenalty, iorPenalty); // stronger suppression wins

            // Semantic Satiation: 0.5× penalty for results in the hot LRU cache
            if (satiationCache.containsKey(r.id())) {
                combinedPenalty *= SATIATION_PENALTY;
            }

            if (combinedPenalty < 1.0f) {
                float newScore = r.score() * combinedPenalty;
                // Carry breakdown with actual habituation penalty recorded
                ScoreBreakdown bd = r.breakdown() != null
                        ? new ScoreBreakdown(
                                r.breakdown().similarity(),
                                r.breakdown().importanceDecay(),
                                r.breakdown().tagBoostFactor(),
                                combinedPenalty,
                                r.breakdown().graphBoost(),
                                r.breakdown().valenceAlignment(),
                                newScore)
                        : null;
                allResults.set(i, new CognitiveResult(
                        r.id(), r.text(), newScore, r.importance(), r.ageDays(),
                        r.recallCount(), r.valence(), r.memoryType(), r.source(),
                        r.synapticTags(), r.decayFactor(), r.ltpAdjustedDecay(),
                        r.retrievalMode(), bd));
            }
        }

        // Step 5b: STDP causal boost — cross-boost results whose tags are causally linked
        // For each result, check if earlier results' tags predict its tags (via STDP edges).
        // This promotes memories that form causal chains.
        if (coActivationTracker != null && allResults.size() >= 2) {
            // Use tags from the first few results as "context tags" to boost subsequent results
            // (imperative loop — avoids Stream API allocation overhead in hot path)
            Set<String> contextTagSet = new HashSet<>();
            int contextLimit = Math.min(3, allResults.size());
            for (int cl = 0; cl < contextLimit; cl++) {
                String[] ctxTags = allResults.get(cl).synapticTags();
                if (ctxTags != null) {
                    for (String t : ctxTags) contextTagSet.add(t);
                }
            }

            if (!contextTagSet.isEmpty()) {
                // Convert to list once for getPredictiveStrength API
                List<String> contextTags = new ArrayList<>(contextTagSet);
                for (int i = 0; i < allResults.size(); i++) {
                    CognitiveResult r = allResults.get(i);
                    if (r.synapticTags() == null || r.synapticTags().length == 0) continue;

                    float predictive = coActivationTracker.getPredictiveStrength(
                            contextTags, r.synapticTags());
                    if (predictive > 0) {
                        float boostedScore = r.score() * (1.0f + predictive * graphScoringPolicy.causalBoostWeight());
                        allResults.set(i, new CognitiveResult(
                                r.id(), r.text(), boostedScore, r.importance(), r.ageDays(),
                                r.recallCount(), r.valence(), r.memoryType(), r.source(),
                                r.synapticTags(), r.decayFactor(), r.ltpAdjustedDecay()));
                    }
                }
            }
        }

        // Build existingIds ONCE for graph expansion steps 5c/5d/5e
        // (previously rebuilt 3 times — eliminated 2 redundant full-list scans)
        boolean needsGraphExpansion = (hebbianGraph != null || temporalChain != null
                || (entityGraph != null && entityExtractor != null && entityExtractor.isAvailable()))
                && !allResults.isEmpty();
        Set<String> existingIds = needsGraphExpansion ? new HashSet<>(allResults.size()) : null;
        if (existingIds != null) {
            for (CognitiveResult r : allResults) {
                if (r.id() != null) existingIds.add(r.id());
            }
        }

        // ── Graph telemetry tracking ──
        // NOTE: Manual nanoTime is intentional here. This times a *sub-phase* of recall
        // (graph expansion only). The overall recall() is already timed by MeteredSpectorMemory's
        // Micrometer Timer. We can't add a Micrometer timer here because spector-memory does
        // not depend on Micrometer — that coupling lives in the spector-metrics decorator layer.
        long graphStartNanos = needsGraphExpansion && TelemetryScope.isActive() ? System.nanoTime() : 0;
        int graphNodesVisited = 0;
        int graphEdgesTraversed = 0;
        int graphMaxDepth = 0;

        // Step 5c: Hebbian spreading activation — follow memory-to-memory associations
        if (hebbianGraph != null && existingIds != null) {
            try {
                // Use top 3 results as seeds for spreading activation
                int seeds = Math.min(3, allResults.size());
                for (int s = 0; s < seeds; s++) {
                    CognitiveResult seed = allResults.get(s);
                    // Find the memory index for this result
                    MemoryIndex.MemoryLocation loc = index.locate(seed.id());
                    if (loc == null) continue;

                    int memIdx = (int) (loc.offset() / 164); // approximate index from offset
                    var activated = hebbianGraph.activateNeighbors(memIdx, graphScoringPolicy.hebbianMaxDepth());
                    graphEdgesTraversed += activated.size();
                    graphMaxDepth = Math.max(graphMaxDepth, graphScoringPolicy.hebbianMaxDepth());
                    for (var edge : activated) {
                        graphNodesVisited++;
                        // Find the memory at this graph index
                        String neighborId = findMemoryByApproximateIndex(edge.neighborIndex());
                        if (neighborId != null && !existingIds.contains(neighborId)) {
                            existingIds.add(neighborId);
                            String text = index.text(neighborId);
                            MemorySource source = index.source(neighborId);
                            String[] tags = index.tags(neighborId);
                            float graphScore = seed.score() * edge.weight() * graphScoringPolicy.hebbianBoostFactor(); // attenuated
                            allResults.add(new CognitiveResult(
                                    neighborId, text, graphScore, seed.importance(), 0f,
                                    (short) 0, (byte) 0, seed.memoryType(), source,
                                    tags, 1.0f, 1.0f));
                        }
                    }
                }
            } catch (RuntimeException e) {
                SpectorHebbianException ex = new SpectorHebbianException("spreading activation", e);
                log.debug(ex.getMessage());
            }
        }

        // Step 5d: Temporal chain extension — follow session-linked sequences
        if (temporalChain != null && existingIds != null) {
            try {
                int seeds = Math.min(3, allResults.size());
                for (int s = 0; s < seeds; s++) {
                    CognitiveResult seed = allResults.get(s);
                    MemoryIndex.MemoryLocation loc = index.locate(seed.id());
                    if (loc == null) continue;

                    int memIdx = (int) (loc.offset() / 164);
                    // Follow forward and backward
                    for (int chainIdx : temporalChain.followForward(memIdx, graphScoringPolicy.temporalMaxHops())) {
                        addChainResult(chainIdx, seed, existingIds, allResults, graphScoringPolicy.temporalForwardFactor());
                    }
                    for (int chainIdx : temporalChain.followBackward(memIdx, graphScoringPolicy.temporalMaxHops())) {
                        addChainResult(chainIdx, seed, existingIds, allResults, graphScoringPolicy.temporalBackwardFactor());
                    }
                }
            } catch (RuntimeException e) {
                SpectorTemporalChainException ex = new SpectorTemporalChainException("chain extension", e);
                log.debug(ex.getMessage());
            }
        }

        // Step 5e: Entity graph traversal — multi-hop knowledge discovery
        if (entityGraph != null && entityExtractor != null
                && entityExtractor.isAvailable() && existingIds != null) {
            // Extract entities from the query
            try {
                var queryEntities = entityExtractor.extract("query", queryText);
                for (var entity : queryEntities) {
                    int entityId = entityGraph.findEntity(entity.name());
                    if (entityId < 0) continue;

                    // Collect memories reachable within 2 hops
                    Set<Integer> reachableMemories = entityGraph.collectMemories(
                            entityId, null, graphScoringPolicy.entityMaxHops());
                    for (int memIdx : reachableMemories) {
                        String memId = findMemoryByApproximateIndex(memIdx);
                        if (memId != null && !existingIds.contains(memId)) {
                            existingIds.add(memId);
                            String text = index.text(memId);
                            MemorySource source = index.source(memId);
                            String[] tags = index.tags(memId);
                            float entityScore = allResults.getFirst().score() * graphScoringPolicy.entityHopAttenuation();
                            allResults.add(new CognitiveResult(
                                    memId, text, entityScore, 0.5f, 0f,
                                    (short) 0, (byte) 0, MemoryType.SEMANTIC, source,
                                    tags, 1.0f, 1.0f));
                        }
                    }
                }
            } catch (RuntimeException e) {
                SpectorEntityGraphException ex = new SpectorEntityGraphException("graph traversal", e);
                log.debug(ex.getMessage());
            }
        }

        // ── Report graph telemetry (if enabled) ──
        if (graphStartNanos > 0) {
            long graphElapsed = System.nanoTime() - graphStartNanos;
            TelemetryScope.publish(new GraphPulseTelemetry(
                    graphNodesVisited, graphEdgesTraversed, graphMaxDepth, graphElapsed));
        }

        // Step 6: Sort by score descending, limit to topK
        allResults.sort(Comparator.comparing(CognitiveResult::score).reversed());
        if (allResults.size() > options.topK()) {
            allResults = new ArrayList<>(allResults.subList(0, options.topK()));
        }

        // Step 7: Fire async post-recall listeners (LTP reconsolidation + Hebbian)
        // In OBSERVE mode, listeners are skipped to prevent persistent mutations.
        if (options.recallMode() == RecallMode.LEARN && !listeners.isEmpty()) {
            final List<CognitiveResult> finalResults = allResults;
            for (RecallListener listener : listeners) {
                Thread.startVirtualThread(() -> {
                    try {
                        listener.onRecallComplete(finalResults);
                    } catch (Exception e) {
                        log.error("Post-recall listener failed: {}", e.getMessage(), e);
                    }
                });
            }
        }

        // Steps 8-8c: Record ephemeral session state (LEARN mode only)
        if (options.recallMode() == RecallMode.LEARN) {
            // Step 8: Record recall timestamps for Inhibition of Return
            long recallTs = System.currentTimeMillis();
            for (CognitiveResult r : allResults) {
                habituationPenalty.recordRecall(r.id(), recallTs);
            }

            log.debug("Recall returned {} results for '{}'", allResults.size(), queryText);

            // Step 8c: Cache retrieval modes for lateral feedback (reinforce/suppress)
            if (recentRetrievalModes.size() > RETRIEVAL_MODE_CACHE_MAX) {
                recentRetrievalModes.clear(); // simple eviction — reset when full
            }
            for (CognitiveResult r : allResults) {
                if (r.id() != null) {
                    recentRetrievalModes.put(r.id(), r.retrievalMode());
                }
            }

            // Step 8b: Update semantic satiation LRU cache
            long nowForSatiation = System.currentTimeMillis();
            for (CognitiveResult r : allResults) {
                if (r.id() != null) {
                    satiationCache.put(r.id(), nowForSatiation);
                }
            }
        } else {
            log.debug("Recall [OBSERVE] returned {} results for '{}'", allResults.size(), queryText);
        }

        return allResults;
    }

    // ══════════════════════════════════════════════════════════════
    // PARALLEL SCANNING — builds Callable tasks for each tier/partition
    // ══════════════════════════════════════════════════════════════

    private List<Callable<List<CognitiveResult>>> buildScanTasks(
            float[] queryVector, RecallOptions options, long nowMs, MemoryType[] targetTypes) {
        List<Callable<List<CognitiveResult>>> tasks = new ArrayList<>();

        // Working Memory scan
        if (TierRouter.shouldScan(MemoryType.WORKING, targetTypes)
                && tierRouter.working().size() > 0) {
            tasks.add(() -> scoreStoreToList(
                    tierRouter.working().segment(), tierRouter.working().size(),
                    tierRouter.working().layout(), queryVector, options, nowMs,
                    MemoryType.WORKING, 0L));
        }

        // Episodic Memory — one task per partition (disjoint segments → zero contention)
        if (TierRouter.shouldScan(MemoryType.EPISODIC, targetTypes)) {
            for (EpisodicPartition partition : tierRouter.episodic().partitions()) {
                if (partition.count() > 0) {
                    tasks.add(() -> scoreStoreToList(
                            partition.segment(), partition.count(),
                            partition.layout(), queryVector, options, nowMs,
                            MemoryType.EPISODIC, METADATA_HEADER_BYTES));
                }
            }
        }

        // Semantic Memory — fused HNSW+cognitive if strategy available, else header slab
        if (TierRouter.shouldScan(MemoryType.SEMANTIC, targetTypes)) {
            if (tierRouter.isSemanticPartitioned()) {
                // Partitioned mode: one task per partition for parallel recall
                var partitions = tierRouter.semanticPartitioned().partitions();
                for (var partition : partitions) {
                    if (partition.store().size() > 0) {
                        tasks.add(() -> scoreHeaderSlabToList(
                                partition.headerSlab(), partition.store().size(),
                                partition.store().layout(), queryVector, options, nowMs));
                    }
                }
            } else if (tierRouter.semantic() != null && tierRouter.semantic().size() > 0) {
                if (semanticRecallStrategy != null && semanticRecallStrategy.isAvailable()) {
                    // Fused pipeline: HNSW search → cognitive re-ranking
                    tasks.add(() -> semanticRecallStrategy.recall(queryVector, options, nowMs));
                } else {
                    // Fallback: header-only slab scan (with tag/valence filters)
                    tasks.add(() -> scoreHeaderSlabToList(
                            tierRouter.semantic().headerSlab(), tierRouter.semantic().size(),
                            tierRouter.semantic().layout(), queryVector, options, nowMs));
                }
            }
        }

        // Procedural Memory scan
        if (TierRouter.shouldScan(MemoryType.PROCEDURAL, targetTypes)
                && tierRouter.procedural().size() > 0) {
            tasks.add(() -> scoreStoreToList(
                    tierRouter.procedural().segment(), tierRouter.procedural().size(),
                    tierRouter.procedural().layout(), queryVector, options, nowMs,
                    MemoryType.PROCEDURAL, 0L));
        }

        return tasks;
    }

    /**
     * Fallback sequential scan (used if parallel scan fails).
     */
    private List<CognitiveResult> sequentialScan(float[] queryVector, RecallOptions options,
                                                   long nowMs, MemoryType[] targetTypes) {
        List<CognitiveResult> results = new ArrayList<>();
        if (TierRouter.shouldScan(MemoryType.WORKING, targetTypes)
                && tierRouter.working().size() > 0) {
            results.addAll(scoreStoreToList(tierRouter.working().segment(),
                    tierRouter.working().size(), tierRouter.working().layout(),
                    queryVector, options, nowMs, MemoryType.WORKING, 0L));
        }
        if (TierRouter.shouldScan(MemoryType.EPISODIC, targetTypes)) {
            for (EpisodicPartition p : tierRouter.episodic().partitions()) {
                if (p.count() > 0) {
                    results.addAll(scoreStoreToList(p.segment(), p.count(), p.layout(),
                            queryVector, options, nowMs, MemoryType.EPISODIC, METADATA_HEADER_BYTES));
                }
            }
        }
        if (TierRouter.shouldScan(MemoryType.SEMANTIC, targetTypes)) {
            if (tierRouter.isSemanticPartitioned()) {
                // Partitioned mode: scan each partition sequentially
                for (var partition : tierRouter.semanticPartitioned().partitions()) {
                    if (partition.store().size() > 0) {
                        results.addAll(scoreHeaderSlabToList(partition.headerSlab(),
                                partition.store().size(), partition.store().layout(),
                                queryVector, options, nowMs));
                    }
                }
            } else if (tierRouter.semantic() != null && tierRouter.semantic().size() > 0) {
                if (semanticRecallStrategy != null && semanticRecallStrategy.isAvailable()) {
                    results.addAll(semanticRecallStrategy.recall(queryVector, options, nowMs));
                } else {
                    results.addAll(scoreHeaderSlabToList(tierRouter.semantic().headerSlab(),
                            tierRouter.semantic().size(), tierRouter.semantic().layout(),
                            queryVector, options, nowMs));
                }
            }
        }
        if (TierRouter.shouldScan(MemoryType.PROCEDURAL, targetTypes)
                && tierRouter.procedural().size() > 0) {
            results.addAll(scoreStoreToList(tierRouter.procedural().segment(),
                    tierRouter.procedural().size(), tierRouter.procedural().layout(),
                    queryVector, options, nowMs, MemoryType.PROCEDURAL, 0L));
        }
        return results;
    }

    // ══════════════════════════════════════════════════════════════
    // SCORING HELPERS — return lists (for parallel composition)
    // ══════════════════════════════════════════════════════════════

    private List<CognitiveResult> scoreStoreToList(MemorySegment segment, int recordCount,
                                                     CognitiveRecordLayout layout, float[] queryVector,
                                                     RecallOptions options, long nowMs, MemoryType type,
                                                     long baseOffset) {
        List<ScoredRecord> scored = CognitiveScorer.score(
                segment, recordCount, layout, queryVector, options, nowMs, baseOffset,
                calibrationMins, calibrationScales);

        List<CognitiveResult> results = new ArrayList<>(scored.size());
        for (ScoredRecord sr : scored) {
            // P8: Header already captured during scoring — no off-heap re-read
            results.add(headerToResult(sr, sr.header(), type));
        }
        return results;
    }

    private List<CognitiveResult> scoreHeaderSlabToList(MemorySegment headerSlab, int recordCount,
                                                          CognitiveRecordLayout layout, float[] queryVector,
                                                          RecallOptions options, long nowMs) {
        long queryTagMask = options.synapticTagMask();
        byte minValence = options.minValence();
        byte maxValence = options.maxValence();
        float tagRelevanceBoost = options.tagRelevanceBoost();

        List<CognitiveResult> results = new ArrayList<>();
        for (int i = 0; i < recordCount; i++) {
            long offset = (long) i * layout.headerLayout().headerBytes();
            CognitiveHeader header = layout.readHeader(headerSlab, offset);

            byte flags = header.flags();
            if (SynapticHeaderConstants.isTombstoned(flags)) continue;

            // Phase 2: Synaptic tag gating (was missing for semantic tier)
            if (queryTagMask != 0) {
                if ((header.synapticTags() & queryTagMask) == 0) continue; // zero overlap → skip
            }

            // Phase 3: Valence filter (was missing for semantic tier)
            byte valence = header.valence();
            if (valence < minValence || valence > maxValence) continue;

            float importance = header.importance();
            if (importance < options.minImportance()) continue;

            long timestamp = header.timestampMs();
            int recallCount = header.recallCount();
            int rawBucket = DecayStrategy.ageToBucket(timestamp, nowMs);
            int adjusted = DecayStrategy.adjustForReconsolidation(rawBucket, recallCount);
            float decay = DecayStrategy.decay(adjusted);

            // Score with weighted tag relevance boost (consistent with CognitiveScorer)
            float baseScore = options.beta() * importance * decay;
            float tagOverlap = SynapticTagEncoder.overlapRatio(header.synapticTags(), queryTagMask);
            float score = baseScore * (1.0f + tagOverlap * tagRelevanceBoost);

            results.add(headerToResult(new ScoredRecord(offset, score, i, header), header, MemoryType.SEMANTIC));
        }
        return results;
    }

    private CognitiveResult headerToResult(ScoredRecord sr, CognitiveHeader header, MemoryType type) {
        String id = index.findIdByOffset(type, sr.offset());  // O(1) via reverse index
        String text = id != null ? index.text(id) : "";
        MemorySource source = id != null ? index.source(id) : MemorySource.OBSERVED;
        String[] tags = id != null ? index.tags(id) : new String[0];

        long nowMs = System.currentTimeMillis();
        float ageDays = (nowMs - header.timestampMs()) / (1000f * 60f * 60f * 24f);

        int rawBucket = DecayStrategy.ageToBucket(header.timestampMs(), nowMs);
        int adjusted = DecayStrategy.adjustForReconsolidation(rawBucket, header.recallCount());
        float rawDecay = DecayStrategy.decay(rawBucket);
        float ltpDecay = DecayStrategy.decay(adjusted);

        // Determine retrieval mode from scorer metadata
        RetrievalMode mode;
        if (sr.lateral()) {
            mode = RetrievalMode.LATERAL;
        } else if (lastRecallOptions != null && lastRecallOptions.hyperfocusMask() != 0) {
            mode = RetrievalMode.HYPERFOCUS;
        } else {
            mode = RetrievalMode.STANDARD;
        }

        // ── ScoreBreakdown: re-derive components from header ──
        // Uses the same formula as CognitiveScorer Phase 6.
        // Note: these are approximations — the scorer's strictness/arousal/storageBoost
        // values are folded into the fused score. We capture what we can from the header.
        float importanceDecay = header.importance() * ltpDecay;
        // Breakdown: individual multipliers default to 1.0 (no effect)
        // habituationPenalty and graphBoost are applied post-scorer in the pipeline
        // and updated in-place on CognitiveResult — we record 1.0 here and
        // the pipeline adjusts them when it applies those factors.
        ScoreBreakdown breakdown = new ScoreBreakdown(
                /* similarity */       Math.max(0, sr.score() > 0 ? sr.score() : 0),
                /* importanceDecay */  importanceDecay,
                /* tagBoostFactor */   1.0f,
                /* habituationPenalty */ 1.0f,
                /* graphBoost */       1.0f,
                /* valenceAlignment */ 1.0f,
                /* finalScore */       sr.score()
        );

        return new CognitiveResult(
                id != null ? id : "unknown-" + sr.index(),
                text, sr.score(), header.importance(), ageDays,
                header.recallCount(), header.valence(), type, source,
                tags, rawDecay, ltpDecay, mode, breakdown
        );
    }

    /**
     * Returns whether the given memory was returned as a lateral result
     * in a recent recall.
     *
     * @param memoryId the memory ID to check
     * @return true if the memory was a lateral result, false otherwise
     */
    public boolean wasLateral(String memoryId) {
        RetrievalMode mode = recentRetrievalModes.get(memoryId);
        return mode == RetrievalMode.LATERAL;
    }

    /**
     * Returns the retrieval mode for a recently recalled memory.
     *
     * @param memoryId the memory ID to check
     * @return the retrieval mode, or null if not in cache
     */
    public RetrievalMode retrievalModeOf(String memoryId) {
        return recentRetrievalModes.get(memoryId);
    }

    // ── Graph helper methods ──

    /**
     * Finds a memory ID by approximate index. Uses the reverse index
     * to search across all tiers.
     */
    private String findMemoryByApproximateIndex(int approxIdx) {
        // Try each tier's typical record size to reverse-map
        for (MemoryType type : MemoryType.values()) {
            var layout = tierRouter.layoutFor(type);
            if (layout == null) continue;
            long offset = (long) approxIdx * layout.stride();
            String id = index.findIdByOffset(type, offset);
            if (id != null) return id;
        }
        return null;
    }

    /**
     * Adds a temporal chain result to the result set if not already present.
     */
    private void addChainResult(int chainIdx, CognitiveResult seed,
                                 Set<String> existingIds,
                                 List<CognitiveResult> allResults,
                                 float attenuation) {
        String chainId = findMemoryByApproximateIndex(chainIdx);
        if (chainId != null && !existingIds.contains(chainId)) {
            existingIds.add(chainId);
            String text = index.text(chainId);
            MemorySource source = index.source(chainId);
            String[] tags = index.tags(chainId);
            float chainScore = seed.score() * attenuation * 0.2f;
            allResults.add(new CognitiveResult(
                    chainId, text, chainScore, seed.importance(), 0f,
                    (short) 0, (byte) 0, seed.memoryType(), source,
                    tags, 1.0f, 1.0f));
        }
    }
}
