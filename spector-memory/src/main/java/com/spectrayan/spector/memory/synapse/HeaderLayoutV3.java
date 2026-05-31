package com.spectrayan.spector.memory.synapse;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static com.spectrayan.spector.memory.synapse.SynapticHeaderConstants.*;

/**
 * Header layout V3 — the full 64-byte cache-line-aligned format.
 *
 * <h3>Layout (64 bytes)</h3>
 * <pre>
 *   Offset  Size  Field              Default    Notes
 *   ──────  ────  ─────────────────  ────────   ──────
 *    0-31   32B   (same as V1)                  Core fields
 *   32      1B    arousal            0          Emotional intensity (unsigned)
 *   33      1B    header_version     3          Format version
 *   34      2B    _pad1              0          Alignment padding
 *   36      4B    storage_strength   1.0f       Two-Factor Memory S(t)
 *   40      4B    _reserved_f1       0.0f       Future float field
 *   44      4B    _reserved_f2       0.0f       Future float field
 *   48      8B    _reserved_l1       0L         Future long (e.g., causal_link_id)
 *   56      4B    _reserved_i1       0          Future int field
 *   60      2B    _reserved_s1       0          Future short field
 *   62      1B    _reserved_b1       0          Future byte field
 *   63      1B    _reserved_b2       0          Future byte field
 * </pre>
 *
 * <p>The 64-byte size is a full CPU cache line, providing optimal alignment for
 * sequential scans. The vector payload starts at offset 64, perfectly aligned
 * for all SIMD register widths (SSE-128, AVX-256, AVX-512).</p>
 *
 * <p>The 32 bytes of reserved space (offsets 32-63 minus used fields) provide
 * ample buffer for future field additions without another format break.</p>
 *
 * <h3>Use Cases</h3>
 * <ul>
 *   <li>Default for all new stores — maximum future-proofing</li>
 *   <li>Full arousal + Two-Factor Memory + future extensions</li>
 * </ul>
 *
 * @see HeaderLayout
 * @see HeaderLayoutV1
 * @see HeaderLayoutV2
 */
public record HeaderLayoutV3() implements HeaderLayout {

    /** Singleton instance. */
    public static final HeaderLayoutV3 INSTANCE = new HeaderLayoutV3();

    /** V3 header size: 64 bytes (full cache line). */
    public static final int HEADER_SIZE = 64;

    // ── V3 field offsets (shared with V2 where applicable) ──
    /** Offset of the arousal byte (unsigned, 0-255). */
    public static final long OFFSET_AROUSAL          = 32L;
    /** Offset of the header_version byte. */
    public static final long OFFSET_HEADER_VERSION   = 33L;
    /** Offset of the storage_strength float (4-byte aligned at 36). */
    public static final long OFFSET_STORAGE_STRENGTH = 36L;
    /** Offset of the first reserved float field. */
    public static final long OFFSET_RESERVED_F1      = 40L;
    /** Offset of the second reserved float field. */
    public static final long OFFSET_RESERVED_F2      = 44L;
    /** Offset of the reserved long field (e.g., causal_link_id). */
    public static final long OFFSET_RESERVED_L1      = 48L;
    /** Offset of the reserved int field. */
    public static final long OFFSET_RESERVED_I1      = 56L;
    /** Offset of the reserved short field. */
    public static final long OFFSET_RESERVED_S1      = 60L;
    /** Offset of the first reserved byte field. */
    public static final long OFFSET_RESERVED_B1      = 62L;
    /** Offset of the second reserved byte field. */
    public static final long OFFSET_RESERVED_B2      = 63L;

    @Override public int headerBytes() { return HEADER_SIZE; }
    @Override public int version() { return 3; }

    // ── Core field reads (identical to V1/V2) ──

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

    // ── Extended field reads (V3) ──

    @Override public byte readArousal(MemorySegment seg, long off) {
        return seg.get(ValueLayout.JAVA_BYTE, off + OFFSET_AROUSAL);
    }

    @Override public float readStorageStrength(MemorySegment seg, long off) {
        return seg.get(ValueLayout.JAVA_FLOAT, off + OFFSET_STORAGE_STRENGTH);
    }

    // ── Extended field writes (V3) ──

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
        seg.set(ValueLayout.JAVA_BYTE,  off + OFFSET_HEADER_VERSION,   (byte) 3);
        seg.set(ValueLayout.JAVA_FLOAT, off + OFFSET_STORAGE_STRENGTH, header.storageStrength());
        // Zero reserved fields (ensure clean state)
        seg.set(ValueLayout.JAVA_FLOAT, off + OFFSET_RESERVED_F1, 0.0f);
        seg.set(ValueLayout.JAVA_FLOAT, off + OFFSET_RESERVED_F2, 0.0f);
        seg.set(ValueLayout.JAVA_LONG,  off + OFFSET_RESERVED_L1, 0L);
        seg.set(ValueLayout.JAVA_INT,   off + OFFSET_RESERVED_I1, 0);
        seg.set(ValueLayout.JAVA_SHORT, off + OFFSET_RESERVED_S1, (short) 0);
        seg.set(ValueLayout.JAVA_BYTE,  off + OFFSET_RESERVED_B1, (byte) 0);
        seg.set(ValueLayout.JAVA_BYTE,  off + OFFSET_RESERVED_B2, (byte) 0);
    }

    // ── Mutation helpers (core fields — identical to V1/V2) ──

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
