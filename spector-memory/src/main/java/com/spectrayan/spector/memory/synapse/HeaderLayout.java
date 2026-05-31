package com.spectrayan.spector.memory.synapse;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static com.spectrayan.spector.memory.synapse.SynapticHeaderConstants.*;

/**
 * Polymorphic, versioned accessor for synaptic memory record headers.
 *
 * <h3>Design: Strategy Pattern with Sealed Interface</h3>
 * <p>The header format evolves across versions (V1=32B, V2=48B, V3=64B).
 * Instead of branching on version in every hot-path read, we construct the
 * correct {@code HeaderLayout} implementation <em>once</em> at store-open time
 * and inject it everywhere. The JIT devirtualizes the sealed hierarchy into
 * direct method calls — zero overhead vs. hardcoded constants.</p>
 *
 * <h3>On-Load Compatibility</h3>
 * <p>Extended fields (V2+) have default implementations that return safe defaults:
 * {@code arousal=0}, {@code storageStrength=1.0f}. This means a V1 layout can
 * serve all read requests without error — callers never need to check the version.</p>
 *
 * <h3>Versions</h3>
 * <ul>
 *   <li><b>V1 (32B)</b> — Legacy/lightweight. Core fields only.</li>
 *   <li><b>V2 (48B)</b> — Adds arousal + storage_strength for emotional modulation
 *       and Two-Factor Memory research.</li>
 *   <li><b>V3 (64B)</b> — Full cache-line aligned layout with 32 bytes of future
 *       buffer. Default for all new stores.</li>
 * </ul>
 *
 * @see HeaderLayoutV1
 * @see HeaderLayoutV2
 * @see HeaderLayoutV3
 * @see CognitiveRecordLayout
 */
public sealed interface HeaderLayout
        permits HeaderLayoutV1, HeaderLayoutV2, HeaderLayoutV3 {

    // ── Layout metadata ──

    /** Header size in bytes for this layout version. */
    int headerBytes();

    /** Layout version number (1, 2, or 3). */
    int version();

    // ── Core field reads (all versions) ──

    /** Reads the timestamp (epoch millis) at the given record offset. */
    long readTimestamp(MemorySegment seg, long off);

    /** Reads the 64-bit Bloom filter of contextual synaptic tags. */
    long readSynapticTags(MemorySegment seg, long off);

    /** Reads the L2 norm of the original float32 vector. */
    float readExactNorm(MemorySegment seg, long off);

    /** Reads the base importance score. */
    float readImportance(MemorySegment seg, long off);

    /** Reads the LTP reinforcement counter. */
    int readRecallCount(MemorySegment seg, long off);

    /** Reads the IVF centroid routing ID. */
    short readCentroidId(MemorySegment seg, long off);

    /** Reads the signed valence byte (-128 to +127). */
    byte readValence(MemorySegment seg, long off);

    /** Reads the flags bitfield. */
    byte readFlags(MemorySegment seg, long off);

    // ── Extended field reads (V2+ — defaults for V1) ──

    /**
     * Reads the arousal byte (emotional intensity, unsigned 0-255).
     *
     * <p>Use {@code Byte.toUnsignedInt()} for arithmetic. Higher arousal
     * modulates decay — emotionally intense memories resist forgetting.</p>
     *
     * @return arousal value, or {@code 0} if this layout version does not support it
     */
    default byte readArousal(MemorySegment seg, long off) { return 0; }

    /**
     * Reads the storage strength for Two-Factor Memory (Bjork &amp; Bjork).
     *
     * <p>Storage strength determines how resistant a memory is to decay.
     * It increases most when retrieval occurs right before forgetting
     * (the spacing effect).</p>
     *
     * @return storage strength, or {@code 1.0f} (standard decay) if unsupported
     */
    default float readStorageStrength(MemorySegment seg, long off) { return 1.0f; }

    // ── Extended field writes (V2+ — no-ops for V1) ──

    /** Writes the arousal byte. No-op on V1 layouts. */
    default void writeArousal(MemorySegment seg, long off, byte arousal) { /* no-op */ }

    /** Writes the storage strength. No-op on V1 layouts. */
    default void writeStorageStrength(MemorySegment seg, long off, float strength) { /* no-op */ }

    // ── Full header read/write ──

    /** Reads all header fields into an immutable {@link CognitiveRecordLayout.CognitiveHeader}. */
    CognitiveRecordLayout.CognitiveHeader readHeader(MemorySegment seg, long off);

    /** Writes all header fields from a {@link CognitiveRecordLayout.CognitiveHeader}. */
    void writeHeader(MemorySegment seg, long off, CognitiveRecordLayout.CognitiveHeader header);

    // ── Mutation helpers (shared logic, version-aware offsets) ──

    /** Updates the importance field. */
    void writeImportance(MemorySegment seg, long off, float importance);

    /** Updates the timestamp field. */
    void writeTimestamp(MemorySegment seg, long off, long timestampMs);

    /** Merges synaptic tags via bitwise OR. */
    void mergeSynapticTags(MemorySegment seg, long off, long additionalTags);

    /** Sets the tombstone flag (logical deletion). */
    void markTombstoned(MemorySegment seg, long off);

    /** Sets the consolidated flag (reflected from Episodic → Semantic). */
    void markConsolidated(MemorySegment seg, long off);

    /**
     * Sets the pinned flag (exempt from decay and pruning).
     * Used by neurodivergent lossless consolidation (SYSTEMATIZER profile).
     */
    void markPinned(MemorySegment seg, long off);

    /**
     * Sets the resolved flag (Zeigarnik Effect — task is done).
     * The memory succumbs to normal time-decay.
     */
    void markResolved(MemorySegment seg, long off);

    /**
     * Clears the resolved flag (Zeigarnik Effect — task re-opened).
     * The memory re-enters the Zeigarnik loop and resists decay.
     */
    void markUnresolved(MemorySegment seg, long off);

    /**
     * Atomically increments the recall count (LTP reinforcement).
     *
     * @return the previous recall count value
     */
    int incrementRecallCount(MemorySegment seg, long off);

    // ── Factory methods ──

    /**
     * Returns the layout for the given version number.
     *
     * @param version 1, 2, or 3
     * @return the corresponding layout instance (singleton)
     * @throws IllegalArgumentException if version is unknown
     */
    static HeaderLayout forVersion(int version) {
        return switch (version) {
            case 1 -> HeaderLayoutV1.INSTANCE;
            case 2 -> HeaderLayoutV2.INSTANCE;
            case 3 -> HeaderLayoutV3.INSTANCE;
            default -> throw new IllegalArgumentException(
                    "Unknown header layout version: " + version + ". Supported: 1, 2, 3");
        };
    }

    /** Default layout for all new stores (V3, 64 bytes). */
    static HeaderLayout defaultLayout() {
        return HeaderLayoutV3.INSTANCE;
    }

    /**
     * Detects the layout version from a store's metadata segment.
     *
     * <p>Reads the {@code header_version} byte from the store metadata.
     * If the byte is 0 or the metadata is too small, assumes V1 (legacy).</p>
     *
     * @param metadataVersion the version byte from the store metadata (0 = legacy)
     * @return the corresponding layout
     */
    static HeaderLayout detect(int metadataVersion) {
        if (metadataVersion <= 0 || metadataVersion > 3) {
            return HeaderLayoutV1.INSTANCE; // legacy or unknown → V1
        }
        return forVersion(metadataVersion);
    }
}
