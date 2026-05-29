package com.spectrayan.spector.memory.pipeline;

import com.spectrayan.spector.commons.concurrent.ConcurrentTasks;
import com.spectrayan.spector.commons.concurrent.ConcurrentExecutionException;
import com.spectrayan.spector.embed.EmbeddingProvider;
import com.spectrayan.spector.memory.CognitiveResult;
import com.spectrayan.spector.memory.CognitiveResult.RetrievalMode;
import com.spectrayan.spector.memory.MemoryType;
import com.spectrayan.spector.memory.RecallOptions;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

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

    private final List<RecallListener> listeners = new ArrayList<>();

    // ── Neurodivergent: Lateral feedback tracking ──
    // Maps memoryId → RetrievalMode for the most recent recall.
    // Used by SpectorMemory.reinforce()/suppress() to feed LateralEvaluator.
    // Entries expire implicitly via size cap (oldest evicted at 2000).
    private final ConcurrentHashMap<String, RetrievalMode> recentRetrievalModes
            = new ConcurrentHashMap<>();
    private static final int RETRIEVAL_MODE_CACHE_MAX = 2000;
    private RecallOptions lastRecallOptions; // for detecting hyperfocus mode

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
                prospectiveScheduler, wal, calibrationMins, calibrationScales, null);
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
    }

    /**
     * Registers a post-recall listener (Observer pattern).
     *
     * @param listener called after each successful recall with the final results
     */
    public void addListener(RecallListener listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    /**
     * Executes the full recall pipeline with parallel tier scanning.
     *
     * @param queryText the query text (will be embedded)
     * @param options   recall configuration
     * @return ranked list of cognitive results
     */
    public List<CognitiveResult> recall(String queryText, RecallOptions options) {
        Objects.requireNonNull(queryText, "queryText is required");
        if (options == null) options = RecallOptions.DEFAULT;

        log.debug("Recall query: '{}', topK={}", queryText, options.topK());
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

        // Step 5: Apply habituation penalty + inhibition of return (anti-filter-bubble)
        for (int i = 0; i < allResults.size(); i++) {
            CognitiveResult r = allResults.get(i);
            float habPenalty = habituationPenalty.recordAndComputePenalty(r.id());
            float iorPenalty = habituationPenalty.computeInhibitionOfReturn(r.id(), nowMs);
            float combinedPenalty = Math.min(habPenalty, iorPenalty); // stronger suppression wins
            if (combinedPenalty < 1.0f) {
                allResults.set(i, new CognitiveResult(
                        r.id(), r.text(), r.score() * combinedPenalty, r.importance(), r.ageDays(),
                        r.recallCount(), r.valence(), r.memoryType(), r.source(),
                        r.synapticTags(), r.decayFactor(), r.ltpAdjustedDecay()));
            }
        }

        // Step 6: Sort by score descending, limit to topK
        allResults.sort(Comparator.comparing(CognitiveResult::score).reversed());
        if (allResults.size() > options.topK()) {
            allResults = new ArrayList<>(allResults.subList(0, options.topK()));
        }

        // Step 7: Fire async post-recall listeners (LTP reconsolidation + Hebbian)
        if (!listeners.isEmpty()) {
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

        // Step 8: Record recall timestamps for Inhibition of Return
        long recallTs = System.currentTimeMillis();
        for (CognitiveResult r : allResults) {
            habituationPenalty.recordRecall(r.id(), recallTs);
        }

        log.debug("Recall returned {} results for '{}'", allResults.size(), queryText);

        // Cache retrieval modes for lateral feedback (reinforce/suppress)
        if (recentRetrievalModes.size() > RETRIEVAL_MODE_CACHE_MAX) {
            recentRetrievalModes.clear(); // simple eviction — reset when full
        }
        for (CognitiveResult r : allResults) {
            if (r.id() != null) {
                recentRetrievalModes.put(r.id(), r.retrievalMode());
            }
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
        if (TierRouter.shouldScan(MemoryType.SEMANTIC, targetTypes)
                && tierRouter.semantic().size() > 0) {
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
        if (TierRouter.shouldScan(MemoryType.SEMANTIC, targetTypes)
                && tierRouter.semantic().size() > 0) {
            if (semanticRecallStrategy != null && semanticRecallStrategy.isAvailable()) {
                results.addAll(semanticRecallStrategy.recall(queryVector, options, nowMs));
            } else {
                results.addAll(scoreHeaderSlabToList(tierRouter.semantic().headerSlab(),
                        tierRouter.semantic().size(), tierRouter.semantic().layout(),
                        queryVector, options, nowMs));
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
            long offset = (long) i * SynapticHeaderConstants.HEADER_BYTES;
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

        return new CognitiveResult(
                id != null ? id : "unknown-" + sr.index(),
                text, sr.score(), header.importance(), ageDays,
                header.recallCount(), header.valence(), type, source,
                tags, rawDecay, ltpDecay, mode
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
}
