package com.spectrayan.spector.memory.synapse;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Constants and {@link VarHandle} accessors for the 32-byte Synaptic Header v2.
 *
 * <h3>Layout (32 bytes, cache-line aligned)</h3>
 * <pre>
 *   [8B timestamp_ms]      Offset 0  — when the memory was formed
 *   [8B synaptic_tags]     Offset 8  — 64-bit Bloom filter of contextual markers
 *   [4B exact_norm]        Offset 16 — L2 norm for SIMD distance computation
 *   [4B importance]        Offset 20 — base importance (auto-set by Prediction Error engine)
 *   [4B recall_count]      Offset 24 — LTP reinforcement counter (4-byte aligned for atomic CAS)
 *   [2B centroid_id]       Offset 28 — IVF partition routing ID (max 65,535 centroids)
 *   [1B valence]           Offset 30 — signed INT8 emotion/reward (-128 to +127)
 *   [1B flags]             Offset 31 — bit field (tombstone, memory_type, consolidated, pinned)
 * </pre>
 *
 * <h3>Flags Bitfield</h3>
 * <pre>
 *   bit 0:   tombstone  (deleted / pruned by Deep Sleep)
 *   bit 1-2: memory_type (Working=0, Episodic=1, Semantic=2, Procedural=3)
 *   bit 3:   consolidated (has been reflected into Semantic tier)
 *   bit 4:   pinned (exempt from decay/pruning)
 *   bits 5-7: reserved
 * </pre>
 *
 * <p>The vector payload starts at offset 32, perfectly aligned for AVX-256 (32-byte)
 * and AVX-512 (64-byte) register loads.</p>
 *
 * @see CognitiveRecordLayout
 */
public final class SynapticHeaderConstants {

    private SynapticHeaderConstants() {}

    // ── Header size ──
    /** Total header size in bytes (must be 32 for SIMD alignment). */
    public static final int HEADER_BYTES = 32;

    // ── Field offsets ──
    public static final long OFFSET_TIMESTAMP     = 0L;
    public static final long OFFSET_SYNAPTIC_TAGS = 8L;
    public static final long OFFSET_EXACT_NORM    = 16L;
    public static final long OFFSET_IMPORTANCE    = 20L;
    public static final long OFFSET_RECALL_COUNT  = 24L;
    public static final long OFFSET_CENTROID_ID   = 28L;
    public static final long OFFSET_VALENCE       = 30L;
    public static final long OFFSET_FLAGS         = 31L;

    // ── Value layouts ──
    public static final ValueLayout.OfLong  LAYOUT_TIMESTAMP     = ValueLayout.JAVA_LONG;
    public static final ValueLayout.OfLong  LAYOUT_SYNAPTIC_TAGS = ValueLayout.JAVA_LONG;
    public static final ValueLayout.OfFloat LAYOUT_EXACT_NORM    = ValueLayout.JAVA_FLOAT;
    public static final ValueLayout.OfFloat LAYOUT_IMPORTANCE    = ValueLayout.JAVA_FLOAT;
    public static final ValueLayout.OfInt   LAYOUT_RECALL_COUNT  = ValueLayout.JAVA_INT;
    public static final ValueLayout.OfShort LAYOUT_CENTROID_ID   = ValueLayout.JAVA_SHORT;
    public static final ValueLayout.OfByte  LAYOUT_VALENCE       = ValueLayout.JAVA_BYTE;
    public static final ValueLayout.OfByte  LAYOUT_FLAGS         = ValueLayout.JAVA_BYTE;

    // ── Atomic recall_count operations ──
    // recall_count is now a 4-byte int at offset 24, naturally 4-byte aligned.
    // This enables atomic CAS/getAndAdd via MemorySegment on JDK 24+.
    // The widening from short (32K max) to int (2.1B max) also prevents
    // overflow for long-lived memories under heavy reinforcement.
    //
    // JDK 22+ removed MethodHandles.memorySegmentViewVarHandle().
    // Direct segment accessors (get/set) are used instead.
    // TODO: When targeting JDK 24+, use MemorySegment.getAndAdd() for atomicity.

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
}
