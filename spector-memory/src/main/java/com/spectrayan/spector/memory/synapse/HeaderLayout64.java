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
 * The 64-byte cache-line-aligned header layout.
 *
 * <p>This is the sole header layout shipped in Spector. The 64-byte size
 * matches exactly one CPU cache line, eliminating split-line reads during
 * sequential scans. The on-disk version byte is {@code 1}.</p>
 *
 * <h3>Layout (64 bytes)</h3>
 * <pre>
 *   Offset  Size  Field              Notes
 *   ──────  ────  ─────────────────  ──────
 *    0      1B    header_version     Always 1
 *    1      1B    flags              Tombstone, type, consolidated, pinned, resolved
 *    2      1B    valence            Signed emotion (-128 to +127)
 *    3      1B    arousal            Unsigned intensity (0-255)
 *    4      4B    importance         Base importance score
 *    8      8B    timestamp_ms       When the memory was formed
 *   16      4B    agent_recall_count LTP reinforcement counter
 *   20      4B    exact_norm         L2 norm for cosine normalization
 *   24      8B    synaptic_tags      64-bit Bloom filter (at end of core for growth)
 *   32      2B    centroid_id        IVF partition routing ID
 *   34      2B    _pad0              Alignment padding
 *   36      4B    storage_strength   Two-Factor Memory S(t)
 *   40      4B    spector_recall_cnt Auto-LTP passive counter
 *   44      4B    _reserved_f1       Future float
 *   48      8B    last_auto_ltp      Auto-LTP timestamp
 *   56      8B    _reserved_l1       Future (128-bit tag upper half)
 * </pre>
 *
 * @see HeaderLayout
 * @see SynapticHeaderConstants
 */
public record HeaderLayout64() implements HeaderLayout {

    /** Singleton instance. */
    public static final HeaderLayout64 INSTANCE = new HeaderLayout64();

    @Override public int headerBytes() { return HEADER_BYTES; }
    @Override public int version() { return HEADER_VERSION; }

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

    @Override public int readAgentRecallCount(MemorySegment seg, long off) {
        return seg.get(LAYOUT_AGENT_RECALL_COUNT, off + OFFSET_AGENT_RECALL_COUNT);
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

    // ── Extended field reads ──

    @Override public byte readArousal(MemorySegment seg, long off) {
        return seg.get(LAYOUT_AROUSAL, off + OFFSET_AROUSAL);
    }

    @Override public float readStorageStrength(MemorySegment seg, long off) {
        return seg.get(LAYOUT_STORAGE_STRENGTH, off + OFFSET_STORAGE_STRENGTH);
    }

    // ── Extended field writes ──

    @Override public void writeArousal(MemorySegment seg, long off, byte arousal) {
        seg.set(LAYOUT_AROUSAL, off + OFFSET_AROUSAL, arousal);
    }

    @Override public void writeStorageStrength(MemorySegment seg, long off, float strength) {
        seg.set(LAYOUT_STORAGE_STRENGTH, off + OFFSET_STORAGE_STRENGTH, strength);
    }

    // ── Full header read/write ──

    @Override
    public CognitiveRecordLayout.CognitiveHeader readHeader(MemorySegment seg, long off) {
        return new CognitiveRecordLayout.CognitiveHeader(
                readTimestamp(seg, off),
                readSynapticTags(seg, off),
                readExactNorm(seg, off),
                readImportance(seg, off),
                readAgentRecallCount(seg, off),
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
        seg.set(LAYOUT_HEADER_VERSION, off + OFFSET_HEADER_VERSION, (byte) HEADER_VERSION);
        seg.set(LAYOUT_FLAGS,         off + OFFSET_FLAGS,          header.flags());
        seg.set(LAYOUT_VALENCE,       off + OFFSET_VALENCE,        header.valence());
        seg.set(LAYOUT_AROUSAL,       off + OFFSET_AROUSAL,        header.arousal());
        seg.set(LAYOUT_IMPORTANCE,    off + OFFSET_IMPORTANCE,     header.importance());
        seg.set(LAYOUT_TIMESTAMP,     off + OFFSET_TIMESTAMP,      header.timestampMs());
        seg.set(LAYOUT_AGENT_RECALL_COUNT, off + OFFSET_AGENT_RECALL_COUNT, header.agentRecallCount());
        seg.set(LAYOUT_EXACT_NORM,    off + OFFSET_EXACT_NORM,     header.exactNorm());
        seg.set(LAYOUT_SYNAPTIC_TAGS, off + OFFSET_SYNAPTIC_TAGS,  header.synapticTags());
        // Extended fields
        seg.set(LAYOUT_CENTROID_ID,   off + OFFSET_CENTROID_ID,    header.centroidId());
        seg.set(ValueLayout.JAVA_SHORT, off + 34L, (short) 0);    // _pad0
        seg.set(LAYOUT_STORAGE_STRENGTH, off + OFFSET_STORAGE_STRENGTH, header.storageStrength());
        // Zero auto-LTP and reserved fields (ensure clean state)
        seg.set(LAYOUT_SPECTOR_RECALL_COUNT, off + OFFSET_SPECTOR_RECALL_COUNT, 0);
        seg.set(ValueLayout.JAVA_FLOAT, off + OFFSET_RESERVED_F1, 0.0f);
        seg.set(LAYOUT_LAST_AUTO_LTP, off + OFFSET_LAST_AUTO_LTP, 0L);
        seg.set(ValueLayout.JAVA_LONG, off + OFFSET_RESERVED_L1,  0L);
    }

    // ── Mutation helpers ──

    @Override public void writeImportance(MemorySegment seg, long off, float importance) {
        seg.set(LAYOUT_IMPORTANCE, off + OFFSET_IMPORTANCE, importance);
    }

    @Override public void writeTimestamp(MemorySegment seg, long off, long timestampMs) {
        seg.set(LAYOUT_TIMESTAMP, off + OFFSET_TIMESTAMP, timestampMs);
    }

    @Override public void mergeSynapticTags(MemorySegment seg, long off, long additionalTags) {
        VAR_HANDLE_SYNAPTIC_TAGS.getAndBitwiseOr(seg, off + OFFSET_SYNAPTIC_TAGS, additionalTags);
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

    @Override public int incrementAgentRecallCount(MemorySegment seg, long off) {
        return (int) VAR_HANDLE_AGENT_RECALL_COUNT.getAndAdd(seg, off + OFFSET_AGENT_RECALL_COUNT, 1);
    }

    // ── Auto-LTP field implementations ──

    @Override public int readSpectorRecallCount(MemorySegment seg, long off) {
        return seg.get(LAYOUT_SPECTOR_RECALL_COUNT, off + OFFSET_SPECTOR_RECALL_COUNT);
    }

    @Override public int incrementSpectorRecallCount(MemorySegment seg, long off) {
        return (int) VAR_HANDLE_SPECTOR_RECALL_COUNT.getAndAdd(seg, off + OFFSET_SPECTOR_RECALL_COUNT, 1);
    }

    @Override public long readLastAutoLtp(MemorySegment seg, long off) {
        return seg.get(LAYOUT_LAST_AUTO_LTP, off + OFFSET_LAST_AUTO_LTP);
    }

    @Override public void writeLastAutoLtp(MemorySegment seg, long off, long timestampMs) {
        seg.set(LAYOUT_LAST_AUTO_LTP, off + OFFSET_LAST_AUTO_LTP, timestampMs);
    }
}
