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
package com.spectrayan.spector.memory.synapse;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static com.spectrayan.spector.memory.synapse.SynapticHeaderConstants.*;

/**
 * Header layout V1 — the original 32-byte format (legacy/lightweight).
 *
 * <h3>Layout (32 bytes)</h3>
 * <pre>
 *   Offset  Size  Field
 *   ──────  ────  ────────────────
 *    0      8B    timestamp_ms
 *    8      8B    synaptic_tags
 *   16      4B    exact_norm
 *   20      4B    importance
 *   24      4B    recall_count
 *   28      2B    centroid_id
 *   30      1B    valence
 *   31      1B    flags
 * </pre>
 *
 * <p>Extended fields (arousal, storage_strength) return safe defaults:
 * {@code arousal=0}, {@code storageStrength=1.0f}. Writes to extended
 * fields are silently ignored (no-op).</p>
 *
 * <h3>Use Cases</h3>
 * <ul>
 *   <li>Reading legacy store files created before V2/V3</li>
 *   <li>Lightweight/edge deployments that don't need extended fields</li>
 * </ul>
 */
public record HeaderLayoutV1() implements HeaderLayout {

    /** Singleton instance. */
    public static final HeaderLayoutV1 INSTANCE = new HeaderLayoutV1();

    /** V1 header size: 32 bytes. */
    public static final int HEADER_SIZE = 32;

    @Override public int headerBytes() { return HEADER_SIZE; }
    @Override public int version() { return 1; }

    // ── Core field reads ──

    @Override public long readTimestamp(MemorySegment seg, long off) {
        return seg.get(LAYOUT_TIMESTAMP, off + OFFSET_TIMESTAMP);
    }

    @Override public long readSynapticTags(MemorySegment seg, long off) {
        return seg.get(LAYOUT_SYNAPTIC_TAGS, off + OFFSET_SYNAPTIC_TAGS);
    }

    @Override public float readExactNorm(MemorySegment seg, long off) {
        return seg.get(LAYOUT_EXACT_NORM, off + OFFSET_EXACT_NORM);
    }

    @Override public float readImportance(MemorySegment seg, long off) {
        return seg.get(LAYOUT_IMPORTANCE, off + OFFSET_IMPORTANCE);
    }

    @Override public int readRecallCount(MemorySegment seg, long off) {
        return seg.get(LAYOUT_RECALL_COUNT, off + OFFSET_RECALL_COUNT);
    }

    @Override public short readCentroidId(MemorySegment seg, long off) {
        return seg.get(LAYOUT_CENTROID_ID, off + OFFSET_CENTROID_ID);
    }

    @Override public byte readValence(MemorySegment seg, long off) {
        return seg.get(LAYOUT_VALENCE, off + OFFSET_VALENCE);
    }

    @Override public byte readFlags(MemorySegment seg, long off) {
        return seg.get(LAYOUT_FLAGS, off + OFFSET_FLAGS);
    }

    // ── Full header read/write ──

    @Override
    public CognitiveRecordLayout.CognitiveHeader readHeader(MemorySegment seg, long off) {
        return new CognitiveRecordLayout.CognitiveHeader(
                readTimestamp(seg, off),
                readSynapticTags(seg, off),
                readExactNorm(seg, off),
                readImportance(seg, off),
                readRecallCount(seg, off),
                readCentroidId(seg, off),
                readValence(seg, off),
                readFlags(seg, off),
                (byte) 0,   // arousal default
                1.0f        // storageStrength default
        );
    }

    @Override
    public void writeHeader(MemorySegment seg, long off, CognitiveRecordLayout.CognitiveHeader header) {
        seg.set(LAYOUT_TIMESTAMP,     off + OFFSET_TIMESTAMP,     header.timestampMs());
        seg.set(LAYOUT_SYNAPTIC_TAGS, off + OFFSET_SYNAPTIC_TAGS, header.synapticTags());
        seg.set(LAYOUT_EXACT_NORM,    off + OFFSET_EXACT_NORM,    header.exactNorm());
        seg.set(LAYOUT_IMPORTANCE,    off + OFFSET_IMPORTANCE,    header.importance());
        seg.set(LAYOUT_RECALL_COUNT,  off + OFFSET_RECALL_COUNT,  header.recallCount());
        seg.set(LAYOUT_CENTROID_ID,   off + OFFSET_CENTROID_ID,   header.centroidId());
        seg.set(LAYOUT_VALENCE,       off + OFFSET_VALENCE,       header.valence());
        seg.set(LAYOUT_FLAGS,         off + OFFSET_FLAGS,         header.flags());
    }

    // ── Mutation helpers ──

    @Override public void writeImportance(MemorySegment seg, long off, float importance) {
        seg.set(LAYOUT_IMPORTANCE, off + OFFSET_IMPORTANCE, importance);
    }

    @Override public void writeTimestamp(MemorySegment seg, long off, long timestampMs) {
        seg.set(LAYOUT_TIMESTAMP, off + OFFSET_TIMESTAMP, timestampMs);
    }

    @Override public void mergeSynapticTags(MemorySegment seg, long off, long additionalTags) {
        long existing = readSynapticTags(seg, off);
        seg.set(LAYOUT_SYNAPTIC_TAGS, off + OFFSET_SYNAPTIC_TAGS, existing | additionalTags);
    }

    @Override public void markTombstoned(MemorySegment seg, long off) {
        byte flags = readFlags(seg, off);
        seg.set(LAYOUT_FLAGS, off + OFFSET_FLAGS, (byte) (flags | FLAG_TOMBSTONE));
    }

    @Override public void markConsolidated(MemorySegment seg, long off) {
        byte flags = readFlags(seg, off);
        seg.set(LAYOUT_FLAGS, off + OFFSET_FLAGS, (byte) (flags | FLAG_CONSOLIDATED));
    }

    @Override public void markPinned(MemorySegment seg, long off) {
        byte flags = readFlags(seg, off);
        seg.set(LAYOUT_FLAGS, off + OFFSET_FLAGS, (byte) (flags | FLAG_PINNED));
    }

    @Override public void markResolved(MemorySegment seg, long off) {
        byte flags = readFlags(seg, off);
        seg.set(LAYOUT_FLAGS, off + OFFSET_FLAGS, (byte) (flags | FLAG_RESOLVED));
    }

    @Override public void markUnresolved(MemorySegment seg, long off) {
        byte flags = readFlags(seg, off);
        seg.set(LAYOUT_FLAGS, off + OFFSET_FLAGS, (byte) (flags & ~FLAG_RESOLVED));
    }

    @Override public int incrementRecallCount(MemorySegment seg, long off) {
        return (int) VAR_HANDLE_RECALL_COUNT.getAndAdd(seg, off + OFFSET_RECALL_COUNT, 1);
    }
}
