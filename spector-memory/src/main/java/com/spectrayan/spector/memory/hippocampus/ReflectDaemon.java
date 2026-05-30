package com.spectrayan.spector.memory.hippocampus;

import com.spectrayan.spector.commons.concurrent.ConcurrentTasks;
import com.spectrayan.spector.commons.concurrent.ConcurrentExecutionException;
import com.spectrayan.spector.memory.MemoryType;
import com.spectrayan.spector.memory.ReflectReport;
import com.spectrayan.spector.memory.cortex.CentroidRouter;
import com.spectrayan.spector.memory.cortex.EpisodicMemoryStore;
import com.spectrayan.spector.memory.cortex.EpisodicMemoryStore.EpisodicPartition;
import com.spectrayan.spector.memory.cortex.SemanticMemoryStore;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout.CognitiveHeader;
import com.spectrayan.spector.memory.synapse.SynapticHeaderConstants;
import com.spectrayan.spector.embed.EmbeddingProvider;
import com.spectrayan.spector.embed.TextGenerationProvider;
import com.spectrayan.spector.embed.GenerationOptions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Background Virtual Thread that runs the two-phase sleep consolidation cycle.
 *
 * <h3>Biological Analog: Hippocampal Replay During Sleep</h3>
 * <p>During sleep, the hippocampus replays episodic memories to the neocortex
 * for consolidation. Dense clusters of related episodes are compressed into
 * permanent semantic facts. Weak, isolated memories are pruned.</p>
 *
 * <h3>Two-Phase Sleep Cycle</h3>
 * <ol>
 *   <li><b>Deep Sleep (Synaptic Pruning):</b>
 *       Scan episodic partitions, tombstone records where decayed score &lt; threshold,
 *       trigger compaction when partition exceeds tombstone threshold.</li>
 *   <li><b>REM Sleep (Dreaming/Synthesis):</b>
 *       Cluster episodic memories by IVF centroid proximity. For each dense cluster,
 *       either synthesize a semantic fact via LLM (if {@code TextGenerationProvider}
 *       is available) or promote the highest-importance representative.</li>
 * </ol>
 *
 * <h3>V3: IVF Centroid Clustering + LLM Synthesis</h3>
 * <ul>
 *   <li>Groups non-consolidated episodic records by {@code centroid_id}</li>
 *   <li>Processes clusters ≥ {@code minClusterSize} (default: 5)</li>
 *   <li>Extracts common synaptic tags via bitmap AND</li>
 *   <li>When {@code TextGenerationProvider} is available:
 *       sends cluster texts to LLM for factual summarization</li>
 *   <li>When no LLM: falls back to highest-importance selection</li>
 * </ul>
 */
public final class ReflectDaemon {

    private static final Logger log = LoggerFactory.getLogger(ReflectDaemon.class);

    /** Default minimum cluster size for REM consolidation. */
    private static final int DEFAULT_MIN_CLUSTER_SIZE = 5;

    private final CircadianPolicy policy;
    private final TombstoneCompactor compactor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // ── Optional providers (null = graceful fallback to basic behavior) ──
    private final CentroidRouter centroidRouter;
    private final TextGenerationProvider textGenerator;
    private final EmbeddingProvider embeddingProvider;
    private final int minClusterSize;

    // ── Neurodivergent: Lossless Consolidation ──
    private final boolean pinSourceEpisodes;
    private final int pinnedQuota;
    private int pinnedCount = 0; // tracks pinned records across cycles

    /**
     * Creates a ReflectDaemon with full V3 capabilities.
     *
     * @param policy             circadian policy for trigger configuration
     * @param centroidRouter     centroid router for IVF clustering (null = basic fallback)
     * @param textGenerator      LLM for cluster synthesis (null = promote highest importance)
     * @param embeddingProvider  embedding provider for synthesized text (null = skip embedding)
     * @param minClusterSize     minimum cluster size for consolidation (default: 5)
     */
    public ReflectDaemon(CircadianPolicy policy, CentroidRouter centroidRouter,
                          TextGenerationProvider textGenerator, EmbeddingProvider embeddingProvider,
                          int minClusterSize, boolean pinSourceEpisodes, int pinnedQuota) {
        this.policy = policy;
        this.compactor = new TombstoneCompactor(policy.tombstoneThreshold());
        this.centroidRouter = centroidRouter;
        this.textGenerator = textGenerator;
        this.embeddingProvider = embeddingProvider;
        this.minClusterSize = minClusterSize;
        this.pinSourceEpisodes = pinSourceEpisodes;
        this.pinnedQuota = pinnedQuota;
    }

    /**
     * Creates a ReflectDaemon with full V3 capabilities (no lossless consolidation).
     */
    public ReflectDaemon(CircadianPolicy policy, CentroidRouter centroidRouter,
                          TextGenerationProvider textGenerator, EmbeddingProvider embeddingProvider,
                          int minClusterSize) {
        this(policy, centroidRouter, textGenerator, embeddingProvider,
                minClusterSize, false, 10_000);
    }

    /**
     * Creates a ReflectDaemon with optional V3 providers and default cluster size.
     */
    public ReflectDaemon(CircadianPolicy policy, CentroidRouter centroidRouter,
                          TextGenerationProvider textGenerator, EmbeddingProvider embeddingProvider) {
        this(policy, centroidRouter, textGenerator, embeddingProvider, DEFAULT_MIN_CLUSTER_SIZE);
    }

    /**
     * Creates a ReflectDaemon with basic behavior (no clustering, no LLM).
     */
    public ReflectDaemon(CircadianPolicy policy) {
        this(policy, null, null, null, DEFAULT_MIN_CLUSTER_SIZE);
    }

    /**
     * Creates a ReflectDaemon with default policy.
     */
    public ReflectDaemon() {
        this(CircadianPolicy.DEFAULT);
    }

    /**
     * Runs a single synchronous reflection cycle.
     *
     * <p>Backward-compatible overload — delegates with null
     * text lookup (falls back to basic behavior).</p>
     *
     * @param episodicStore the episodic memory store to scan
     * @param semanticStore the semantic store to promote into (may be null for basic mode)
     * @return report summarizing what was done
     */
    public ReflectReport runCycle(EpisodicMemoryStore episodicStore,
                                   SemanticMemoryStore semanticStore) {
        return runCycle(episodicStore, semanticStore, null);
    }

    /**
     * Runs a single synchronous reflection cycle with text lookup for IVF clustering.
     *
     * @param episodicStore the episodic memory store to scan
     * @param semanticStore the semantic store to promote into
     * @param textLookup    function to look up text by memory offset (nullable)
     * @return report summarizing what was done
     */
    public ReflectReport runCycle(EpisodicMemoryStore episodicStore,
                                   SemanticMemoryStore semanticStore,
                                   Function<Long, String> textLookup) {
        if (!running.compareAndSet(false, true)) {
            log.warn("Reflection cycle already in progress — skipping");
            return ReflectReport.EMPTY;
        }

        Instant start = Instant.now();
        int totalTombstoned = 0;
        int totalCompacted = 0;
        int totalConsolidated = 0;

        try {
            long nowMs = System.currentTimeMillis();

            // ── Phase 1: Deep Sleep (Synaptic Pruning) — parallel partitions ──
            log.info("Deep Sleep starting — scanning {} partitions",
                    episodicStore.partitionCount());

            List<EpisodicPartition> allPartitions = episodicStore.partitions();
            
            // Native POSIX Optimization: advise sequential access on all episodic segments before scan
            for (EpisodicPartition partition : allPartitions) {
                if (partition.segment() != null && partition.segment().isMapped()) {
                    com.spectrayan.spector.commons.concurrent.NativeOsMemory.advise(partition.segment(), com.spectrayan.spector.commons.concurrent.NativeOsMemory.MADV_SEQUENTIAL);
                }
            }

            try {
                // Parallel prune: each partition scanned on its own Virtual Thread
                List<Callable<Integer>> pruneTasks = new ArrayList<>(allPartitions.size());
                for (EpisodicPartition partition : allPartitions) {
                    pruneTasks.add(() -> compactor.pruneDecayed(partition,
                            policy.decayPruneThreshold(), nowMs));
                }
                List<Integer> prunedCounts = ConcurrentTasks.forkJoinAll(pruneTasks);
                for (int p : prunedCounts) totalTombstoned += p;
            } catch (ConcurrentExecutionException | InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Parallel prune failed, falling back to sequential: {}", e.getMessage());
                for (EpisodicPartition partition : allPartitions) {
                    totalTombstoned += compactor.pruneDecayed(partition,
                            policy.decayPruneThreshold(), nowMs);
                }
            }

            // Compaction check (sequential — involves atomic partition swap)
            for (EpisodicPartition partition : allPartitions) {
                if (compactor.shouldCompact(partition)) {
                    String key = episodicStore.keyForPartition(partition);
                    log.info("Partition {} exceeds tombstone threshold ({:.0f}%) — compacting",
                            partition.path(), partition.tombstoneRatio() * 100);

                    if (key != null) {
                        EpisodicPartition compacted = compactor.compact(
                                partition, episodicStore.partitions().getFirst().path().getParent(), key);
                        if (compacted != null) {
                            episodicStore.replacePartition(key, partition, compacted);
                            totalCompacted++;

                            // Native POSIX Optimization: Immediately release old partition segment page cache
                            if (partition.segment() != null && partition.segment().isMapped()) {
                                com.spectrayan.spector.commons.concurrent.NativeOsMemory.advise(partition.segment(), com.spectrayan.spector.commons.concurrent.NativeOsMemory.MADV_DONTNEED);
                            }
                        }
                    }
                }
            }

            // ── Phase 2: REM Sleep (Dreaming/Synthesis) — parallel partitions ──
            log.info("REM Sleep starting — looking for dense episodic clusters");

            try {
                List<Callable<Integer>> remTasks = new ArrayList<>(allPartitions.size());
                for (EpisodicPartition partition : episodicStore.partitions()) {
                    remTasks.add(() -> {
                        if (centroidRouter != null) {
                            return clusterAndSynthesize(partition, semanticStore, textLookup);
                        } else {
                            return promoteHighestImportance(partition, semanticStore);
                        }
                    });
                }
                List<Integer> consolidated = ConcurrentTasks.forkJoinAll(remTasks);
                for (int c : consolidated) totalConsolidated += c;
            } catch (ConcurrentExecutionException | InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Parallel REM failed, falling back to sequential: {}", e.getMessage());
                for (EpisodicPartition partition : episodicStore.partitions()) {
                    int promoted = centroidRouter != null
                            ? clusterAndSynthesize(partition, semanticStore, textLookup)
                            : promoteHighestImportance(partition, semanticStore);
                    totalConsolidated += promoted;
                }
            }

            Duration elapsed = Duration.between(start, Instant.now());
            log.info("Reflection complete: consolidated={}, tombstoned={}, compacted={}, duration={}ms",
                    totalConsolidated, totalTombstoned, totalCompacted, elapsed.toMillis());

            // Native POSIX Optimization: Release page-cache for all episodic segments once sleep consolidation is fully complete
            for (EpisodicPartition partition : allPartitions) {
                if (partition.segment() != null && partition.segment().isMapped()) {
                    com.spectrayan.spector.commons.concurrent.NativeOsMemory.advise(partition.segment(), com.spectrayan.spector.commons.concurrent.NativeOsMemory.MADV_DONTNEED);
                }
            }

            return new ReflectReport(totalConsolidated, totalTombstoned,
                    totalCompacted, elapsed);

        } finally {
            running.set(false);
        }
    }

    // ── V3: IVF Centroid-Based Clustering + LLM Synthesis ──

    /**
     * Clusters non-consolidated records by centroid ID and promotes dense clusters.
     *
     * <p>Algorithm:</p>
     * <ol>
     *   <li>Group records by {@code centroid_id} (read from header at offset 24)</li>
     *   <li>Filter: only process clusters ≥ {@code minClusterSize}</li>
     *   <li>For each dense cluster:
     *     <ul>
     *       <li>Compute common synaptic tags via bitmap AND</li>
     *       <li>If TextGenerationProvider available: synthesize factual summary</li>
     *       <li>If no LLM: select highest-importance record as representative</li>
     *     </ul>
     *   </li>
     *   <li>Promote into Semantic tier with {@code MemorySource.REFLECTED}</li>
     *   <li>Mark all cluster members as consolidated</li>
     * </ol>
     */
    private int clusterAndSynthesize(EpisodicPartition partition,
                                      SemanticMemoryStore semanticStore,
                                      Function<Long, String> textLookup) {
        if (semanticStore == null || partition.count() == 0) return 0;

        CognitiveRecordLayout layout = partition.layout();
        var segment = partition.segment();
        int count = partition.count();

        // Step 1: Group non-consolidated records by centroid ID
        Map<Integer, List<Integer>> centroidClusters = new HashMap<>();

        for (int i = 0; i < count; i++) {
            long offset = partition.recordOffset(i);
            byte flags = layout.readFlags(segment, offset);

            if (SynapticHeaderConstants.isTombstoned(flags)) continue;
            if (SynapticHeaderConstants.isConsolidated(flags)) continue;

            CognitiveHeader header = layout.readHeader(segment, offset);
            int centroidId = header.centroidId();

            centroidClusters.computeIfAbsent(centroidId, k -> new ArrayList<>()).add(i);
        }

        // Step 2: Process dense clusters
        int totalPromoted = 0;

        for (Map.Entry<Integer, List<Integer>> entry : centroidClusters.entrySet()) {
            List<Integer> clusterIndices = entry.getValue();
            if (clusterIndices.size() < minClusterSize) continue;

            int centroidId = entry.getKey();
            log.debug("REM: Processing cluster {} ({} records)", centroidId, clusterIndices.size());

            // Step 2.5: Proactive Interference — decay near-duplicates within cluster
            int degraded = applyProactiveInterference(partition, clusterIndices);
            if (degraded > 0) {
                log.debug("REM: Cluster {} — {} near-duplicates had importance decayed",
                        centroidId, degraded);
            }

            // Step 3: Compute common synaptic tags (bitmap AND across cluster)
            long commonTags = ~0L; // start with all bits set
            float maxImportance = -1f;
            int bestIndex = -1;
            List<String> clusterTexts = new ArrayList<>();

            for (int idx : clusterIndices) {
                long offset = partition.recordOffset(idx);
                CognitiveHeader header = layout.readHeader(segment, offset);

                commonTags &= header.synapticTags();

                if (header.importance() > maxImportance) {
                    maxImportance = header.importance();
                    bestIndex = idx;
                }

                // Collect text for LLM synthesis
                if (textLookup != null) {
                    String text = textLookup.apply(offset);
                    if (text != null && !text.isEmpty()) {
                        clusterTexts.add(text);
                    }
                }
            }

            if (bestIndex < 0) continue;

            // Step 4: Synthesize or select representative
            CognitiveHeader promotedHeader;

            if (textGenerator != null && !clusterTexts.isEmpty() && embeddingProvider != null) {
                // V3: LLM-based synthesis
                promotedHeader = synthesizeWithLlm(clusterTexts, commonTags, maxImportance);
            } else {
                // Fallback: promote highest-importance record (no LLM available)
                long bestOffset = partition.recordOffset(bestIndex);
                CognitiveHeader episodicHeader = layout.readHeader(segment, bestOffset);
                promotedHeader = createSemanticHeader(episodicHeader, commonTags);
            }

            if (promotedHeader != null) {
                semanticStore.store(promotedHeader);
                totalPromoted++;

                // Step 5: Mark all cluster members as consolidated
                for (int idx : clusterIndices) {
                    long offset = partition.recordOffset(idx);
                    layout.markConsolidated(segment, offset);

                    // Neurodivergent: Lossless consolidation — pin source episodes
                    // to preserve encyclopedic detail alongside the semantic fact.
                    if (pinSourceEpisodes && pinnedCount < pinnedQuota) {
                        layout.pin(segment, offset);
                        pinnedCount++;
                    }
                }

                log.debug("REM: Cluster {} consolidated ({} records → 1 semantic fact, importance={})",
                        centroidId, clusterIndices.size(), maxImportance);
            }
        }

        return totalPromoted;
    }

    // ── Proactive Interference ──

    /** Maximum records to compare per cluster (bounds O(N²) cost). */
    private static final int MAX_INTERFERENCE_CANDIDATES = 20;

    /**
     * Proactive Interference — competitive degradation of near-duplicate memories.
     *
     * <h3>Biological Analog</h3>
     * <p>New memories overwrite old similar ones (retroactive interference). In the
     * brain, similar memories compete for the same neural pathways. The newer,
     * more recently encoded memory wins, and the older one fades.</p>
     *
     * <h3>Implementation</h3>
     * <p>Within each centroid cluster, finds pairs of records within
     * {@code interferenceThreshold} L2 distance. For each pair, the older
     * record's importance is multiplied by {@code interferenceDecayFactor}
     * (default: 0.7 = 30% reduction per cycle). This is less violent than
     * halving recall_count — the old memory fades naturally via importance
     * decay rather than losing its entire recall history.</p>
     *
     * <h3>Performance</h3>
     * <p>Caps comparisons at the top-{@value #MAX_INTERFERENCE_CANDIDATES}
     * records by importance (descending) to bound the O(N²/cluster) cost.
     * For a cluster of 50 records, this reduces comparisons from 1,225 to 190.</p>
     *
     * @param partition       the episodic partition being processed
     * @param clusterIndices  indices of records in this centroid cluster
     * @return count of records whose importance was decayed
     */
    private int applyProactiveInterference(EpisodicPartition partition,
                                            List<Integer> clusterIndices) {
        if (clusterIndices.size() < 2) return 0;

        CognitiveRecordLayout layout = partition.layout();
        var segment = partition.segment();
        float threshold = policy.interferenceThreshold();
        float decayFactor = policy.interferenceDecayFactor();

        // Select top candidates by importance (cap at MAX_INTERFERENCE_CANDIDATES)
        List<Integer> candidates;
        if (clusterIndices.size() <= MAX_INTERFERENCE_CANDIDATES) {
            candidates = clusterIndices;
        } else {
            // Sort a copy by importance descending, take top N
            candidates = new ArrayList<>(clusterIndices);
            candidates.sort((a, b) -> {
                float ia = layout.readImportance(segment, partition.recordOffset(a));
                float ib = layout.readImportance(segment, partition.recordOffset(b));
                return Float.compare(ib, ia); // descending
            });
            candidates = candidates.subList(0, MAX_INTERFERENCE_CANDIDATES);
        }

        int degradedCount = 0;

        for (int i = 0; i < candidates.size(); i++) {
            long offsetA = partition.recordOffset(candidates.get(i));
            CognitiveHeader headerA = layout.readHeader(segment, offsetA);
            if (SynapticHeaderConstants.isTombstoned(headerA.flags())) continue;

            for (int j = i + 1; j < candidates.size(); j++) {
                long offsetB = partition.recordOffset(candidates.get(j));
                CognitiveHeader headerB = layout.readHeader(segment, offsetB);
                if (SynapticHeaderConstants.isTombstoned(headerB.flags())) continue;

                // Compute L2 distance between quantized vectors.
                // Read A's quantized bytes → dequantize to float[] → compare against B.
                // This allocates a float[] per pair, acceptable since this runs during sleep.
                int vecBytes = layout.quantizedVecBytes();
                float[] vecA = new float[vecBytes];
                long vecOffsetA = layout.vectorOffset(offsetA);
                for (int d = 0; d < vecBytes; d++) {
                    vecA[d] = (segment.get(java.lang.foreign.ValueLayout.JAVA_BYTE, vecOffsetA + d) & 0xFF);
                }
                // Use identity calibration (both vectors quantized the same way)
                float[] identityMins = com.spectrayan.spector.memory.synapse.IdentityCalibration.mins(vecBytes);
                float[] identityScales = com.spectrayan.spector.memory.synapse.IdentityCalibration.scales(vecBytes);
                float dist = com.spectrayan.spector.core.similarity.SimilarityFunction.EUCLIDEAN
                        .computeQuantizedFromSegment(vecA, segment, layout.vectorOffset(offsetB),
                                identityMins, identityScales, vecBytes);

                if (dist <= threshold) {
                    // Near-duplicate: decay the OLDER one's importance
                    long tsA = headerA.timestampMs();
                    long tsB = headerB.timestampMs();

                    long olderOffset = tsA <= tsB ? offsetA : offsetB;
                    float olderImportance = layout.readImportance(segment, olderOffset);
                    float decayed = olderImportance * decayFactor;

                    layout.writeImportance(segment, olderOffset, decayed);
                    degradedCount++;

                    log.trace("Proactive interference: decayed importance at offset {} " +
                            "from {} → {} (L2={}, threshold={})",
                            olderOffset, olderImportance, decayed, dist, threshold);
                }
            }
        }

        return degradedCount;
    }

    /**
     * Synthesizes a semantic fact from cluster texts using the LLM.
     */
    private CognitiveHeader synthesizeWithLlm(List<String> clusterTexts, long commonTags,
                                                float maxImportance) {
        try {
            // Build prompt
            String memoriesText = clusterTexts.stream()
                    .limit(10) // cap at 10 memories to avoid token overflow
                    .collect(Collectors.joining("\n- ", "- ", ""));

            String prompt = String.format(
                    "Summarize these %d related episodic memories into a single factual statement. " +
                    "Be concise and factual.\n\nMemories:\n%s\n\nFactual summary:",
                    clusterTexts.size(), memoriesText);

            String synthesized = textGenerator.generate(prompt, GenerationOptions.CONCISE);

            if (synthesized == null || synthesized.isBlank()) {
                log.warn("REM: LLM returned empty synthesis — falling back to selection");
                return null;
            }

            log.debug("REM: LLM synthesized: '{}'", synthesized.substring(0, Math.min(100, synthesized.length())));

            // Build semantic header for the synthesized fact
            // Embed synthesized text to compute exactNorm (if embedding provider available)
            float exactNorm = 1.0f;
            if (embeddingProvider != null) {
                try {
                    float[] vec = embeddingProvider.embed(synthesized).vector();
                    float sum = 0f;
                    for (float v : vec) sum += v * v;
                    exactNorm = (float) Math.sqrt(sum);
                } catch (Exception e) {
                    log.warn("REM: Failed to embed synthesized text: {}", e.getMessage());
                }
            }

            byte semanticFlags = SynapticHeaderConstants.withMemoryType(
                    SynapticHeaderConstants.FLAG_CONSOLIDATED,
                    MemoryType.SEMANTIC.ordinal());

            return new CognitiveHeader(
                    System.currentTimeMillis(), commonTags, exactNorm, maxImportance,
                    0, (short) 0, (byte) 0, semanticFlags);

        } catch (Exception e) {
            log.warn("REM: LLM synthesis failed: {} — falling back to selection", e.getMessage());
            return null;
        }
    }

    /**
     * Creates a SEMANTIC-type header from an episodic header, with consolidated flag.
     */
    private CognitiveHeader createSemanticHeader(CognitiveHeader episodicHeader, long commonTags) {
        byte semanticFlags = SynapticHeaderConstants.withMemoryType(
                (byte) (episodicHeader.flags() | SynapticHeaderConstants.FLAG_CONSOLIDATED),
                MemoryType.SEMANTIC.ordinal());

        return new CognitiveHeader(
                episodicHeader.timestampMs(),
                commonTags != 0 ? commonTags : episodicHeader.synapticTags(),
                episodicHeader.exactNorm(),
                episodicHeader.importance(),
                episodicHeader.recallCount(),
                episodicHeader.centroidId(),
                episodicHeader.valence(),
                semanticFlags);
    }

    // ── Simple Highest-Importance Promotion (fallback path) ──

    /**
     * Promotes the highest-importance non-consolidated memory from a partition
     * into the semantic store. Used as fallback when clustering is not configured.
     */
    private int promoteHighestImportance(EpisodicPartition partition,
                                          SemanticMemoryStore semanticStore) {
        if (semanticStore == null || partition.count() == 0) return 0;

        CognitiveRecordLayout layout = partition.layout();
        var segment = partition.segment();
        int count = partition.count();

        float maxImportance = -1f;
        int bestIndex = -1;

        for (int i = 0; i < count; i++) {
            long offset = partition.recordOffset(i);
            byte flags = layout.readFlags(segment, offset);

            // Skip tombstoned and already-consolidated
            if (SynapticHeaderConstants.isTombstoned(flags)) continue;
            if (SynapticHeaderConstants.isConsolidated(flags)) continue;

            float importance = layout.readImportance(segment, offset);
            if (importance > maxImportance) {
                maxImportance = importance;
                bestIndex = i;
            }
        }

        if (bestIndex >= 0 && maxImportance >= 1.0f) {
            long offset = partition.recordOffset(bestIndex);

            // Read the header and re-create as SEMANTIC type
            CognitiveHeader episodicHeader = layout.readHeader(segment, offset);
            CognitiveHeader semanticHeader = createSemanticHeader(episodicHeader,
                    episodicHeader.synapticTags());

            semanticStore.store(semanticHeader);

            // Mark the episodic original as consolidated
            layout.markConsolidated(segment, offset);

            // Neurodivergent: Lossless consolidation — pin promoted source
            if (pinSourceEpisodes && pinnedCount < pinnedQuota) {
                layout.pin(segment, offset);
                pinnedCount++;
            }

            log.debug("REM: Promoted episodic record {} to semantic (importance={})",
                    bestIndex, maxImportance);
            return 1;
        }

        return 0;
    }

    /**
     * Returns whether a reflection cycle is currently running.
     */
    public boolean isRunning() {
        return running.get();
    }
}
