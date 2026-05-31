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
package com.spectrayan.spector.memory.interference;

import com.spectrayan.spector.core.similarity.SimilarityFunction;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout.CognitiveHeader;
import com.spectrayan.spector.memory.synapse.IdentityCalibration;
import com.spectrayan.spector.memory.synapse.SynapticHeaderConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.util.Optional;

/**
 * Merge-on-ingest semantic deduplication.
 *
 * <h3>Biological Analog: Memory Interference</h3>
 * <p>Old memories interfere with learning new ones (proactive interference), and
 * new memories overwrite old ones (retroactive interference). You can't remember
 * your old phone number after learning a new one.</p>
 *
 * <h3>Implementation</h3>
 * <p>During ingestion, if a new memory's vector falls within a tight L2 radius
 * of an existing one in the same tier, don't create a duplicate — merge:
 * refresh timestamp, bump importance, OR the Bloom filters.</p>
 *
 * <h3>Distance Computation</h3>
 * <p>Delegates to {@link SimilarityFunction#computeQuantizedFromSegment} — the
 * same zero-copy off-heap kernel used by {@code CognitiveScorer}, {@code spector-storage},
 * and {@code spector-index}. Accepts optional calibration parameters from
 * {@link com.spectrayan.spector.core.quantization.ScalarQuantizer} for accurate
 * per-dimension affine dequantization.</p>
 */
public final class SemanticDeduplicator {

    private static final Logger log = LoggerFactory.getLogger(SemanticDeduplicator.class);

    private final float deduplicationRadius;

    /**
     * Creates a deduplicator.
     *
     * @param deduplicationRadius L2 distance threshold for considering two memories
     *                           as duplicates (default: 0.05)
     */
    public SemanticDeduplicator(float deduplicationRadius) {
        this.deduplicationRadius = deduplicationRadius;
    }

    /**
     * Creates a deduplicator with default radius (0.05).
     */
    public SemanticDeduplicator() {
        this(0.05f);
    }

    /**
     * Checks if a new vector is a near-duplicate of any existing record in the segment.
     * Uses uncalibrated identity quantization.
     *
     * @param newVector     the new memory's vector
     * @param segment       existing records segment
     * @param recordCount   number of existing records
     * @param layout        cognitive record layout
     * @return the index of the nearest duplicate (if within radius), or empty
     */
    public Optional<Integer> findDuplicate(float[] newVector, MemorySegment segment,
                                            int recordCount, CognitiveRecordLayout layout) {
        return findDuplicate(newVector, segment, recordCount, layout, 0L, null, null);
    }

    /**
     * Checks if a new vector is a near-duplicate of any existing record in the segment.
     * Uses uncalibrated identity quantization with a base offset.
     *
     * @param newVector     the new memory's vector
     * @param segment       existing records segment
     * @param recordCount   number of existing records
     * @param layout        cognitive record layout
     * @param baseOffset    byte offset where records begin (e.g., metadata header size)
     * @return the index of the nearest duplicate (if within radius), or empty
     */
    public Optional<Integer> findDuplicate(float[] newVector, MemorySegment segment,
                                            int recordCount, CognitiveRecordLayout layout,
                                            long baseOffset) {
        return findDuplicate(newVector, segment, recordCount, layout, baseOffset, null, null);
    }

    /**
     * Checks if a new vector is a near-duplicate of any existing record in the segment
     * using calibrated {@link SimilarityFunction#computeQuantizedFromSegment} distance.
     *
     * <p>When {@code mins} and {@code scales} are provided (from
     * {@link com.spectrayan.spector.core.quantization.ScalarQuantizer}), the distance
     * uses proper per-dimension affine dequantization. When null, falls back to
     * identity transform.</p>
     *
     * @param newVector     the new memory's vector
     * @param segment       existing records segment
     * @param recordCount   number of existing records
     * @param layout        cognitive record layout
     * @param baseOffset    byte offset where records begin (e.g., metadata header size)
     * @param mins          per-dimension minimum values from ScalarQuantizer calibration (null = identity)
     * @param scales        per-dimension scale values from ScalarQuantizer calibration (null = identity)
     * @return the index of the nearest duplicate (if within radius), or empty
     */
    public Optional<Integer> findDuplicate(float[] newVector, MemorySegment segment,
                                            int recordCount, CognitiveRecordLayout layout,
                                            long baseOffset, float[] mins, float[] scales) {
        float minDistance = Float.MAX_VALUE;
        int minIndex = -1;

        int stride = layout.stride();
        int dims = newVector.length;

        // Resolve calibration: use identity transform if not calibrated
        float[] effectiveMins = mins != null ? mins : IdentityCalibration.mins(dims);
        float[] effectiveScales = scales != null ? scales : IdentityCalibration.scales(dims);

        for (int i = 0; i < recordCount; i++) {
            long offset = baseOffset + (long) i * stride;

            // Skip tombstoned records
            byte flags = segment.get(SynapticHeaderConstants.LAYOUT_FLAGS,
                    offset + SynapticHeaderConstants.OFFSET_FLAGS);
            if (SynapticHeaderConstants.isTombstoned(flags)) continue;

            // Compute calibrated L2 distance via SimilarityFunction
            float dist = SimilarityFunction.EUCLIDEAN.computeQuantizedFromSegment(
                    newVector, segment, layout.vectorOffset(offset),
                    effectiveMins, effectiveScales, layout.quantizedVecBytes());

            if (dist < minDistance) {
                minDistance = dist;
                minIndex = i;
            }
        }

        if (minIndex >= 0 && minDistance <= deduplicationRadius) {
            log.debug("Deduplication: found near-duplicate at index {} (L2={})",
                    minIndex, minDistance);
            return Optional.of(minIndex);
        }

        return Optional.empty();
    }

    /**
     * Merges a new header's metadata into an existing record (retroactive interference).
     *
     * <p>Updates: timestamp (refresh), importance (max), synaptic tags (OR).</p>
     */
    public void merge(MemorySegment segment, long offset, CognitiveRecordLayout layout,
                       CognitiveHeader newHeader) {
        // Refresh timestamp to current time
        layout.writeTimestamp(segment, offset, newHeader.timestampMs());

        // Bump importance: max(old, new)
        float existingImportance = layout.readImportance(segment, offset);
        float mergedImportance = Math.max(existingImportance, newHeader.importance());
        layout.writeImportance(segment, offset, mergedImportance);

        // OR synaptic tags
        layout.mergeSynapticTags(segment, offset, newHeader.synapticTags());

        log.debug("Merged memory at offset {}: importance {} → {}", offset,
                existingImportance, mergedImportance);
    }

}
