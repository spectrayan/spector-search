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

import com.spectrayan.spector.memory.model.MemoryType;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout.CognitiveHeader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import com.spectrayan.spector.memory.error.SpectorMemoryTierFullException;

/**
 * Episodic memory store — stores time-ordered personal experiences.
 *
 * <h3>Biological Analog: Hippocampus</h3>
 * <p>The hippocampus encodes events as time-ordered episodic traces. New events are
 * appended rapidly (one-trial learning), and during sleep the hippocampus replays
 * sequences for consolidation into cortical (semantic) memory.</p>
 *
 * <h3>V4 Design: Single File Per Partition</h3>
 * <p>Each colocated partition directory contains a single {@code episodic.mem} file,
 * consistent with {@code semantic.mem} and {@code procedural.mem}. Partition rolling
 * is handled by {@code DefaultSpectorMemory} at the directory level — no daily
 * sub-partitioning within a partition.</p>
 *
 * <ul>
 *   <li>Extends {@link AbstractTierStore} for common Arena/layout/segment lifecycle</li>
 *   <li>Full cognitive records — header + quantized vector in one slab</li>
 *   <li>Flat SIMD scan via the scorer</li>
 *   <li>Persistent across JVM restarts via {@code FileChannel.map()}</li>
 * </ul>
 */
public final class EpisodicMemoryStore extends AbstractTierStore {

    private static final Logger log = LoggerFactory.getLogger(EpisodicMemoryStore.class);

    /**
     * Creates a volatile Episodic Memory store (in-memory only).
     *
     * @param quantizedVecBytes bytes per quantized vector
     * @param capacity          maximum number of episodic memories
     */
    public EpisodicMemoryStore(int quantizedVecBytes, int capacity) {
        super(quantizedVecBytes, capacity,
                (long) new CognitiveRecordLayout(quantizedVecBytes).stride() * capacity);

        log.info("EpisodicMemoryStore initialized: capacity={}, stride={}B, persistent=false",
                capacity, layout.stride());
    }

    /**
     * Creates a persistent Episodic Memory store backed by an mmap file.
     *
     * @param quantizedVecBytes bytes per quantized vector
     * @param capacity          maximum number of episodic memories
     * @param filePath          path to the backing mmap file (e.g., episodic.mem)
     */
    public EpisodicMemoryStore(int quantizedVecBytes, int capacity, Path filePath) {
        super(quantizedVecBytes, capacity,
                (long) new CognitiveRecordLayout(quantizedVecBytes).stride() * capacity,
                filePath);

        log.info("EpisodicMemoryStore initialized: capacity={}, stride={}B, persistent=true, count={}",
                capacity, layout.stride(), count);
    }

    /**
     * Constructor matching the legacy 3-arg signature (basePath, vecBytes, capacity).
     *
     * <p>This constructor is used by {@link com.spectrayan.spector.memory.DefaultSpectorMemory}
     * which passes {@code StorageLayout.episodicMem(partitionDir)} as the file path.</p>
     *
     * @param filePath          path to episodic.mem file
     * @param quantizedVecBytes bytes per quantized vector
     * @param capacity          maximum records
     */
    public EpisodicMemoryStore(Path filePath, int quantizedVecBytes, int capacity) {
        this(quantizedVecBytes, capacity, filePath);
    }

    @Override
    public MemoryType type() {
        return MemoryType.EPISODIC;
    }

    @Override
    public long write(CognitiveHeader header, byte[] quantizedVec) {
        long offset = dataOffset() + (long) count * layout.stride();
        append(header, quantizedVec);
        return offset;
    }

    private final ReentrantLock writeLock = new ReentrantLock();

    /**
     * Appends a full episodic memory (header + quantized vector).
     *
     * @param header       cognitive header
     * @param quantizedVec quantized vector bytes (nullable for header-only writes)
     */
    public void append(CognitiveHeader header, byte[] quantizedVec) {
        writeLock.lock();
        try {
            if (count >= capacity) {
                throw new SpectorMemoryTierFullException("EPISODIC", capacity);
            }

            long offset = dataOffset() + (long) count * layout.stride();
            layout.writeHeader(segment, offset, header);

            if (quantizedVec != null) {
                MemorySegment.copy(
                        MemorySegment.ofArray(quantizedVec), 0,
                        segment, layout.vectorOffset(offset),
                        quantizedVec.length
                );
            }

            count++;
            persistCount();
            publishVisible(); // SWMR: make record visible to scanners
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Reads the cognitive header at the given index.
     */
    public CognitiveHeader readHeader(int index) {
        long offset = dataOffset() + (long) index * layout.stride();
        return layout.readHeader(segment, offset);
    }

    /**
     * Returns the total record count.
     */
    public int totalRecords() {
        return count;
    }

    /**
     * Returns the partition count (always 1 — single file per colocated partition).
     */
    public int partitionCount() {
        return 1;
    }

    /**
     * Computes the byte offset for record at logical index i.
     */
    public long recordOffset(int recordIndex) {
        return dataOffset() + (long) recordIndex * layout.stride();
    }

    /**
     * Returns the header slab segment for direct scorer access.
     */
    public MemorySegment headerSlab() {
        return segment;
    }

    // ══════════════════════════════════════════════════════════════
    // BACKWARD COMPATIBILITY — EpisodicPartition shim
    // ══════════════════════════════════════════════════════════════
    // ReflectDaemon, RecallPipeline, and TombstoneCompactor reference
    // EpisodicPartition. This shim wraps the store itself as a single
    // "partition" for compatibility.

    /**
     * Returns this store wrapped as a single EpisodicPartition for backward
     * compatibility with ReflectDaemon, RecallPipeline, and TombstoneCompactor.
     */
    public List<EpisodicPartition> partitions() {
        return List.of(new EpisodicPartition(this));
    }

    /**
     * Returns the key for a partition (always "default" for single-file store).
     */
    public String keyForPartition(EpisodicPartition partition) {
        return "default";
    }

    /**
     * No-op for single-file store (partition replacement not applicable).
     */
    public boolean replacePartition(String key, EpisodicPartition oldPartition,
                                     EpisodicPartition newPartition) {
        log.debug("replacePartition called on single-file store — no-op");
        return false;
    }

    /**
     * Compatibility shim wrapping the EpisodicMemoryStore as a single "partition".
     *
     * <p>Used by ReflectDaemon, RecallPipeline, and TombstoneCompactor which
     * iterate over episodic partitions. In the new architecture, there is always
     * exactly one partition per colocated directory.</p>
     */
    public static final class EpisodicPartition {

        /** Size of the metadata header in bytes (matches AbstractTierStore). */
        public static final int METADATA_HEADER_BYTES = AbstractTierStore.METADATA_HEADER_BYTES;

        private final EpisodicMemoryStore store;
        private int tombstoneCount = 0;

        public EpisodicPartition(EpisodicMemoryStore store) {
            this.store = store;
        }

        public int count() { return store.count; }

        /** Returns the acquire-fenced visible count for concurrent readers. */
        public int visibleCount() { return store.visibleCount(); }

        public MemorySegment segment() { return store.segment; }

        public CognitiveRecordLayout layout() { return store.layout; }

        public int capacity() { return store.capacity; }

        public long recordOffset(int recordIndex) { return store.recordOffset(recordIndex); }

        /** Returns the byte offset where data records begin (0 for volatile, 64 for persistent). */
        public long dataOffset() { return store.dataOffset(); }

        public Path path() { return store.filePath(); }

        /** Partition state — always ACTIVE for the current store. */
        public PartitionState state() { return PartitionState.ACTIVE; }

        public void seal() { /* no-op for single-file store */ }

        public void setState(PartitionState newState) { /* no-op */ }

        public int tombstoneCount() { return tombstoneCount; }

        public void incrementTombstoneCount() { tombstoneCount++; }

        public float tombstoneRatio() {
            int c = count();
            return c == 0 ? 0f : (float) tombstoneCount / c;
        }

        public void close() { /* managed by the store's own close() */ }

        public void force() { store.force(); }

        /**
         * Appends to the underlying store.
         */
        public void append(CognitiveHeader header, byte[] quantizedVec) {
            store.append(header, quantizedVec);
        }
    }

    /**
     * Partition lifecycle states (kept for backward compatibility).
     */
    public enum PartitionState {
        ACTIVE, SEALED, REFLECTABLE, TOMBSTONED, COMPACTED
    }
}
