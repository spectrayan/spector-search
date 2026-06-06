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
package com.spectrayan.spector.memory.hippocampus;

import com.spectrayan.spector.memory.cortex.EpisodicMemoryStore;
import com.spectrayan.spector.memory.cortex.EpisodicMemoryStore.EpisodicPartition;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout.CognitiveHeader;
import com.spectrayan.spector.memory.synapse.DecayStrategy;
import com.spectrayan.spector.memory.synapse.SynapticHeaderConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Partition rebuild when tombstone ratio exceeds threshold.
 *
 * <h3>Biological Analog: Synaptic Pruning</h3>
 * <p>The brain prunes weak synaptic connections during sleep to make room for
 * new learning. Tombstone compaction is the digital equivalent — removing
 * logically-deleted records so that scans remain fast.</p>
 *
 * <h3>Design: Tombstone + Rebuild (Not In-Place Compaction)</h3>
 * <p>Moving records in-place invalidates HNSW edges and WAL references.
 * Instead, we mark records as tombstoned (1 byte flag write), then
 * rebuild entire partitions when the tombstone ratio exceeds a threshold.</p>
 *
 * <h3>V3: Full Partition Rebuild</h3>
 * <p>When compaction is triggered, a new dense partition is created containing
 * only live (non-tombstoned) records. The old partition is atomically swapped
 * out via {@link EpisodicMemoryStore#replacePartition}. An offset remap is
 * produced so callers can update their ID index entries.</p>
 */
public final class TombstoneCompactor {

    private static final Logger log = LoggerFactory.getLogger(TombstoneCompactor.class);

    private final float tombstoneThreshold;

    /**
     * Creates a compactor.
     *
     * @param tombstoneThreshold ratio above which compaction triggers (default: 0.30 = 30%)
     */
    public TombstoneCompactor(float tombstoneThreshold) {
        this.tombstoneThreshold = tombstoneThreshold;
    }

    /**
     * Checks if a partition should be compacted.
     */
    public boolean shouldCompact(EpisodicPartition partition) {
        return partition.tombstoneRatio() > tombstoneThreshold;
    }

    /**
     * Scans a partition and tombstones records whose decayed score falls below
     * the prune threshold.
     *
     * @param partition       the episodic partition to scan
     * @param pruneThreshold  minimum decay score to survive (records below are tombstoned)
     * @param nowMs           current time in epoch millis
     * @return number of records tombstoned in this pass
     */
    public int pruneDecayed(EpisodicPartition partition, float pruneThreshold, long nowMs) {
        int pruned = 0;
        CognitiveRecordLayout layout = partition.layout();
        MemorySegment segment = partition.segment();
        int count = partition.count();

        for (int i = 0; i < count; i++) {
            long offset = partition.recordOffset(i);

            byte flags = layout.readFlags(segment, offset);
            if (SynapticHeaderConstants.isTombstoned(flags)) continue;

            // Don't prune pinned memories
            if (SynapticHeaderConstants.isPinned(flags)) continue;

            long timestamp = layout.readTimestamp(segment, offset);
            int agentRecallCount = layout.readAgentRecallCount(segment, offset);
            float importance = layout.readImportance(segment, offset);

            float decay = DecayStrategy.computeDecay(timestamp, nowMs, agentRecallCount);
            float score = importance * decay;

            if (score < pruneThreshold) {
                layout.tombstone(segment, offset);
                partition.incrementTombstoneCount();
                pruned++;
            }
        }

        if (pruned > 0) {
            log.info("Deep Sleep: tombstoned {} records in partition {} (threshold={})",
                    pruned, partition.path(), pruneThreshold);
        }

        return pruned;
    }

    /**
     * Compacts a partition by copying all live (non-tombstoned) records into a
     * new, dense partition.
     *
     * <p>The new partition is created at {@code basePath/episodic-{key}-compacted.mem}
     * with capacity equal to the live record count. All live records are copied
     * sequentially, producing a dense, gap-free partition.</p>
     *
     * @param source   the partition to compact
     * @param basePath the episodic store base directory
     * @param key      the partition key (e.g., "20260526")
     * @return the compacted partition, or null if there are no live records
     */
    public EpisodicPartition compact(EpisodicPartition source, Path basePath, String key) {
        CognitiveRecordLayout layout = source.layout();
        MemorySegment srcSegment = source.segment();
        int srcCount = source.count();

        // Count live records
        int liveCount = 0;
        for (int i = 0; i < srcCount; i++) {
            long offset = source.recordOffset(i);
            byte flags = layout.readFlags(srcSegment, offset);
            if (!SynapticHeaderConstants.isTombstoned(flags)) {
                liveCount++;
            }
        }

        if (liveCount == 0) {
            log.info("Compaction: partition {} has no live records — skipping", key);
            return null;
        }

        // Create new dense store as the compacted partition
        Path compactedPath = basePath.resolve("episodic-compacted.mem");
        EpisodicMemoryStore compactedStore = new EpisodicMemoryStore(
                compactedPath, layout.quantizedVecBytes(), liveCount);
        EpisodicPartition compacted = compactedStore.partitions().getFirst();

        // Copy live records
        int copied = 0;
        for (int i = 0; i < srcCount; i++) {
            long srcOffset = source.recordOffset(i);
            byte flags = layout.readFlags(srcSegment, srcOffset);
            if (SynapticHeaderConstants.isTombstoned(flags)) continue;

            // Read header from source
            CognitiveHeader header = layout.readHeader(srcSegment, srcOffset);

            // Read quantized vector from source
            byte[] quantizedVec = new byte[layout.quantizedVecBytes()];
            MemorySegment.copy(srcSegment, layout.vectorOffset(srcOffset),
                    MemorySegment.ofArray(quantizedVec), 0,
                    quantizedVec.length);

            // Write to compacted partition
            compacted.append(header, quantizedVec);
            copied++;
        }

        log.info("Compaction complete: partition {} — {} → {} records (removed {} tombstones)",
                key, srcCount, copied, srcCount - copied);

        return compacted;
    }

    /**
     * Builds an offset remap for updating the ID index after compaction.
     *
     * <p>Returns a map of {@code oldOffset → newOffset} for all live records.
     * Callers use this to update their {@code MemoryLocation} entries.</p>
     *
     * @param source   the original partition (before compaction)
     * @param compacted the compacted partition (after compaction)
     * @return map of old byte offsets to new byte offsets
     */
    public Map<Long, Long> buildOffsetRemap(EpisodicPartition source, EpisodicPartition compacted) {
        CognitiveRecordLayout layout = source.layout();
        MemorySegment srcSegment = source.segment();
        int srcCount = source.count();

        Map<Long, Long> remap = new HashMap<>();
        int destIndex = 0;

        for (int i = 0; i < srcCount; i++) {
            long srcOffset = source.recordOffset(i);
            byte flags = layout.readFlags(srcSegment, srcOffset);
            if (SynapticHeaderConstants.isTombstoned(flags)) continue;

            long destOffset = compacted.recordOffset(destIndex);
            remap.put(srcOffset, destOffset);
            destIndex++;
        }

        return remap;
    }
}
