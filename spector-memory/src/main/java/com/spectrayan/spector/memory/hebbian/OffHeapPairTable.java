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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Off-heap open-addressing hash table for <b>undirected</b> co-activation pairs.
 *
 * <h3>Biological Analog</h3>
 * <p>"Cells that fire together wire together" (Hebb, 1949). Each entry
 * records how many times two synaptic tags were recalled together.</p>
 *
 * <h3>Slot Layout (32 bytes, 8-byte aligned)</h3>
 * <pre>
 *   [hashA:8B][hashB:8B][count:4B][flags:4B][pad:8B]
 * </pre>
 *
 * <h3>Thread Safety</h3>
 * <p>Writes are guarded by a {@link ReentrantLock}. Reads are lock-free
 * (may see slightly stale data — acceptable for soft-scoring signals).</p>
 *
 * @see CoActivationTracker
 */
final class OffHeapPairTable {

    private static final Logger log = LoggerFactory.getLogger(OffHeapPairTable.class);

    // ── Slot layout ──
    static final int SLOT_BYTES = 32;
    static final long OFF_HASH_A = 0;
    static final long OFF_HASH_B = 8;
    static final long OFF_COUNT = 16;
    static final long OFF_FLAGS = 20;

    /** Flag: slot is occupied. */
    static final int FLAG_OCCUPIED = 1;

    private final MemorySegment segment;
    private final int capacity;
    private final ReentrantLock writeLock = new ReentrantLock();
    private volatile int count;

    // ── Construction ──

    /**
     * Creates a new empty pair table.
     *
     * @param capacity number of hash table slots (must be power of 2)
     * @param arena    arena to allocate from
     */
    OffHeapPairTable(int capacity, Arena arena) {
        this.capacity = capacity;
        this.segment = arena.allocate((long) SLOT_BYTES * capacity);
        this.segment.fill((byte) 0);
        this.count = 0;
    }

    /**
     * Wraps a pre-loaded segment (used during deserialization).
     */
    OffHeapPairTable(int capacity, MemorySegment segment, int count) {
        this.capacity = capacity;
        this.segment = segment;
        this.count = count;
    }

    // ── Writes (locked) ──

    /**
     * Records a co-activation for the given canonical hash pair.
     * The caller must ensure hashA &lt;= hashB for canonical ordering.
     */
    void increment(long hashA, long hashB) {
        writeLock.lock();
        try {
            int slot = findSlot(hashA, hashB);

            if (slot >= 0) {
                long offset = (long) slot * SLOT_BYTES;
                int c = segment.get(ValueLayout.JAVA_INT, offset + OFF_COUNT);
                segment.set(ValueLayout.JAVA_INT, offset + OFF_COUNT, c + 1);
            } else {
                int insertSlot = ~slot;
                if (insertSlot < 0 || count >= capacity / 2) {
                    pruneWeakest();
                    slot = findSlot(hashA, hashB);
                    insertSlot = slot >= 0 ? slot : ~slot;
                    if (insertSlot < 0) return;
                }

                long offset = (long) insertSlot * SLOT_BYTES;
                segment.set(ValueLayout.JAVA_LONG, offset + OFF_HASH_A, hashA);
                segment.set(ValueLayout.JAVA_LONG, offset + OFF_HASH_B, hashB);
                segment.set(ValueLayout.JAVA_INT, offset + OFF_COUNT, 1);
                segment.set(ValueLayout.JAVA_INT, offset + OFF_FLAGS, FLAG_OCCUPIED);
                count++;
            }
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Resets all pair data. Caller must hold no other locks.
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
     * Returns the co-activation count for a canonical hash pair, or 0 if absent.
     */
    int get(long hashA, long hashB) {
        int slot = findSlot(hashA, hashB);
        if (slot < 0) return 0;
        long offset = (long) slot * SLOT_BYTES;
        return segment.get(ValueLayout.JAVA_INT, offset + OFF_COUNT);
    }

    /**
     * Scans all occupied slots for pairs containing {@code tagHash} and
     * returns associated (otherHash, count) pairs.
     */
    List<long[]> findAssociations(long tagHash) {
        List<long[]> results = new ArrayList<>();
        for (int i = 0; i < capacity; i++) {
            long offset = (long) i * SLOT_BYTES;
            int flags = segment.get(ValueLayout.JAVA_INT, offset + OFF_FLAGS);
            if ((flags & FLAG_OCCUPIED) == 0) continue;

            long hA = segment.get(ValueLayout.JAVA_LONG, offset + OFF_HASH_A);
            long hB = segment.get(ValueLayout.JAVA_LONG, offset + OFF_HASH_B);

            if (hA == tagHash || hB == tagHash) {
                long otherHash = (hA == tagHash) ? hB : hA;
                int c = segment.get(ValueLayout.JAVA_INT, offset + OFF_COUNT);
                results.add(new long[]{otherHash, c});
            }
        }
        return results;
    }

    int count() { return count; }
    int capacity() { return capacity; }
    MemorySegment segment() { return segment; }

    // ── Hash Table Internals ──

    /**
     * Finds a pair slot by hash keys.
     *
     * @return slot index if found, or ~insertionPoint if not found
     */
    private int findSlot(long hashA, long hashB) {
        int mask = capacity - 1;
        int idx = (int) ((hashA * 0x9E3779B97F4A7C15L + hashB) & mask);

        for (int probe = 0; probe < capacity; probe++) {
            int slot = (idx + probe) & mask;
            long offset = (long) slot * SLOT_BYTES;
            int flags = segment.get(ValueLayout.JAVA_INT, offset + OFF_FLAGS);

            if ((flags & FLAG_OCCUPIED) == 0) return ~slot;
            long a = segment.get(ValueLayout.JAVA_LONG, offset + OFF_HASH_A);
            long b = segment.get(ValueLayout.JAVA_LONG, offset + OFF_HASH_B);
            if (a == hashA && b == hashB) return slot;
        }
        return -1;
    }

    /**
     * Prunes the weakest 10% of pairs by count. Must be called under writeLock.
     */
    private void pruneWeakest() {
        if (count == 0) return;
        int toPrune = Math.max(1, count / 10);

        int[] counts = new int[count];
        int idx = 0;
        for (int i = 0; i < capacity && idx < count; i++) {
            long offset = (long) i * SLOT_BYTES;
            int flags = segment.get(ValueLayout.JAVA_INT, offset + OFF_FLAGS);
            if ((flags & FLAG_OCCUPIED) != 0) {
                counts[idx++] = segment.get(ValueLayout.JAVA_INT, offset + OFF_COUNT);
            }
        }
        Arrays.sort(counts, 0, idx);
        int threshold = idx > toPrune ? counts[toPrune] : counts[0];

        int removed = 0;
        for (int i = 0; i < capacity && removed < toPrune; i++) {
            long offset = (long) i * SLOT_BYTES;
            int flags = segment.get(ValueLayout.JAVA_INT, offset + OFF_FLAGS);
            if ((flags & FLAG_OCCUPIED) != 0) {
                int c = segment.get(ValueLayout.JAVA_INT, offset + OFF_COUNT);
                if (c <= threshold) {
                    segment.set(ValueLayout.JAVA_INT, offset + OFF_FLAGS, 0);
                    segment.set(ValueLayout.JAVA_LONG, offset + OFF_HASH_A, 0L);
                    segment.set(ValueLayout.JAVA_LONG, offset + OFF_HASH_B, 0L);
                    segment.set(ValueLayout.JAVA_INT, offset + OFF_COUNT, 0);
                    removed++;
                    count--;
                }
            }
        }

        log.debug("Pruned {} weak co-activation pairs (remaining={})", removed, count);
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

    static OffHeapPairTable readFrom(FileChannel ch, int capacity, int count, Arena arena)
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
        return new OffHeapPairTable(capacity, seg, count);
    }
}
