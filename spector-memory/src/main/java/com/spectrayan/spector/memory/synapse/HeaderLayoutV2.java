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
 * Header layout V2 — 48-byte format with arousal and storage strength.
 *
 * <h3>Layout (48 bytes)</h3>
 * <pre>
 *   Offset  Size  Field              Default
 *   ──────  ────  ─────────────────  ────────
 *    0-31   32B   (same as V1)
 *   32      1B    arousal            0 (unsigned 0-255)
 *   33      1B    header_version     2
 *   34      2B    _pad1              0 (alignment)
 *   36      4B    storage_strength   1.0f
 *   40      4B    _reserved_f1       0.0f
 *   44      2B    _reserved_s1       0
 *   46      1B    _reserved_b1       0
 *   47      1B    _reserved_b2       0
 * </pre>
 *
 * <h3>Use Cases</h3>
 * <ul>
 *   <li>Production deployments needing arousal-modulated decay</li>
 *   <li>Two-Factor Memory (Bjork &amp; Bjork) research with moderate memory footprint</li>
 * </ul>
 */
public record HeaderLayoutV2() implements HeaderLayout {

    /** Singleton instance. */
    public static final HeaderLayoutV2 INSTANCE = new HeaderLayoutV2();

    /** V2 header size: 48 bytes. */
    public static final int HEADER_SIZE = 48;

    // ── V2 extended field offsets ──
    /** Offset of the arousal byte (unsigned, 0-255). */
    public static final long OFFSET_AROUSAL          = 32L;
    /** Offset of the header_version byte. */
    public static final long OFFSET_HEADER_VERSION   = 33L;
    /** Offset of the storage_strength float (4-byte aligned at 36). */
    public static final long OFFSET_STORAGE_STRENGTH = 36L;

    @Override public int headerBytes() { return HEADER_SIZE; }
    @Override public int version() { return 2; }

    // ── Core field reads (identical to V1) ──

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

    // ── Extended field reads (V2) ──

    @Override public byte readArousal(MemorySegment seg, long off) {
        return seg.get(ValueLayout.JAVA_BYTE, off + OFFSET_AROUSAL);
    }

    @Override public float readStorageStrength(MemorySegment seg, long off) {
        return seg.get(ValueLayout.JAVA_FLOAT, off + OFFSET_STORAGE_STRENGTH);
    }

    // ── Extended field writes (V2) ──

    @Override public void writeArousal(MemorySegment seg, long off, byte arousal) {
        seg.set(ValueLayout.JAVA_BYTE, off + OFFSET_AROUSAL, arousal);
    }

    @Override public void writeStorageStrength(MemorySegment seg, long off, float strength) {
        seg.set(ValueLayout.JAVA_FLOAT, off + OFFSET_STORAGE_STRENGTH, strength);
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
                readArousal(seg, off),
                readStorageStrength(seg, off)
        );
    }

    @Override
    public void writeHeader(MemorySegment seg, long off, CognitiveRecordLayout.CognitiveHeader header) {
        // Core fields
        seg.set(LAYOUT_TIMESTAMP,     off + OFFSET_TIMESTAMP,     header.timestampMs());
        seg.set(LAYOUT_SYNAPTIC_TAGS, off + OFFSET_SYNAPTIC_TAGS, header.synapticTags());
        seg.set(LAYOUT_EXACT_NORM,    off + OFFSET_EXACT_NORM,    header.exactNorm());
        seg.set(LAYOUT_IMPORTANCE,    off + OFFSET_IMPORTANCE,    header.importance());
        seg.set(LAYOUT_RECALL_COUNT,  off + OFFSET_RECALL_COUNT,  header.recallCount());
        seg.set(LAYOUT_CENTROID_ID,   off + OFFSET_CENTROID_ID,   header.centroidId());
        seg.set(LAYOUT_VALENCE,       off + OFFSET_VALENCE,       header.valence());
        seg.set(LAYOUT_FLAGS,         off + OFFSET_FLAGS,         header.flags());
        // Extended fields
        seg.set(ValueLayout.JAVA_BYTE,  off + OFFSET_AROUSAL,          header.arousal());
        seg.set(ValueLayout.JAVA_BYTE,  off + OFFSET_HEADER_VERSION,   (byte) 2);
        seg.set(ValueLayout.JAVA_FLOAT, off + OFFSET_STORAGE_STRENGTH, header.storageStrength());
    }

    // ── Mutation helpers (core fields — identical to V1) ──

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
