package com.spectrayan.spector.memory.synapse;

import com.spectrayan.spector.core.similarity.SimilarityFunction;
import com.spectrayan.spector.memory.RecallOptions;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout.CognitiveHeader;


import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

import static com.spectrayan.spector.memory.synapse.SynapticHeaderConstants.*;

/**
 * Fused SIMD cognitive scoring loop — the heart of Spector Memory's performance.
 *
 * <h3>6-Phase Pipeline</h3>
 * <pre>
 *   Phase 1: Tombstone check          (~1 cycle)   — skip deleted records
 *   Phase 2: Synaptic tag gating      (~1 cycle)   — Bloom filter pre-screen
 *   Phase 3: Valence filter           (~2 cycles)  — skip outside valence range
 *   Phase 4: Temporal/importance pre-screen         — skip stale, low-importance
 *   Phase 5: Vector distance          (~200 cycles) — calibrated INT8 L2 distance
 *   Phase 6: Fused cognitive score    (~7 cycles)   — α·similarity + β·importance·decay
 * </pre>
 *
 * <h3>Biological Analog: Sensory Gating + Fused Retrieval</h3>
 * <p>The brain doesn't consider every memory for every retrieval. It gates early —
 * suppressing irrelevant memories before the expensive associative search begins.
 * This scorer replicates that: phases 1–4 eliminate 99% of records before the
 * expensive vector distance computation in phase 5.</p>
 *
 * <h3>Distance Computation</h3>
 * <p>Phase 5 delegates to {@link SimilarityFunction#computeQuantizedFromSegment},
 * the same zero-copy off-heap SIMD kernel used by {@code spector-storage} and
 * {@code spector-index}. When calibration parameters ({@code mins[]}/{@code scales[]})
 * are provided (from {@link com.spectrayan.spector.core.quantization.ScalarQuantizer}),
 * the distance computation uses proper per-dimension affine dequantization.
 * In uncalibrated mode, identity transform ({@code min=0, scale=1/255}) is used.</p>
 *
 * <h3>Performance</h3>
 * <p>With 1M episodic memories and 1% tag match rate:
 * Phases 1-4 eliminate 990K records in ~1ms → Phase 5 computes distance on ~10K
 * → Total ~2ms (vs ~200ms without gating).</p>
 */
public final class CognitiveScorer {

    private CognitiveScorer() {}

    /**
     * Represents a scored record for the priority queue.
     *
     * <p>Carries the full {@link CognitiveHeader} to avoid a second off-heap read
     * during result assembly (P8 performance optimization).</p>
     *
     * @param lateral true if this record came from the lateral retrieval heap
     */
    public record ScoredRecord(long offset, float score, int index, CognitiveHeader header, boolean lateral)
            implements Comparable<ScoredRecord> {

        /** Standard (non-lateral) constructor for backward compatibility. */
        public ScoredRecord(long offset, float score, int index, CognitiveHeader header) {
            this(offset, score, index, header, false);
        }

        @Override
        public int compareTo(ScoredRecord other) {
            return Float.compare(this.score, other.score); // min-heap for top-K
        }
    }

    /**
     * Scans a memory segment and returns the top-K scored records.
     * Uses uncalibrated identity quantization (for backward compatibility and tests).
     *
     * @param segment       off-heap segment containing cognitive records
     * @param recordCount   number of records in the segment
     * @param layout        cognitive record layout
     * @param queryVector   the query vector (float32)
     * @param options       recall options (topK, filters, weights)
     * @param nowMs         current time in epoch millis
     * @return top-K scored records sorted by descending score
     */
    public static List<ScoredRecord> score(MemorySegment segment, int recordCount,
                                            CognitiveRecordLayout layout,
                                            float[] queryVector, RecallOptions options,
                                            long nowMs) {
        return score(segment, recordCount, layout, queryVector, options, nowMs, 0L, null, null);
    }

    /**
     * Scans a memory segment and returns the top-K scored records.
     * Uses uncalibrated identity quantization with a base offset.
     *
     * @param segment       off-heap segment containing cognitive records
     * @param recordCount   number of records in the segment
     * @param layout        cognitive record layout
     * @param queryVector   the query vector (float32)
     * @param options       recall options (topK, filters, weights)
     * @param nowMs         current time in epoch millis
     * @param baseOffset    byte offset where records begin (e.g., metadata header size for mmap partitions)
     * @return top-K scored records sorted by descending score
     */
    public static List<ScoredRecord> score(MemorySegment segment, int recordCount,
                                            CognitiveRecordLayout layout,
                                            float[] queryVector, RecallOptions options,
                                            long nowMs, long baseOffset) {
        return score(segment, recordCount, layout, queryVector, options, nowMs, baseOffset, null, null);
    }

    /**
     * Scans a memory segment and returns the top-K scored records using calibrated
     * {@link SimilarityFunction#computeQuantizedFromSegment} for distance computation.
     *
     * <p>When {@code mins} and {@code scales} are provided (from
     * {@link com.spectrayan.spector.core.quantization.ScalarQuantizer}), the distance
     * uses proper per-dimension affine dequantization: {@code value = unsigned_byte * scale[i] + min[i]}.
     * When null, falls back to identity transform for backward compatibility.</p>
     *
     * @param segment       off-heap segment containing cognitive records
     * @param recordCount   number of records in the segment
     * @param layout        cognitive record layout
     * @param queryVector   the query vector (float32)
     * @param options       recall options (topK, filters, weights)
     * @param nowMs         current time in epoch millis
     * @param baseOffset    byte offset where records begin (e.g., metadata header size for mmap partitions)
     * @param mins          per-dimension minimum values from ScalarQuantizer calibration (null = identity)
     * @param scales        per-dimension scale values from ScalarQuantizer calibration (null = identity)
     * @return top-K scored records sorted by descending score
     */
    public static List<ScoredRecord> score(MemorySegment segment, int recordCount,
                                            CognitiveRecordLayout layout,
                                            float[] queryVector, RecallOptions options,
                                            long nowMs, long baseOffset,
                                            float[] mins, float[] scales) {
        int topK = options.topK();
        long queryTagMask = options.synapticTagMask();
        float minImportance = options.minImportance();
        byte minValence = options.minValence();
        byte maxValence = options.maxValence();
        float alpha = options.alpha();
        float beta = options.beta();
        float tagRelevanceBoost = options.tagRelevanceBoost();

        // ── Neurodivergent: Hyperfocus parameters ──
        long hyperfocusMask = options.hyperfocusMask();
        float hyperfocusBoost = options.hyperfocusBoost();

        // ── Neurodivergent: Lateral retrieval parameters ──
        boolean lateralMode = options.lateralMode();
        float lateralDistanceThreshold = options.lateralDistanceThreshold();
        int lateralMaxResults = options.lateralMaxResults();
        float lateralMinTagOverlap = options.lateralMinTagOverlap();

        // Resolve calibration: use identity transform if not calibrated
        int dims = queryVector.length;
        float[] effectiveMins = mins != null ? mins : IdentityCalibration.mins(dims);
        float[] effectiveScales = scales != null ? scales : IdentityCalibration.scales(dims);

        // Min-heap for top-K tracking (standard results)
        PriorityQueue<ScoredRecord> heap = new PriorityQueue<>(topK + 1);

        // Lateral heap: separate collection for cross-domain candidates
        PriorityQueue<ScoredRecord> lateralHeap = lateralMode
                ? new PriorityQueue<>(lateralMaxResults + 1)
                : null;

        int stride = layout.stride();

        for (int i = 0; i < recordCount; i++) {
            long offset = baseOffset + (long) i * stride;

            // ── Phase 1: Tombstone check (~1 cycle) ──
            byte flags = segment.get(LAYOUT_FLAGS, offset + OFFSET_FLAGS);
            if (isTombstoned(flags)) continue;

            // ── Phase 2: Synaptic tag gating (~1 cycle) ──
            // Neurodivergent: Hyperfocus uses STRICT equality (all mask bits must match).
            // Standard mode uses containment (any overlap passes).
            long recordTags = 0;
            if (hyperfocusMask != 0) {
                // Hyperfocus: strict equality gate — reject anything that doesn't
                // match ALL focus tags. This creates a "tunnel" that blocks off-topic noise.
                recordTags = segment.get(LAYOUT_SYNAPTIC_TAGS, offset + OFFSET_SYNAPTIC_TAGS);
                if ((recordTags & hyperfocusMask) != hyperfocusMask) continue;
            } else if (queryTagMask != 0) {
                // Standard: broadened containment — skip only on zero overlap
                recordTags = segment.get(LAYOUT_SYNAPTIC_TAGS, offset + OFFSET_SYNAPTIC_TAGS);
                if ((recordTags & queryTagMask) == 0) continue;
            }

            // ── Phase 3: Valence filter (~2 cycles) ──
            byte valence = segment.get(LAYOUT_VALENCE, offset + OFFSET_VALENCE);
            if (valence < minValence || valence > maxValence) continue;

            // ── Phase 4: Temporal/importance pre-screen with reconsolidation ──
            float importance = segment.get(LAYOUT_IMPORTANCE, offset + OFFSET_IMPORTANCE);
            if (importance < minImportance) continue;

            long timestamp = segment.get(LAYOUT_TIMESTAMP, offset + OFFSET_TIMESTAMP);
            int recallCount = segment.get(LAYOUT_RECALL_COUNT, offset + OFFSET_RECALL_COUNT);
            int rawBucket = DecayStrategy.ageToBucket(timestamp, nowMs);
            int adjustedBucket = DecayStrategy.adjustForReconsolidation(rawBucket, recallCount);

            // Neurodivergent: Hyperfocus — clamp decay to 1.0 (zero time effect)
            // for memories that match the focus mask.
            boolean focusMatch = hyperfocusMask != 0
                    && (recordTags & hyperfocusMask) == hyperfocusMask;
            if (focusMatch) {
                adjustedBucket = 0; // time ceases to exist for this topic
            }

            // Skip if too old AND low importance (pinned memories are exempt)
            if (adjustedBucket >= DecayStrategy.MAX_BUCKET
                    && importance < 1.0f
                    && !isPinned(flags)) {
                continue;
            }

            // ── Phase 5: Calibrated INT8 L2 distance via SimilarityFunction ──
            float l2dist = SimilarityFunction.EUCLIDEAN.computeQuantizedFromSegment(
                    queryVector, segment, layout.vectorOffset(offset),
                    effectiveMins, effectiveScales, layout.quantizedVecBytes());

            // ── Phase 6: Fused cognitive score with weighted tag relevance (~7 cycles) ──
            float tagOverlap = SynapticTagEncoder.overlapRatio(recordTags, queryTagMask);

            // Neurodivergent: Lateral retrieval — collect tag-matched but
            // semantically distant candidates into a separate heap.
            if (lateralMode && l2dist > lateralDistanceThreshold
                    && tagOverlap >= lateralMinTagOverlap) {
                // Lateral scoring: bounded [0,1] via 1/(1 + 1/l2dist)
                // Higher L2 distance → higher lateral score (inverted relationship)
                float lateralSimilarity = 1.0f / (1.0f + 1.0f / l2dist);
                float decay = DecayStrategy.decay(adjustedBucket);
                float lateralScore = lateralSimilarity * tagOverlap * importance * decay;

                // Build header for lateral candidate
                long synapticTags = recordTags;
                float exactNorm = segment.get(LAYOUT_EXACT_NORM, offset + OFFSET_EXACT_NORM);
                short centroidId = segment.get(LAYOUT_CENTROID_ID, offset + OFFSET_CENTROID_ID);
                CognitiveHeader header = new CognitiveHeader(
                        timestamp, synapticTags, exactNorm, importance,
                        recallCount, centroidId, valence, flags);

                if (lateralHeap.size() < lateralMaxResults) {
                    lateralHeap.offer(new ScoredRecord(offset, lateralScore, i, header, true));
                } else if (lateralScore > lateralHeap.peek().score()) {
                    lateralHeap.poll();
                    lateralHeap.offer(new ScoredRecord(offset, lateralScore, i, header, true));
                }
                // Lateral candidates are NOT also added to the standard heap.
                // They are blended post-loop.
                continue;
            }

            // Standard scoring path
            float similarity = 1.0f / (1.0f + l2dist);
            float decay = DecayStrategy.decay(adjustedBucket);
            float baseScore = alpha * similarity + beta * importance * decay;

            // Weighted tag relevance: partial matches get proportional boost
            float finalScore = baseScore * (1.0f + tagOverlap * tagRelevanceBoost);

            // Neurodivergent: Hyperfocus — post-score boost for focus-matched memories
            if (focusMatch && hyperfocusBoost != 1.0f) {
                finalScore *= hyperfocusBoost;
            }

            // Build header from already-read fields + 2 remaining (avoids double-read)
            long synapticTags = queryTagMask != 0 || hyperfocusMask != 0
                    ? recordTags  // already read in Phase 2
                    : 0;
            float exactNorm = segment.get(LAYOUT_EXACT_NORM, offset + OFFSET_EXACT_NORM);
            short centroidId = segment.get(LAYOUT_CENTROID_ID, offset + OFFSET_CENTROID_ID);
            CognitiveHeader header = new CognitiveHeader(
                    timestamp, synapticTags, exactNorm, importance,
                    recallCount, centroidId, valence, flags);

            // Insert into top-K min-heap
            if (heap.size() < topK) {
                heap.offer(new ScoredRecord(offset, finalScore, i, header));
            } else if (finalScore > heap.peek().score()) {
                heap.poll();
                heap.offer(new ScoredRecord(offset, finalScore, i, header));
            }
        }

        // Merge standard + lateral results
        List<ScoredRecord> results = new ArrayList<>(heap);
        if (lateralHeap != null && !lateralHeap.isEmpty()) {
            results.addAll(lateralHeap);
        }
        results.sort(Comparator.comparing(ScoredRecord::score).reversed());
        return results;
    }
}
