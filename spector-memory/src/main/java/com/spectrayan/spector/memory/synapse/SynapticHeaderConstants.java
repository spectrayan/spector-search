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

/**
 * Constants for the Synaptic Header — the 64-byte cache-line-aligned binary format.
 *
 * <h3>Layout (64 bytes — V1, full cache line)</h3>
 * <pre>
 *   Offset  Size  Field              Access Phase  Frequency
 *   ──────  ────  ─────────────────  ────────────  ──────────
 *    0      1B    header_version     One-time      Format detection (byte 0)
 *    1      1B    flags              Phase 1       EVERY record (tombstone check)
 *    2      1B    valence            Phase 3       ~98% (emotion filter)
 *    3      1B    arousal            Phase 4.5     ~90% (emotional intensity)
 *    4      4B    importance         Phase 4       ~95% (importance pre-screen)
 *    8      8B    timestamp_ms       Phase 1b      ~99% (temporal gating)
 *   16      4B    agent_recall_count Phase 4       ~95% (reconsolidation)
 *   20      4B    exact_norm         Heap insert   ~0.1% (cosine normalization)
 *   24      8B    synaptic_tags      Phase 2       ~98% (Bloom filter, AT END of core for 128-bit growth)
 *   ── 32B: end of core fields ─────────────────────────────────────────
 *   32      2B    centroid_id        Heap insert   ~0.1% (IVF routing)
 *   34      2B    _pad0              —             Alignment to 4B
 *   36      4B    storage_strength   Phase 6       ~1-10% (Two-Factor scoring)
 *   40      4B    spector_recall_cnt Auto-LTP      Passive retrieval counter
 *   44      4B    _reserved_f1       —             Future float field
 *   48      8B    last_auto_ltp      Auto-LTP      Auto-LTP timestamp
 *   56      8B    _reserved_l1       —             Future: 128-bit tag upper half
 *   ── 64B: full cache line ────────────────────────────────────────────
 * </pre>
 *
 * <h3>Design Principles</h3>
 * <ul>
 *   <li><b>Version at byte 0</b> — universal convention (ELF, PNG, Java .class).</li>
 *   <li><b>Flags at byte 1</b> — first decision field in scoring hot path.</li>
 *   <li><b>Natural alignment</b> — longs at 8B boundaries, ints/floats at 4B.</li>
 *   <li><b>Synaptic tags at end of core (offset 24)</b> — growable to 128-bit
 *       without reshuffling core fields.</li>
 * </ul>
 *
 * <h3>Flags Bitfield (byte 1)</h3>
 * <pre>
 *   bit 0:   tombstone  (deleted / pruned by Deep Sleep)
 *   bit 1-2: memory_type (2 bits → 4 types)
 *   bit 3:   consolidated (has been reflected into Semantic tier)
 *   bit 4:   pinned (exempt from decay/pruning)
 *   bit 5:   resolved (Zeigarnik Effect — unresolved tasks resist decay)
 *   bit 6-7: source_modality (2 bits → 4 modalities: TEXT, IMAGE, AUDIO, VIDEO)
 * </pre>
 *
 * @see HeaderLayout
 * @see CognitiveRecordLayout
 */
public final class SynapticHeaderConstants {

    private SynapticHeaderConstants() {}

    /** Header size in bytes (64B = full cache line). */
    public static final int HEADER_BYTES = 64;

    /** Current header format version. */
    public static final int HEADER_VERSION = 1;

    // ── Core field offsets (first 32 bytes) ──

    /** Offset of header_version byte (always byte 0). */
    public static final long OFFSET_HEADER_VERSION      = 0L;
    /** Offset of flags bitfield. */
    public static final long OFFSET_FLAGS               = 1L;
    /** Offset of valence byte (signed -128 to +127). */
    public static final long OFFSET_VALENCE             = 2L;
    /** Offset of arousal byte (unsigned 0-255). */
    public static final long OFFSET_AROUSAL             = 3L;
    /** Offset of importance float (4B-aligned). */
    public static final long OFFSET_IMPORTANCE          = 4L;
    /** Offset of timestamp_ms long (8B-aligned). */
    public static final long OFFSET_TIMESTAMP           = 8L;
    /** Offset of agent_recall_count int (4B-aligned). */
    public static final long OFFSET_AGENT_RECALL_COUNT  = 16L;
    /** Offset of exact_norm float. */
    public static final long OFFSET_EXACT_NORM          = 20L;
    /** Offset of synaptic_tags long (8B-aligned, at end of core for 128-bit growth). */
    public static final long OFFSET_SYNAPTIC_TAGS       = 24L;

    // ── Extended field offsets (bytes 32-63) ──

    /** Offset of centroid_id short (IVF routing). */
    public static final long OFFSET_CENTROID_ID         = 32L;
    // 34-35: alignment padding
    /** Offset of storage_strength float (Two-Factor scoring). */
    public static final long OFFSET_STORAGE_STRENGTH    = 36L;
    /** Offset of spector-internal recall count int (auto-LTP). */
    public static final long OFFSET_SPECTOR_RECALL_COUNT = 40L;
    /** Offset of reserved float field. */
    public static final long OFFSET_RESERVED_F1         = 44L;
    /** Offset of last auto-LTP timestamp long (8B-aligned). */
    public static final long OFFSET_LAST_AUTO_LTP       = 48L;
    /** Offset of reserved long field (future: 128-bit tag upper half). */
    public static final long OFFSET_RESERVED_L1         = 56L;

    // ── Value layouts ──

    public static final ValueLayout.OfByte  LAYOUT_HEADER_VERSION     = ValueLayout.JAVA_BYTE;
    public static final ValueLayout.OfByte  LAYOUT_FLAGS              = ValueLayout.JAVA_BYTE;
    public static final ValueLayout.OfByte  LAYOUT_VALENCE            = ValueLayout.JAVA_BYTE;
    public static final ValueLayout.OfByte  LAYOUT_AROUSAL            = ValueLayout.JAVA_BYTE;
    public static final ValueLayout.OfFloat LAYOUT_IMPORTANCE         = ValueLayout.JAVA_FLOAT;
    public static final ValueLayout.OfLong  LAYOUT_TIMESTAMP          = ValueLayout.JAVA_LONG;
    public static final ValueLayout.OfInt   LAYOUT_AGENT_RECALL_COUNT = ValueLayout.JAVA_INT;
    public static final ValueLayout.OfFloat LAYOUT_EXACT_NORM         = ValueLayout.JAVA_FLOAT;
    public static final ValueLayout.OfLong  LAYOUT_SYNAPTIC_TAGS      = ValueLayout.JAVA_LONG;
    public static final ValueLayout.OfShort LAYOUT_CENTROID_ID        = ValueLayout.JAVA_SHORT;
    public static final ValueLayout.OfFloat LAYOUT_STORAGE_STRENGTH   = ValueLayout.JAVA_FLOAT;
    public static final ValueLayout.OfInt   LAYOUT_SPECTOR_RECALL_COUNT = ValueLayout.JAVA_INT;
    public static final ValueLayout.OfLong  LAYOUT_LAST_AUTO_LTP      = ValueLayout.JAVA_LONG;

    // ── VarHandle views for atomic access ──

    /** VarHandle for atomic updates to the agent_recall_count field. */
    public static final java.lang.invoke.VarHandle VAR_HANDLE_AGENT_RECALL_COUNT = LAYOUT_AGENT_RECALL_COUNT.varHandle();
    /** VarHandle for atomic updates to the spector_recall_count field. */
    public static final java.lang.invoke.VarHandle VAR_HANDLE_SPECTOR_RECALL_COUNT = LAYOUT_SPECTOR_RECALL_COUNT.varHandle();
    /** VarHandle for atomic bitwise synaptic tag merging (getAndBitwiseOr). */
    public static final java.lang.invoke.VarHandle VAR_HANDLE_SYNAPTIC_TAGS = LAYOUT_SYNAPTIC_TAGS.varHandle();

    // ── Flags bitmasks ──

    /** Bit 0: Record has been logically deleted (tombstoned). */
    public static final byte FLAG_TOMBSTONE    = 0x01;
    /** Bits 1-2: Memory type (2 bits → 4 types). */
    public static final byte FLAG_TYPE_MASK    = 0x06;
    /** Number of bits to shift to read/write memory type from flags. */
    public static final int  FLAG_TYPE_SHIFT   = 1;
    /** Bit 3: Memory has been consolidated (reflected from Episodic → Semantic). */
    public static final byte FLAG_CONSOLIDATED = 0x08;
    /** Bit 4: Memory is pinned (exempt from decay and pruning). */
    public static final byte FLAG_PINNED       = 0x10;
    /** Bit 5: Memory is resolved (Zeigarnik Effect — unresolved memories resist time-decay). */
    public static final byte FLAG_RESOLVED     = 0x20;
    /** Bits 6-7: Source modality (2 bits → 4 modalities: TEXT=0, IMAGE=1, AUDIO=2, VIDEO=3). */
    public static final byte FLAG_MODALITY_MASK  = (byte) 0xC0;
    /** Number of bits to shift to read/write source modality from flags. */
    public static final int  FLAG_MODALITY_SHIFT = 6;

    // ── Convenience methods ──

    /**
     * Checks if the tombstone flag is set in the given flags byte.
     */
    public static boolean isTombstoned(byte flags) {
        return (flags & FLAG_TOMBSTONE) != 0;
    }

    /**
     * Checks if the pinned flag is set.
     */
    public static boolean isPinned(byte flags) {
        return (flags & FLAG_PINNED) != 0;
    }

    /**
     * Checks if the resolved flag is set (Zeigarnik Effect).
     *
     * <p>When {@code false} (default for new memories), the memory resists
     * time-decay — it floats to the top of recall like an unfinished task.
     * When the agent marks the task complete, this flips to {@code true}
     * and the memory succumbs to normal decay.</p>
     */
    public static boolean isResolved(byte flags) {
        return (flags & FLAG_RESOLVED) != 0;
    }

    /**
     * Checks if the consolidated flag is set.
     */
    public static boolean isConsolidated(byte flags) {
        return (flags & FLAG_CONSOLIDATED) != 0;
    }

    /**
     * Extracts the 2-bit memory type ordinal (0–3) from the flags byte.
     */
    public static int memoryTypeOrdinal(byte flags) {
        return (flags & FLAG_TYPE_MASK) >>> FLAG_TYPE_SHIFT;
    }

    /**
     * Encodes a memory type ordinal into a flags byte, preserving other bits.
     */
    public static byte withMemoryType(byte flags, int typeOrdinal) {
        return (byte) ((flags & ~FLAG_TYPE_MASK) | ((typeOrdinal << FLAG_TYPE_SHIFT) & FLAG_TYPE_MASK));
    }

    /**
     * Extracts the 2-bit source modality ordinal (0–3) from the flags byte.
     *
     * @see com.spectrayan.spector.memory.model.SourceModality
     */
    public static int sourceModalityOrdinal(byte flags) {
        return (flags & 0xFF & FLAG_MODALITY_MASK) >>> FLAG_MODALITY_SHIFT;
    }

    /**
     * Encodes a source modality ordinal into a flags byte, preserving other bits.
     *
     * @see com.spectrayan.spector.memory.model.SourceModality
     */
    public static byte withSourceModality(byte flags, int modalityOrdinal) {
        return (byte) ((flags & ~FLAG_MODALITY_MASK)
                | ((modalityOrdinal << FLAG_MODALITY_SHIFT) & (FLAG_MODALITY_MASK & 0xFF)));
    }
}
