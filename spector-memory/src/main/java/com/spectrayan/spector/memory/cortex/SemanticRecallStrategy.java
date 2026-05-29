package com.spectrayan.spector.memory.cortex;

import com.spectrayan.spector.index.ScoredResult;
import com.spectrayan.spector.index.VectorIndex;
import com.spectrayan.spector.memory.CognitiveResult;
import com.spectrayan.spector.memory.MemoryType;
import com.spectrayan.spector.memory.RecallOptions;
import com.spectrayan.spector.memory.index.MemoryIndex;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout.CognitiveHeader;
import com.spectrayan.spector.memory.synapse.DecayStrategy;
import com.spectrayan.spector.memory.synapse.SynapticHeaderConstants;
import com.spectrayan.spector.memory.synapse.SynapticTagEncoder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Fused semantic recall strategy — HNSW vector search + cognitive header scoring.
 *
 * <h3>Problem (The Truncation Trap — Semantic Variant)</h3>
 * <p>The {@code SemanticMemoryStore} stores only 32-byte cognitive headers (no vectors).
 * This means flat-scanning the header slab cannot compute vector similarity — the
 * {@code alpha * similarity} term is entirely missing. Semantic recall was scoring
 * only {@code beta * importance * decay}, which is fundamentally broken for
 * similarity-based retrieval.</p>
 *
 * <h3>Solution: Fused Pipeline</h3>
 * <ol>
 *   <li>Query the {@link VectorIndex} (HNSW) for top-N candidates with similarity scores</li>
 *   <li>For each candidate, look up the cognitive header from the header slab</li>
 *   <li>Apply the full 6-phase cognitive scoring: tag gating, valence filtering,
 *       temporal decay, reconsolidation, and weighted tag relevance</li>
 *   <li>Re-rank by fused score and return</li>
 * </ol>
 *
 * <h3>Performance</h3>
 * <p>HNSW search is O(log N) vs O(N) flat scan. For 100K+ semantic memories,
 * this is orders of magnitude faster. The over-fetch multiplier (default: 3×)
 * ensures cognitive re-ranking has enough candidates to find truly relevant results.</p>
 *
 * <h3>Graceful Degradation</h3>
 * <p>If no {@code VectorIndex} is configured, the caller falls back to
 * the header-only scoring path (with the newly-added tag/valence filters).</p>
 */
public final class SemanticRecallStrategy {

    private static final Logger log = LoggerFactory.getLogger(SemanticRecallStrategy.class);

    private final VectorIndex vectorIndex;
    private final SemanticMemoryStore semanticStore;
    private final MemoryIndex memoryIndex;

    /**
     * Creates a fused semantic recall strategy.
     *
     * @param vectorIndex   the HNSW/IVF index backing semantic memory
     * @param semanticStore the header-only semantic slab
     * @param memoryIndex   the ID → metadata index for reverse lookups
     */
    public SemanticRecallStrategy(VectorIndex vectorIndex,
                                   SemanticMemoryStore semanticStore,
                                   MemoryIndex memoryIndex) {
        this.vectorIndex = vectorIndex;
        this.semanticStore = semanticStore;
        this.memoryIndex = memoryIndex;
    }

    /**
     * Executes a fused semantic recall: HNSW search → cognitive re-ranking.
     *
     * <p>Steps:</p>
     * <ol>
     *   <li>Search HNSW for {@code topK * multiplier} candidates</li>
     *   <li>For each candidate, read the cognitive header from the slab</li>
     *   <li>Apply tag gating, valence filtering, importance threshold</li>
     *   <li>Compute fused score: {@code alpha * similarity + beta * importance * decay}</li>
     *   <li>Apply weighted tag relevance boost</li>
     *   <li>Sort and return top-K</li>
     * </ol>
     *
     * @param queryVector the embedded query vector
     * @param options     recall configuration
     * @param nowMs       current timestamp for decay computation
     * @return ranked list of cognitive results
     */
    public List<CognitiveResult> recall(float[] queryVector, RecallOptions options, long nowMs) {
        int candidateCount = options.topK() * options.semanticCandidateMultiplier();
        ScoredResult[] hnswResults = vectorIndex.search(queryVector, candidateCount);

        if (hnswResults == null || hnswResults.length == 0) {
            log.debug("Semantic HNSW search returned 0 results");
            return List.of();
        }

        // Extract filter parameters
        long queryTagMask = options.synapticTagMask();
        byte minValence = options.minValence();
        byte maxValence = options.maxValence();
        float minImportance = options.minImportance();
        float alpha = options.alpha();
        float beta = options.beta();
        float tagRelevanceBoost = options.tagRelevanceBoost();

        CognitiveRecordLayout layout = semanticStore.layout();
        java.lang.foreign.MemorySegment headerSlab = semanticStore.primarySegment();

        List<CognitiveResult> results = new ArrayList<>();

        for (ScoredResult sr : hnswResults) {
            // HNSW returns an internal store index — compute header offset
            long headerOffset = (long) sr.index() * SynapticHeaderConstants.HEADER_BYTES;

            // Bounds check: ensure we're within the slab
            if (headerSlab == null || headerOffset + SynapticHeaderConstants.HEADER_BYTES > headerSlab.byteSize()) {
                continue;
            }

            CognitiveHeader header = layout.readHeader(headerSlab, headerOffset);

            // Phase 1: Tombstone check
            if (SynapticHeaderConstants.isTombstoned(header.flags())) continue;

            // Phase 2: Synaptic tag gating (skip on zero overlap)
            long recordTags = header.synapticTags();
            if (queryTagMask != 0 && (recordTags & queryTagMask) == 0) continue;

            // Phase 3: Valence filter
            byte valence = header.valence();
            if (valence < minValence || valence > maxValence) continue;

            // Phase 4: Importance threshold
            float importance = header.importance();
            if (importance < minImportance) continue;

            // Phase 5: Use HNSW similarity score directly
            float similarity = sr.score();

            // Phase 6: Temporal decay + reconsolidation
            long timestamp = header.timestampMs();
            int recallCount = header.recallCount();
            int rawBucket = DecayStrategy.ageToBucket(timestamp, nowMs);
            int adjusted = DecayStrategy.adjustForReconsolidation(rawBucket, recallCount);
            float decay = DecayStrategy.decay(adjusted);

            // Fused cognitive score with weighted tag relevance
            float baseScore = alpha * similarity + beta * importance * decay;
            float tagOverlap = SynapticTagEncoder.overlapRatio(recordTags, queryTagMask);
            float finalScore = baseScore * (1.0f + tagOverlap * tagRelevanceBoost);

            // Build result
            String id = memoryIndex.findIdByOffset(MemoryType.SEMANTIC, headerOffset);
            String text = id != null ? memoryIndex.text(id) : "";
            MemorySource source = id != null ? memoryIndex.source(id) : MemorySource.OBSERVED;
            String[] tags = id != null ? memoryIndex.tags(id) : new String[0];
            float ageDays = (nowMs - timestamp) / (1000f * 60f * 60f * 24f);
            float rawDecay = DecayStrategy.decay(rawBucket);

            results.add(new CognitiveResult(
                    id != null ? id : "semantic-" + sr.index(),
                    text, finalScore, importance, ageDays,
                    recallCount, valence, MemoryType.SEMANTIC, source,
                    tags, rawDecay, decay));
        }

        // Sort by fused score descending
        results.sort(Comparator.comparing(CognitiveResult::score).reversed());

        log.debug("Semantic fused recall: {} HNSW candidates → {} after filtering",
                hnswResults.length, results.size());

        return results;
    }

    /**
     * Returns whether this strategy has a configured vector index.
     */
    public boolean isAvailable() {
        return vectorIndex != null;
    }
}
