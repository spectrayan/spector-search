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

import com.spectrayan.spector.core.quantization.ScalarQuantizer;
import com.spectrayan.spector.memory.model.MemoryType;
import com.spectrayan.spector.memory.model.SourceModality;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Read/write operations for cognitive memory records.
 *
 * <p>A cognitive record = 64-byte synaptic header + quantized vector payload.
 * This layout does <em>not</em> extend or modify the existing {@code VectorStoreLayout}
 * in {@code spector-storage}. It is a new, independent layout specific to
 * {@code spector-memory}.</p>
 *
 * <h3>Biological Analog: The Synaptic Tag</h3>
 * <p>In neuroscience, synapses are "tagged" during learning (Frey &amp; Morris, 1997)
 * to mark them for later consolidation. The synaptic header is the digital
 * equivalent — a lightweight marker enabling microsecond-latency routing,
 * filtering, and scoring without touching the heavy vector payload.</p>
 *
 * <h3>Header Layout</h3>
 * <p>The {@link HeaderLayout} sealed interface provides version-aware access
 * to header fields. The current layout is 64 bytes (full cache line), with
 * header_version at byte 0 and synaptic_tags at the end of the core (offset 24)
 * for future 128-bit growth.</p>
 *
 * @param quantizedVecBytes number of bytes for the quantized vector payload
 * @param headerLayout      the versioned header layout to use for read/write
 *
 * @see HeaderLayout
 * @see HeaderLayout64
 */
public record CognitiveRecordLayout(int quantizedVecBytes, HeaderLayout headerLayout) {

    /**
     * Default constructor — uses the default 64-byte header layout.
     *
     * @param quantizedVecBytes bytes per quantized vector payload
     */
    public CognitiveRecordLayout(int quantizedVecBytes) {
        this(quantizedVecBytes, HeaderLayout.defaultLayout());
    }

    /**
     * Total bytes per record (header + payload).
     */
    public int stride() {
        return headerLayout.headerBytes() + quantizedVecBytes;
    }

    /**
     * Offset where the quantized vector payload begins within a record.
     */
    public long vectorOffset(long recordOffset) {
        return recordOffset + headerLayout.headerBytes();
    }

    // ── Write operations (delegate to HeaderLayout) ──

    /**
     * Writes a complete cognitive header to the given segment at the specified record offset.
     */
    public void writeHeader(MemorySegment segment, long offset, CognitiveHeader header) {
        headerLayout.writeHeader(segment, offset, header);
    }

    /**
     * Reads a complete cognitive header from the given segment at the specified record offset.
     */
    public CognitiveHeader readHeader(MemorySegment segment, long offset) {
        return headerLayout.readHeader(segment, offset);
    }

    // ── Field-level accessors (delegate to HeaderLayout) ──

    /** Reads the flags byte at the given record offset. */
    public byte readFlags(MemorySegment segment, long offset) {
        return headerLayout.readFlags(segment, offset);
    }

    /** Reads the synaptic tags (Bloom filter) at the given record offset. */
    public long readSynapticTags(MemorySegment segment, long offset) {
        return headerLayout.readSynapticTags(segment, offset);
    }

    /**
     * Reads the source modality (TEXT, IMAGE, AUDIO, VIDEO) from the flags byte.
     *
     * <p>Extracts bits 6-7 from the flags byte and maps to a {@link SourceModality}.
     * Existing records with zeroed bits return {@code SourceModality.TEXT}.</p>
     */
    public SourceModality readSourceModality(MemorySegment segment, long offset) {
        byte flags = headerLayout.readFlags(segment, offset);
        return SourceModality.fromOrdinal(SynapticHeaderConstants.sourceModalityOrdinal(flags));
    }

    /** Reads the valence byte at the given record offset. */
    public byte readValence(MemorySegment segment, long offset) {
        return headerLayout.readValence(segment, offset);
    }

    /** Reads the timestamp at the given record offset. */
    public long readTimestamp(MemorySegment segment, long offset) {
        return headerLayout.readTimestamp(segment, offset);
    }

    /** Reads the importance at the given record offset. */
    public float readImportance(MemorySegment segment, long offset) {
        return headerLayout.readImportance(segment, offset);
    }

    /** Reads the recall count at the given record offset. */
    public int readAgentRecallCount(MemorySegment segment, long offset) {
        return headerLayout.readAgentRecallCount(segment, offset);
    }

    /** Reads the arousal byte (unsigned 0-255). Returns 0 on V1 layouts. */
    public byte readArousal(MemorySegment segment, long offset) {
        return headerLayout.readArousal(segment, offset);
    }

    /** Reads the storage strength. Returns 1.0f on V1 layouts. */
    public float readStorageStrength(MemorySegment segment, long offset) {
        return headerLayout.readStorageStrength(segment, offset);
    }

    /**
     * Increments the recall count (reconsolidation / LTP reinforcement).
     *
     * <h3>Semantic Note</h3>
     * <p>As of the agent_recall_count inflation fix, this is only called from
     * {@code SpectorMemory.reinforce()}, meaning agent_recall_count represents
     * "times the agent explicitly found this useful" — not "times it appeared
     * in search results." This produces more meaningful LTP adjustment.</p>
     *
     * <h3>Thread Safety</h3>
     * <p>Uses a thread-safe atomic getAndAdd operation via {@link java.lang.invoke.VarHandle}.
     * This guarantees atomicity and zero race conditions under heavy concurrent
     * reinforcement workloads on modern multicore CPUs.</p>
     *
     * @return the previous recall count value
     */
    public int incrementAgentRecallCount(MemorySegment segment, long offset) {
        return headerLayout.incrementAgentRecallCount(segment, offset);
    }

    /** Reads the spector-internal recall count. Returns 0 on V1/V2 layouts. */
    public int readSpectorRecallCount(MemorySegment segment, long offset) {
        return headerLayout.readSpectorRecallCount(segment, offset);
    }

    /**
     * Atomically increments the spector-internal recall count (auto-LTP).
     *
     * <p>Unlike {@code incrementAgentRecallCount()}, this is called automatically
     * by the recall pipeline when a memory surfaces in results, subject to
     * a cooldown to prevent inflation from repeated queries.</p>
     *
     * @return the previous spector recall count value
     */
    public int incrementSpectorRecallCount(MemorySegment segment, long offset) {
        return headerLayout.incrementSpectorRecallCount(segment, offset);
    }

    /** Reads the last auto-LTP timestamp. Returns 0L on V1/V2 layouts. */
    public long readLastAutoLtp(MemorySegment segment, long offset) {
        return headerLayout.readLastAutoLtp(segment, offset);
    }

    /** Writes the last auto-LTP timestamp. No-op on V1/V2 layouts. */
    public void writeLastAutoLtp(MemorySegment segment, long offset, long timestampMs) {
        headerLayout.writeLastAutoLtp(segment, offset, timestampMs);
    }

    /** Sets the tombstone flag (logical deletion / pruning by Deep Sleep). */
    public void tombstone(MemorySegment segment, long offset) {
        headerLayout.markTombstoned(segment, offset);
    }

    /** Sets the consolidated flag (memory has been reflected into Semantic tier). */
    public void markConsolidated(MemorySegment segment, long offset) {
        headerLayout.markConsolidated(segment, offset);
    }

    /**
     * Sets the pinned flag (memory is exempt from decay and pruning).
     *
     * <p>Used by neurodivergent lossless consolidation (SYSTEMATIZER profile)
     * to pin source episodes during REM sleep, preserving encyclopedic detail
     * alongside the synthesized semantic fact.</p>
     */
    public void pin(MemorySegment segment, long offset) {
        headerLayout.markPinned(segment, offset);
    }

    /**
     * Sets the resolved flag (Zeigarnik Effect — marks a task/issue as done).
     *
     * <p>Once resolved, the memory succumbs to normal time-decay and gradually
     * fades from active recall. Call {@link #markUnresolved} if the issue resurfaces.</p>
     */
    public void markResolved(MemorySegment segment, long offset) {
        headerLayout.markResolved(segment, offset);
    }

    /**
     * Clears the resolved flag (Zeigarnik Effect — re-opens a task/issue).
     *
     * <p>The memory re-enters the Zeigarnik loop: it resists decay and floats
     * to the top of recall until explicitly resolved again.</p>
     */
    public void markUnresolved(MemorySegment segment, long offset) {
        headerLayout.markUnresolved(segment, offset);
    }

    /** Updates the importance field. */
    public void writeImportance(MemorySegment segment, long offset, float importance) {
        headerLayout.writeImportance(segment, offset, importance);
    }

    /** Updates the timestamp field. */
    public void writeTimestamp(MemorySegment segment, long offset, long timestampMs) {
        headerLayout.writeTimestamp(segment, offset, timestampMs);
    }

    /** Merges synaptic tags by ORing the existing tags with new ones. */
    public void mergeSynapticTags(MemorySegment segment, long offset, long additionalTags) {
        headerLayout.mergeSynapticTags(segment, offset, additionalTags);
    }

    /** Writes the arousal byte. No-op on V1 layouts. */
    public void writeArousal(MemorySegment segment, long offset, byte arousal) {
        headerLayout.writeArousal(segment, offset, arousal);
    }

    /** Writes the storage strength. No-op on V1 layouts. */
    public void writeStorageStrength(MemorySegment segment, long offset, float strength) {
        headerLayout.writeStorageStrength(segment, offset, strength);
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
     * Immutable record holding all header fields across all layout versions.
     *
     * <p>V1-only code can use the 8-arg constructor; the extended fields
     * default to {@code arousal=0} and {@code storageStrength=1.0f}.</p>
     *
     * <p><b>TODO (JDK 28+ / Project Valhalla):</b> Convert to {@code value record} once
     * JEP 401 (Value Classes) is available. As a value class, CognitiveHeader would
     * be identity-free and scalarizable by the JIT — the 30 bytes of payload would
     * live in CPU registers instead of as a 48-byte heap object. When nested inside
     * {@code ScoredRecord} (also a value class), both would be flattened inline,
     * eliminating pointer indirection and reducing per-candidate cost from ~96B
     * heap to 0B (scalarized). Specialized generics (JEP 402) would further enable
     * flat storage in generic collections like {@code PriorityQueue}.</p>
     *
     * @param timestampMs     when the memory was formed (epoch millis)
     * @param synapticTags    64-bit Bloom filter of contextual markers
     * @param exactNorm       L2 norm for SIMD distance computation
     * @param importance      base importance (set by Prediction Error engine)
     * @param agentRecallCount     LTP reinforcement counter
     * @param centroidId      IVF partition routing ID
     * @param valence         signed emotion/reward (-128 to +127)
     * @param flags           bit field (tombstone, type, consolidated, pinned, resolved)
     * @param arousal         emotional intensity (unsigned 0-255, V2+)
     * @param storageStrength Two-Factor Memory storage strength (V2+, default 1.0f)
     */
    public record CognitiveHeader(
            long timestampMs,
            long synapticTags,
            float exactNorm,
            float importance,
            int agentRecallCount,
            short centroidId,
            byte valence,
            byte flags,
            // ── Extended fields (V2+) ──
            byte arousal,
            float storageStrength
    ) {
        /**
         * V1-compatible constructor — defaults for extended fields.
         *
         * <p>Provides backward compatibility for code that constructs headers
         * without arousal or storage strength fields.</p>
         */
        public CognitiveHeader(long timestampMs, long synapticTags, float exactNorm,
                                float importance, int agentRecallCount, short centroidId,
                                byte valence, byte flags) {
            this(timestampMs, synapticTags, exactNorm, importance,
                 agentRecallCount, centroidId, valence, flags,
                 (byte) 0, 1.0f);
        }

        /**
         * Creates a new header for initial ingestion with default recall count and valence.
         */
        public static CognitiveHeader create(long timestampMs, long synapticTags, float exactNorm,
                                              float importance, short centroidId, MemoryType memoryType) {
            byte flags = SynapticHeaderConstants.withMemoryType((byte) 0, memoryType.ordinal());
            return new CognitiveHeader(timestampMs, synapticTags, exactNorm, importance,
                    0, centroidId, (byte) 0, flags);
        }

        /**
         * Creates a new header with arousal for V2+ ingestion.
         */
        public static CognitiveHeader createWithArousal(long timestampMs, long synapticTags,
                                                          float exactNorm, float importance,
                                                          short centroidId, MemoryType memoryType,
                                                          byte valence, byte arousal) {
            byte flags = SynapticHeaderConstants.withMemoryType((byte) 0, memoryType.ordinal());
            return new CognitiveHeader(timestampMs, synapticTags, exactNorm, importance,
                    0, centroidId, valence, flags, arousal, 1.0f);
        }

        /**
         * Creates a new header with source modality for multimodal ingestion.
         *
         * <p>Encodes both the memory type (bits 1-2) and source modality (bits 6-7)
         * into the flags byte. Used by the ingestion pipeline when processing
         * non-text content (images, audio, video).</p>
         *
         * @param timestampMs  when the memory was formed (epoch millis)
         * @param synapticTags 64-bit Bloom filter of contextual markers
         * @param exactNorm    L2 norm for SIMD distance computation
         * @param importance   base importance (set by Prediction Error engine)
         * @param centroidId   IVF partition routing ID
         * @param memoryType   cognitive memory tier
         * @param modality     source modality (TEXT, IMAGE, AUDIO, VIDEO)
         * @param valence      signed emotion/reward (-128 to +127)
         * @param arousal      emotional intensity (unsigned 0-255)
         */
        public static CognitiveHeader createWithModality(long timestampMs, long synapticTags,
                                                          float exactNorm, float importance,
                                                          short centroidId, MemoryType memoryType,
                                                          SourceModality modality,
                                                          byte valence, byte arousal) {
            byte flags = SynapticHeaderConstants.withMemoryType((byte) 0, memoryType.ordinal());
            if (modality != null && modality != SourceModality.TEXT) {
                flags = SynapticHeaderConstants.withSourceModality(flags, modality.ordinal());
            }
            return new CognitiveHeader(timestampMs, synapticTags, exactNorm, importance,
                    0, centroidId, valence, flags, arousal, 1.0f);
        }
    }
}
