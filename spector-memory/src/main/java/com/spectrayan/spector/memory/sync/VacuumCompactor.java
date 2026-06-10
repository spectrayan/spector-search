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
package com.spectrayan.spector.memory.sync;

import com.spectrayan.spector.memory.cortex.AbstractTierStore;
import com.spectrayan.spector.memory.index.MemoryIndex;
import com.spectrayan.spector.memory.model.MemoryType;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout.CognitiveHeader;
import com.spectrayan.spector.memory.synapse.SynapticHeaderConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.HashMap;
import java.util.Map;

/**
 * Compacts a tier store by removing tombstoned records and reclaiming space.
 *
 * <h3>Biological Analog: Synaptic Pruning</h3>
 * <p>During sleep, the brain selectively eliminates weak synaptic connections
 * (synaptic homeostasis). The vacuum compactor is the digital equivalent —
 * removing tombstoned (forgotten) records and compacting the memory space.</p>
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>Scan the tier store, counting live vs. tombstoned records</li>
 *   <li>Allocate a new segment sized for live records only</li>
 *   <li>Copy live records sequentially (header + vector), tracking offset remapping</li>
 *   <li>Update {@link MemoryIndex} with new offsets via {@code relocateBatch()}</li>
 *   <li>Return {@link CompactionResult} with statistics</li>
 * </ol>
 *
 * <h3>Concurrency</h3>
 * <p>This compactor operates on a <b>copy-then-replace</b> model. The caller
 * is responsible for synchronizing access (e.g., holding a write lock on the store).
 * After compaction, the caller swaps the segment reference and calls
 * {@code publishVisible()} to make the compacted data visible to readers.</p>
 *
 * @see CompactionResult
 * @see MemoryIndex#relocateBatch(Map)
 */
public final class VacuumCompactor {

    private static final Logger log = LoggerFactory.getLogger(VacuumCompactor.class);

    /** Default tombstone ratio threshold for triggering compaction (20%). */
    public static final float DEFAULT_THRESHOLD = 0.20f;

    private VacuumCompactor() {} // utility class

    /**
     * Compacts a tier store by copying only live (non-tombstoned) records
     * to a new segment.
     *
     * <p>The compacted segment is allocated in a shared arena. The caller is
     * responsible for closing the old segment's arena after swapping.</p>
     *
     * @param store   the tier store to compact (must not be modified concurrently)
     * @param type    the memory tier type (for index relocation and result)
     * @param index   the memory index (for offset remapping)
     * @return the compaction result (null if no compaction needed)
     */
    public static CompactionResult compact(AbstractTierStore store, MemoryType type,
                                            MemoryIndex index) {
        long startMs = System.currentTimeMillis();

        CognitiveRecordLayout layout = store.layout();
        int totalRecords = store.size();
        long baseOffset = store.isPersistent() ? AbstractTierStore.METADATA_HEADER_BYTES : 0;
        int stride = layout.stride();

        // Phase 1: Count live and tombstoned records
        int liveCount = 0;
        int tombstoneCount = 0;
        for (int i = 0; i < totalRecords; i++) {
            long offset = baseOffset + (long) i * stride;
            CognitiveHeader header = layout.readHeader(store.segment(), offset);
            if (SynapticHeaderConstants.isTombstoned(header.flags())) {
                tombstoneCount++;
            } else {
                liveCount++;
            }
        }

        if (tombstoneCount == 0) {
            log.info("Vacuum: {} has no tombstoned records, skipping", type);
            return null;
        }

        log.info("Vacuum: {} compacting {} total records ({} live, {} tombstoned)",
                type, totalRecords, liveCount, tombstoneCount);

        // Phase 2: Allocate new segment for live records only
        long newDataBytes = (long) liveCount * stride;
        long newTotalBytes = baseOffset + newDataBytes;
        Arena newArena = Arena.ofShared();
        MemorySegment newSegment = newArena.allocate(newTotalBytes,
                SynapticHeaderConstants.HEADER_BYTES);

        // Phase 3: Copy live records sequentially, building offset remap
        Map<String, Long> relocations = new HashMap<>();
        int writeIndex = 0;

        for (int i = 0; i < totalRecords; i++) {
            long oldOffset = baseOffset + (long) i * stride;
            CognitiveHeader header = layout.readHeader(store.segment(), oldOffset);

            if (SynapticHeaderConstants.isTombstoned(header.flags())) {
                continue; // skip tombstoned
            }

            long newOffset = baseOffset + (long) writeIndex * stride;

            // Copy entire record (header + vector) via MemorySegment.copy
            MemorySegment.copy(store.segment(), ValueLayout.JAVA_BYTE, oldOffset,
                    newSegment, ValueLayout.JAVA_BYTE, newOffset, stride);

            // Find the memory ID at the old offset for index relocation
            String id = index.findIdByOffset(type, oldOffset);
            if (id != null) {
                relocations.put(id, newOffset);
            }

            writeIndex++;
        }

        // Phase 4: Update MemoryIndex with new offsets
        index.relocateBatch(relocations);

        long bytesReclaimed = (long) tombstoneCount * stride;
        long durationMs = System.currentTimeMillis() - startMs;

        CompactionResult result = new CompactionResult(
                type, totalRecords, liveCount, tombstoneCount,
                bytesReclaimed, durationMs);

        log.info("Vacuum complete: {} — removed {} tombstones, reclaimed {}KB in {}ms",
                type, tombstoneCount, bytesReclaimed / 1024, durationMs);

        // Note: The caller must swap the segment reference and close the old arena.
        // The new segment is available via newArena.

        return result;
    }

    /**
     * Checks if a tier store should be compacted based on tombstone ratio.
     *
     * @param store     the store to check
     * @param threshold the tombstone ratio threshold (e.g., 0.20 for 20%)
     * @return true if compaction is recommended
     */
    public static boolean shouldCompact(AbstractTierStore store, float threshold) {
        if (store.size() == 0) return false;
        return store.tombstoneRatio() >= threshold;
    }
}
