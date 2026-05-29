package com.spectrayan.spector.memory.synapse;

import com.spectrayan.spector.core.quantization.ScalarQuantizer;
import com.spectrayan.spector.memory.MemoryType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static com.spectrayan.spector.memory.synapse.SynapticHeaderConstants.*;

/**
 * Read/write operations for the 32-byte cognitive record layout.
 *
 * <p>A cognitive record = 32-byte synaptic header + quantized vector payload.
 * This layout does <em>not</em> extend or modify the existing {@code VectorStoreLayout}
 * in {@code spector-storage}. It is a new, independent layout specific to
 * {@code spector-memory}.</p>
 *
 * <h3>Biological Analog: The Synaptic Tag</h3>
 * <p>In neuroscience, synapses are "tagged" during learning (Frey &amp; Morris, 1997)
 * to mark them for later consolidation. This 32-byte header is the digital
 * equivalent — a lightweight marker enabling microsecond-latency routing,
 * filtering, and scoring without touching the heavy vector payload.</p>
 *
 * @param quantizedVecBytes number of bytes for the quantized vector payload
 */
public record CognitiveRecordLayout(int quantizedVecBytes) {

    /**
     * Total bytes per record (header + payload).
     */
    public int stride() {
        return HEADER_BYTES + quantizedVecBytes;
    }

    /**
     * Offset where the quantized vector payload begins within a record.
     */
    public long vectorOffset(long recordOffset) {
        return recordOffset + HEADER_BYTES;
    }

    // ── Write operations ──

    /**
     * Writes a complete cognitive header to the given segment at the specified record offset.
     */
    public void writeHeader(MemorySegment segment, long offset, CognitiveHeader header) {
        segment.set(LAYOUT_TIMESTAMP,     offset + OFFSET_TIMESTAMP,     header.timestampMs());
        segment.set(LAYOUT_SYNAPTIC_TAGS, offset + OFFSET_SYNAPTIC_TAGS, header.synapticTags());
        segment.set(LAYOUT_EXACT_NORM,    offset + OFFSET_EXACT_NORM,    header.exactNorm());
        segment.set(LAYOUT_IMPORTANCE,    offset + OFFSET_IMPORTANCE,    header.importance());
        segment.set(LAYOUT_RECALL_COUNT,  offset + OFFSET_RECALL_COUNT,  header.recallCount());
        segment.set(LAYOUT_CENTROID_ID,   offset + OFFSET_CENTROID_ID,   header.centroidId());
        segment.set(LAYOUT_VALENCE,       offset + OFFSET_VALENCE,       header.valence());
        segment.set(LAYOUT_FLAGS,         offset + OFFSET_FLAGS,         header.flags());
    }

    /**
     * Reads a complete cognitive header from the given segment at the specified record offset.
     */
    public CognitiveHeader readHeader(MemorySegment segment, long offset) {
        return new CognitiveHeader(
                segment.get(LAYOUT_TIMESTAMP,     offset + OFFSET_TIMESTAMP),
                segment.get(LAYOUT_SYNAPTIC_TAGS, offset + OFFSET_SYNAPTIC_TAGS),
                segment.get(LAYOUT_EXACT_NORM,    offset + OFFSET_EXACT_NORM),
                segment.get(LAYOUT_IMPORTANCE,    offset + OFFSET_IMPORTANCE),
                segment.get(LAYOUT_RECALL_COUNT,  offset + OFFSET_RECALL_COUNT),
                segment.get(LAYOUT_CENTROID_ID,   offset + OFFSET_CENTROID_ID),
                segment.get(LAYOUT_VALENCE,       offset + OFFSET_VALENCE),
                segment.get(LAYOUT_FLAGS,         offset + OFFSET_FLAGS)
        );
    }

    // ── Field-level accessors for hot-loop usage ──

    /**
     * Reads the flags byte at the given record offset.
     */
    public byte readFlags(MemorySegment segment, long offset) {
        return segment.get(LAYOUT_FLAGS, offset + OFFSET_FLAGS);
    }

    /**
     * Reads the synaptic tags (Bloom filter) at the given record offset.
     */
    public long readSynapticTags(MemorySegment segment, long offset) {
        return segment.get(LAYOUT_SYNAPTIC_TAGS, offset + OFFSET_SYNAPTIC_TAGS);
    }

    /**
     * Reads the valence byte at the given record offset.
     */
    public byte readValence(MemorySegment segment, long offset) {
        return segment.get(LAYOUT_VALENCE, offset + OFFSET_VALENCE);
    }

    /**
     * Reads the timestamp at the given record offset.
     */
    public long readTimestamp(MemorySegment segment, long offset) {
        return segment.get(LAYOUT_TIMESTAMP, offset + OFFSET_TIMESTAMP);
    }

    /**
     * Reads the importance at the given record offset.
     */
    public float readImportance(MemorySegment segment, long offset) {
        return segment.get(LAYOUT_IMPORTANCE, offset + OFFSET_IMPORTANCE);
    }

    /**
     * Reads the recall count at the given record offset.
     */
    public int readRecallCount(MemorySegment segment, long offset) {
        return segment.get(LAYOUT_RECALL_COUNT, offset + OFFSET_RECALL_COUNT);
    }

    /**
     * Increments the recall count (reconsolidation / LTP reinforcement).
     *
     * <h3>Semantic Note</h3>
     * <p>As of the recall_count inflation fix, this is only called from
     * {@code SpectorMemory.reinforce()}, meaning recall_count represents
     * "times the agent explicitly found this useful" — not "times it appeared
     * in search results." This produces more meaningful LTP adjustment.</p>
     *
     * <h3>Thread Safety</h3>
     * <p>Uses a plain read-modify-write on the off-heap segment. {@code recall_count}
     * is now a 4-byte {@code int} at a 4-byte-aligned offset (24), which enables
     * atomic CAS/getAndAdd via {@code MemorySegment} on JDK 24+. Until then,
     * the race condition is harmless: a lost increment shifts a decay bucket by
     * only 0.33 positions (via {@code DecayStrategy.adjustForReconsolidation}).</p>
     *
     * @return the previous recall count value
     */
    public int incrementRecallCount(MemorySegment segment, long offset) {
        int current = segment.get(LAYOUT_RECALL_COUNT, offset + OFFSET_RECALL_COUNT);
        segment.set(LAYOUT_RECALL_COUNT, offset + OFFSET_RECALL_COUNT, current + 1);
        return current;
    }

    /**
     * Sets the tombstone flag (logical deletion / pruning by Deep Sleep).
     */
    public void tombstone(MemorySegment segment, long offset) {
        byte flags = readFlags(segment, offset);
        segment.set(LAYOUT_FLAGS, offset + OFFSET_FLAGS, (byte) (flags | FLAG_TOMBSTONE));
    }

    /**
     * Sets the consolidated flag (memory has been reflected into Semantic tier).
     */
    public void markConsolidated(MemorySegment segment, long offset) {
        byte flags = readFlags(segment, offset);
        segment.set(LAYOUT_FLAGS, offset + OFFSET_FLAGS, (byte) (flags | FLAG_CONSOLIDATED));
    }

    /**
     * Sets the pinned flag (memory is exempt from decay and pruning).
     *
     * <p>Used by neurodivergent lossless consolidation (SYSTEMATIZER profile)
     * to pin source episodes during REM sleep, preserving encyclopedic detail
     * alongside the synthesized semantic fact.</p>
     */
    public void pin(MemorySegment segment, long offset) {
        byte flags = readFlags(segment, offset);
        segment.set(LAYOUT_FLAGS, offset + OFFSET_FLAGS, (byte) (flags | FLAG_PINNED));
    }

    /**
     * Updates the importance field.
     */
    public void writeImportance(MemorySegment segment, long offset, float importance) {
        segment.set(LAYOUT_IMPORTANCE, offset + OFFSET_IMPORTANCE, importance);
    }

    /**
     * Updates the timestamp field.
     */
    public void writeTimestamp(MemorySegment segment, long offset, long timestampMs) {
        segment.set(LAYOUT_TIMESTAMP, offset + OFFSET_TIMESTAMP, timestampMs);
    }

    /**
     * Merges synaptic tags by ORing the existing tags with new ones.
     */
    public void mergeSynapticTags(MemorySegment segment, long offset, long additionalTags) {
        long existing = readSynapticTags(segment, offset);
        segment.set(LAYOUT_SYNAPTIC_TAGS, offset + OFFSET_SYNAPTIC_TAGS, existing | additionalTags);
    }

    /**
     * Writes a pre-quantized vector payload to the segment at the record's vector offset.
     *
     * @param segment      off-heap memory segment
     * @param recordOffset byte offset of the record start
     * @param quantizedVec pre-quantized byte array (e.g., from ScalarQuantizer.encode())
     */
    public void writeQuantizedVector(MemorySegment segment, long recordOffset, byte[] quantizedVec) {
        MemorySegment.copy(MemorySegment.ofArray(quantizedVec), ValueLayout.JAVA_BYTE, 0,
                segment, ValueLayout.JAVA_BYTE, vectorOffset(recordOffset), quantizedVec.length);
    }

    /**
     * Quantizes a float32 vector using a calibrated {@link ScalarQuantizer} and writes
     * the result directly to the segment at the record's vector offset.
     *
     * <p>This is the production encoding path. The quantizer uses per-dimension min/max
     * bounds to linearly map each float to [0, 255], achieving accurate distance
     * computation when paired with the same quantizer's mins/scales during recall.</p>
     *
     * @param segment      off-heap memory segment
     * @param recordOffset byte offset of the record start
     * @param vector       float32 vector to quantize
     * @param quantizer    calibrated ScalarQuantizer
     */
    public void writeQuantizedVector(MemorySegment segment, long recordOffset,
                                      float[] vector, ScalarQuantizer quantizer) {
        byte[] quantized = quantizer.encode(vector);
        writeQuantizedVector(segment, recordOffset, quantized);
    }

    /**
     * Immutable record holding all 32-byte header fields.
     *
     * <p>Header v3: recall_count widened to int (4B, atomic-ready at offset 24),
     * centroid_id narrowed to short (2B at offset 28, max 65,535 centroids).</p>
     */
    public record CognitiveHeader(
            long timestampMs,
            long synapticTags,
            float exactNorm,
            float importance,
            int recallCount,
            short centroidId,
            byte valence,
            byte flags
    ) {
        /**
         * Creates a new header for initial ingestion with default recall count and valence.
         */
        public static CognitiveHeader create(long timestampMs, long synapticTags, float exactNorm,
                                              float importance, short centroidId, MemoryType memoryType) {
            byte flags = SynapticHeaderConstants.withMemoryType((byte) 0, memoryType.ordinal());
            return new CognitiveHeader(timestampMs, synapticTags, exactNorm, importance,
                    0, centroidId, (byte) 0, flags);
        }
    }
}
