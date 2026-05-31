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
package com.spectrayan.spector.memory.hebbian;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Off-heap open-addressing hash table for <b>directed</b> STDP edges.
 *
 * <h3>Biological Analog: Spike-Timing-Dependent Plasticity</h3>
 * <p>If neuron A fires <b>before</b> neuron B (causal), the A→B synapse is
 * strengthened (LTP). If A fires <b>after</b> B (anti-causal), the B→A
 * synapse is weakened (LTD). This produces predictive associations.</p>
 *
 * <h3>Slot Layout (40 bytes, 8-byte aligned)</h3>
 * <pre>
 *   [srcHash:8B][tgtHash:8B][weight:4B][pad:4B][lastActivatedMs:8B][activationCount:4B][flags:4B]
 * </pre>
 *
 * <h3>Thread Safety</h3>
 * <p>Writes are guarded by a {@link ReentrantLock}. Reads are lock-free.</p>
 *
 * @see CoActivationTracker
 */
final class OffHeapEdgeTable {

    private static final Logger log = LoggerFactory.getLogger(OffHeapEdgeTable.class);

    // ── Slot layout ──
    static final int SLOT_BYTES = 40;
    static final long OFF_SRC = 0;
    static final long OFF_TGT = 8;
    static final long OFF_WEIGHT = 16;
    // pad: 4B at offset 20 for alignment
    static final long OFF_LAST_MS = 24;
    static final long OFF_ACT_COUNT = 32;
    static final long OFF_FLAGS = 36;

    /** Flag: slot is occupied. */
    static final int FLAG_OCCUPIED = 1;

    /** Minimum weight (prevent complete erasure). */
    static final float MIN_WEIGHT = 0.0f;

    /** Maximum weight (prevent runaway potentiation). */
    static final float MAX_WEIGHT = 1.0f;

    private final MemorySegment segment;
    private final int capacity;
    private final ReentrantLock writeLock = new ReentrantLock();
    private volatile int count;

    // ── Construction ──

    /**
     * Creates a new empty edge table.
     *
     * @param capacity number of hash table slots (must be power of 2)
     * @param arena    arena to allocate from
     */
    OffHeapEdgeTable(int capacity, Arena arena) {
        this.capacity = capacity;
        this.segment = arena.allocate((long) SLOT_BYTES * capacity);
        this.segment.fill((byte) 0);
        this.count = 0;
    }

    /**
     * Wraps a pre-loaded segment (used during deserialization).
     */
    OffHeapEdgeTable(int capacity, MemorySegment segment, int count) {
        this.capacity = capacity;
        this.segment = segment;
        this.count = count;
    }

    // ── Writes (locked) ──

    /**
     * Updates or inserts a directed STDP edge.
     *
     * @param srcHash     FNV-1a hash of the source tag
     * @param tgtHash     FNV-1a hash of the target tag
     * @param deltaWeight weight change (positive = LTP, negative = LTD)
     * @param nowMs       current epoch millis
     */
    void update(long srcHash, long tgtHash, float deltaWeight, long nowMs) {
        writeLock.lock();
        try {
            int slot = findSlot(srcHash, tgtHash);

            if (slot >= 0) {
                // Exists — update
                long offset = (long) slot * SLOT_BYTES;
                float weight = segment.get(ValueLayout.JAVA_FLOAT, offset + OFF_WEIGHT);
                float newWeight = Math.clamp(weight + deltaWeight, MIN_WEIGHT, MAX_WEIGHT);
                int actCount = segment.get(ValueLayout.JAVA_INT, offset + OFF_ACT_COUNT);

                segment.set(ValueLayout.JAVA_FLOAT, offset + OFF_WEIGHT, newWeight);
                segment.set(ValueLayout.JAVA_LONG, offset + OFF_LAST_MS, nowMs);
                segment.set(ValueLayout.JAVA_INT, offset + OFF_ACT_COUNT, actCount + 1);
            } else {
                // Insert
                int insertSlot = ~slot;
                if (insertSlot < 0 || count >= capacity / 2) {
                    pruneWeakest();
                    slot = findSlot(srcHash, tgtHash);
                    insertSlot = slot >= 0 ? slot : ~slot;
                    if (insertSlot < 0) return;
                }

                long offset = (long) insertSlot * SLOT_BYTES;
                float initialWeight = Math.max(MIN_WEIGHT, deltaWeight);
                segment.set(ValueLayout.JAVA_LONG, offset + OFF_SRC, srcHash);
                segment.set(ValueLayout.JAVA_LONG, offset + OFF_TGT, tgtHash);
                segment.set(ValueLayout.JAVA_FLOAT, offset + OFF_WEIGHT, initialWeight);
                segment.set(ValueLayout.JAVA_LONG, offset + OFF_LAST_MS, nowMs);
                segment.set(ValueLayout.JAVA_INT, offset + OFF_ACT_COUNT, 1);
                segment.set(ValueLayout.JAVA_INT, offset + OFF_FLAGS, FLAG_OCCUPIED);
                count++;
            }
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Resets all edge data. Caller must hold no other locks.
     */
    void reset() {
        writeLock.lock();
        try {
            segment.fill((byte) 0);
            count = 0;
        } finally {
            writeLock.unlock();
        }
    }

    // ── Reads (lock-free) ──

    /**
     * Returns the STDP weight for a specific directed edge, or -1 if absent.
     */
    float getWeight(long srcHash, long tgtHash) {
        int slot = findSlot(srcHash, tgtHash);
        if (slot < 0) return -1f;
        long offset = (long) slot * SLOT_BYTES;
        return segment.get(ValueLayout.JAVA_FLOAT, offset + OFF_WEIGHT);
    }

    /**
     * Returns full edge metadata, or null if absent.
     */
    CoActivationTracker.EdgeWeight getEdge(long srcHash, long tgtHash) {
        int slot = findSlot(srcHash, tgtHash);
        if (slot < 0) return null;
        long offset = (long) slot * SLOT_BYTES;
        float weight = segment.get(ValueLayout.JAVA_FLOAT, offset + OFF_WEIGHT);
        long lastMs = segment.get(ValueLayout.JAVA_LONG, offset + OFF_LAST_MS);
        int actCount = segment.get(ValueLayout.JAVA_INT, offset + OFF_ACT_COUNT);
        return new CoActivationTracker.EdgeWeight(weight, lastMs, actCount);
    }

    int count() { return count; }
    int capacity() { return capacity; }
    MemorySegment segment() { return segment; }

    // ── Hash Table Internals ──

    /**
     * Finds an edge slot by source and target hashes.
     *
     * @return slot index if found, or ~insertionPoint if not found
     */
    private int findSlot(long srcHash, long tgtHash) {
        int mask = capacity - 1;
        int idx = (int) ((srcHash * 0x517CC1B727220A95L + tgtHash) & mask);

        for (int probe = 0; probe < capacity; probe++) {
            int slot = (idx + probe) & mask;
            long offset = (long) slot * SLOT_BYTES;
            int flags = segment.get(ValueLayout.JAVA_INT, offset + OFF_FLAGS);

            if ((flags & FLAG_OCCUPIED) == 0) return ~slot;
            long s = segment.get(ValueLayout.JAVA_LONG, offset + OFF_SRC);
            long t = segment.get(ValueLayout.JAVA_LONG, offset + OFF_TGT);
            if (s == srcHash && t == tgtHash) return slot;
        }
        return -1;
    }

    /**
     * Prunes the weakest 10% of edges by weight. Must be called under writeLock.
     */
    private void pruneWeakest() {
        if (count == 0) return;
        int toPrune = Math.max(1, count / 10);

        float[] weights = new float[count];
        int idx = 0;
        for (int i = 0; i < capacity && idx < count; i++) {
            long offset = (long) i * SLOT_BYTES;
            int flags = segment.get(ValueLayout.JAVA_INT, offset + OFF_FLAGS);
            if ((flags & FLAG_OCCUPIED) != 0) {
                weights[idx++] = segment.get(ValueLayout.JAVA_FLOAT, offset + OFF_WEIGHT);
            }
        }
        Arrays.sort(weights, 0, idx);
        float threshold = idx > toPrune ? weights[toPrune] : weights[0];

        int removed = 0;
        for (int i = 0; i < capacity && removed < toPrune; i++) {
            long offset = (long) i * SLOT_BYTES;
            int flags = segment.get(ValueLayout.JAVA_INT, offset + OFF_FLAGS);
            if ((flags & FLAG_OCCUPIED) != 0) {
                float weight = segment.get(ValueLayout.JAVA_FLOAT, offset + OFF_WEIGHT);
                if (weight <= threshold) {
                    for (int b = 0; b < SLOT_BYTES; b += 4) {
                        segment.set(ValueLayout.JAVA_INT, offset + b, 0);
                    }
                    removed++;
                    count--;
                }
            }
        }

        log.debug("Pruned {} weak STDP edges (remaining={})", removed, count);
    }

    // ── Persistence helpers ──

    void writeTo(FileChannel ch) throws IOException {
        long totalBytes = (long) SLOT_BYTES * capacity;
        long written = 0;
        int chunkSize = 64 * 1024;
        while (written < totalBytes) {
            int toWrite = (int) Math.min(chunkSize, totalBytes - written);
            ByteBuffer buf = segment.asSlice(written, toWrite).asByteBuffer().asReadOnlyBuffer();
            ch.write(buf);
            written += toWrite;
        }
    }

    static OffHeapEdgeTable readFrom(FileChannel ch, int capacity, int count, Arena arena)
            throws IOException {
        long totalBytes = (long) SLOT_BYTES * capacity;
        MemorySegment seg = arena.allocate(totalBytes);
        long read = 0;
        int chunkSize = 64 * 1024;
        while (read < totalBytes) {
            int toRead = (int) Math.min(chunkSize, totalBytes - read);
            ByteBuffer buf = ByteBuffer.allocate(toRead);
            ch.read(buf);
            buf.flip();
            MemorySegment.copy(MemorySegment.ofBuffer(buf), 0, seg, read, toRead);
            read += toRead;
        }
        return new OffHeapEdgeTable(capacity, seg, count);
    }
}
