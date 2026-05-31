package com.spectrayan.spector.memory.synapse;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Constants for the Synaptic Header — shared across all layout versions.
 *
 * <h3>Versioned Layout System</h3>
 * <p>The header format is versioned via the {@link HeaderLayout} sealed interface.
 * Three versions are supported:</p>
 * <ul>
 *   <li><b>V1 (32B)</b> — Legacy/lightweight. Core fields only.
 *       See {@link HeaderLayoutV1}.</li>
 *   <li><b>V2 (48B)</b> — Adds arousal + storage_strength for emotional
 *       modulation and Two-Factor Memory. See {@link HeaderLayoutV2}.</li>
 *   <li><b>V3 (64B)</b> — Full cache-line-aligned format with 32B of future
 *       buffer. Default for all new stores. See {@link HeaderLayoutV3}.</li>
 * </ul>
 *
 * <h3>Core Layout (first 32 bytes — shared by all versions)</h3>
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
 *   bit 1-2: memory_type (2 bits → 4 types)
 *   bit 3:   consolidated (has been reflected into Semantic tier)
 *   bit 4:   pinned (exempt from decay/pruning)
 *   bit 5:   resolved (Zeigarnik Effect — unresolved tasks resist decay)
 *   bits 6-7: reserved
 * </pre>
 *
 * <p>This class holds only the <em>core</em> constants shared across all versions.
 * Version-specific offsets and layouts are defined in the respective
 * {@link HeaderLayout} implementations.</p>
 *
 * @see HeaderLayout
 * @see CognitiveRecordLayout
 */
public final class SynapticHeaderConstants {

    private SynapticHeaderConstants() {}

    /**
     * V1 (core) header size in bytes.
     *
     * <p>This constant is retained for SIMD alignment purposes (Arena allocation
     * alignment parameter) and for backward compatibility. The actual header size
     * used at runtime is determined by the {@link HeaderLayout#headerBytes()} method
     * on the active layout version.</p>
     *
     * @see HeaderLayout#headerBytes()
     */
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

    // ── V2+ Extended field offsets (beyond 32-byte core) ──
    /** Arousal byte offset (V2/V3 only — returns 0 on V1 reads). */
    public static final long OFFSET_AROUSAL = 32L;
    /** Layout for arousal: unsigned byte (0-255), stored as signed Java byte. */
    public static final ValueLayout.OfByte  LAYOUT_AROUSAL       = ValueLayout.JAVA_BYTE;

    // ── VarHandle view for atomic access ──
    /** VarHandle for atomic updates to the recall_count field. */
    public static final java.lang.invoke.VarHandle VAR_HANDLE_RECALL_COUNT = LAYOUT_RECALL_COUNT.varHandle();

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
}
