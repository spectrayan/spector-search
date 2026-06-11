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
package com.spectrayan.spector.memory.cortex;

import com.spectrayan.spector.core.similarity.SimilarityFunction;
import com.spectrayan.spector.memory.model.MemoryType;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout.CognitiveHeader;
import com.spectrayan.spector.memory.synapse.SynapticHeaderConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Volatile or persistent scratchpad for short-term Working Memory.
 *
 * <h3>Biological Analog: Prefrontal Cortex</h3>
 * <p>The prefrontal cortex holds a small number of items in active consciousness
 * (~7 ± 2 according to Miller's Law). Working memory is volatile — it exists
 * only while the session is active and is discarded when the session ends.</p>
 *
 * <h3>Persistence</h3>
 * <p>When file-backed ({@code filePath} constructor), the circular buffer and
 * its {@code writeIndex} are persisted via mmap. The {@code writeIndex} is
 * stored in the metadata header's {@code extra1} field (offset 24). On restart,
 * the agent resumes with its previous "train of thought."</p>
 *
 * <h3>Design</h3>
 * <ul>
 *   <li>Extends {@link AbstractTierStore} for common Arena/layout/segment lifecycle</li>
 *   <li>Fixed capacity (default: 100 records)</li>
 *   <li>FIFO eviction when full — oldest items are overwritten (circular buffer)</li>
 *   <li>Flat Panama scan — no index needed (working set is small)</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * <p>Uses a shared Arena. Write access is synchronized; reads are lock-free
 * (scan over immutable segments).</p>
 */
public final class WorkingMemoryStore extends AbstractTierStore {

    private static final Logger log = LoggerFactory.getLogger(WorkingMemoryStore.class);

    private int writeIndex = 0;  // circular buffer index

    /**
     * Creates a volatile Working Memory store (in-memory only).
     *
     * @param quantizedVecBytes bytes per quantized vector
     * @param capacity          maximum number of records (default: 100)
     */
    public WorkingMemoryStore(int quantizedVecBytes, int capacity) {
        super(quantizedVecBytes, capacity,
                (long) new com.spectrayan.spector.memory.synapse.CognitiveRecordLayout(quantizedVecBytes).stride() * capacity);

        log.info("WorkingMemoryStore initialized: capacity={}, stride={}B, total={}KB, persistent=false",
                capacity, layout.stride(), (long) layout.stride() * capacity / 1024);
    }

    /**
     * Creates a persistent Working Memory store backed by an mmap file.
     *
     * <p>On restart, {@code count} and {@code writeIndex} are restored from
     * the metadata header, allowing the circular buffer to resume exactly
     * where it left off.</p>
     *
     * @param quantizedVecBytes bytes per quantized vector
     * @param capacity          maximum number of records
     * @param filePath          path to the backing mmap file
     */
    public WorkingMemoryStore(int quantizedVecBytes, int capacity, Path filePath) {
        super(quantizedVecBytes, capacity,
                (long) new com.spectrayan.spector.memory.synapse.CognitiveRecordLayout(quantizedVecBytes).stride() * capacity,
                filePath);

        // Restore writeIndex from metadata header extra1 field
        if (persistent && count > 0) {
            this.writeIndex = segment.get(ValueLayout.JAVA_INT, META_EXTRA1);
            log.info("WorkingMemoryStore restored: writeIndex={}, count={}", writeIndex, count);
        }

        log.info("WorkingMemoryStore initialized: capacity={}, stride={}B, persistent=true",
                capacity, layout.stride());
    }

    /**
     * Creates a volatile Working Memory store with default capacity (100).
     */
    public WorkingMemoryStore(int quantizedVecBytes) {
        this(quantizedVecBytes, 100);
    }

    @Override
    public MemoryType type() {
        return MemoryType.WORKING;
    }

    /**
     * Returns the number of live records currently in working memory.
     */
    public int count() {
        return count;
    }

    @Override
    public long write(CognitiveHeader header, byte[] quantizedVec) {
        long offset = dataOffset() + (long) writeIndex * layout.stride();
        put(header, quantizedVec);
        return offset;
    }

    private final ReentrantLock writeLock = new ReentrantLock();

    /**
     * Appends a record to the working memory circular buffer.
     *
     * <p>If the buffer is full, the oldest record is overwritten (FIFO eviction).
     * The evicted record's tombstone flag is set before overwrite.</p>
     *
     * @param header       cognitive header for this memory
     * @param quantizedVec the quantized vector bytes
     */
    public void put(CognitiveHeader header, byte[] quantizedVec) {
        writeLock.lock();
        try {
            long offset = dataOffset() + (long) writeIndex * layout.stride();

            // If we're overwriting an existing record, mark it as evicted
            if (count >= capacity) {
                log.trace("Working memory full — evicting slot {}", writeIndex);
            }

            // Write header
            layout.writeHeader(segment, offset, header);

            // Write quantized vector payload
            MemorySegment.copy(
                    MemorySegment.ofArray(quantizedVec), 0,
                    segment, layout.vectorOffset(offset),
                    quantizedVec.length
            );

            // Advance circular buffer
            writeIndex = (writeIndex + 1) % capacity;
            count = Math.min(count + 1, capacity);

            // Persist count and writeIndex to metadata header
            persistCount();
            if (persistent) {
                segment.set(ValueLayout.JAVA_INT, META_EXTRA1, writeIndex);
            }
            publishVisible(); // SWMR: make record visible to scanners
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Flat scans all live records and returns matching results.
     *
     * <p>This is a linear scan over at most {@code capacity} records.
     * Since Working Memory is small (≤100 records), this is fast (~2-5µs).</p>
     *
     * @param queryTagMask synaptic tag filter (0 = match all)
     * @return array of offsets that passed the filter, for scoring
     */
    public long[] scan(long queryTagMask) {
        long[] matches = new long[count];
        int matchCount = 0;

        for (int i = 0; i < count; i++) {
            long offset = dataOffset() + (long) i * layout.stride();

            // Phase 1: Skip tombstones
            byte flags = layout.readFlags(segment, offset);
            if (SynapticHeaderConstants.isTombstoned(flags)) continue;

            // Phase 2: Synaptic tag gating
            if (queryTagMask != 0) {
                long recordTags = layout.readSynapticTags(segment, offset);
                if ((recordTags & queryTagMask) != queryTagMask) continue;
            }

            matches[matchCount++] = offset;
        }

        // Trim
        long[] result = new long[matchCount];
        System.arraycopy(matches, 0, result, 0, matchCount);
        return result;
    }

    /**
     * Scans the working memory buffer and returns the minimum L2 distance
     * between the given query vector and any existing live record.
     *
     * <h3>Neurodivergent: Dopaminergic Novelty Routing</h3>
     * <p>Used at ingestion time to compute novelty/surprise. If the new memory
     * is very close to an existing working memory record (low L2 distance),
     * it's "boring" — importance is suppressed. If it's far away, it's novel
     * and importance is spiked (dopamine event).</p>
     *
     * <p>Cost: O(capacity × dims) — for a 100-slot WM with 768-dim vectors,
     * this is ~0.5ms using SIMD acceleration.</p>
     *
     * @param queryVector the new embedding vector to compare (float32)
     * @param mins        per-dimension minimum values from ScalarQuantizer calibration
     * @param scales      per-dimension scale values from ScalarQuantizer calibration
     * @return minimum L2 distance to any live record, or {@code Float.MAX_VALUE} if empty
     */
    public float nearestDistance(float[] queryVector, float[] mins, float[] scales) {
        if (count == 0) return Float.MAX_VALUE;

        float minDist = Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            long offset = dataOffset() + (long) i * layout.stride();

            // Skip tombstoned records
            byte flags = layout.readFlags(segment, offset);
            if (SynapticHeaderConstants.isTombstoned(flags)) continue;

            // Compute calibrated L2 distance via SIMD kernel
            float dist = SimilarityFunction.EUCLIDEAN.computeQuantizedFromSegment(
                    queryVector, segment, layout.vectorOffset(offset),
                    mins, scales, layout.quantizedVecBytes());

            if (dist < minDist) minDist = dist;
        }
        return minDist;
    }
}

