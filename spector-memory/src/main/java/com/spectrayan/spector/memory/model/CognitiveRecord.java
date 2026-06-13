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
package com.spectrayan.spector.memory.model;

import com.spectrayan.spector.memory.cortex.MemorySource;
import com.spectrayan.spector.memory.synapse.SynapticHeaderConstants;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Complete cognitive snapshot of a single memory — the "X-ray" view.
 *
 * <p>Combines data from three subsystems into a single record:</p>
 * <ul>
 *   <li><b>MemoryIndex</b>: id, text, source, tags, location</li>
 *   <li><b>CognitiveHeader</b> (64-byte off-heap): timestamp, synaptic tags bloom,
 *       importance, recall counts, valence, arousal, storage strength, flags</li>
 *   <li><b>Vector payload</b>: quantized INT8 vector bytes</li>
 * </ul>
 *
 * <p>This closes the gap where no single API call could return the full
 * text ↔ cognitive header ↔ vector correlation for a given memory.</p>
 *
 * <h3>Usage via MCP</h3>
 * <pre>{@code
 *   // Java API
 *   CognitiveRecord record = memory.inspect("mem-42");
 *
 *   // MCP tool
 *   { "tool": "memory_inspect", "arguments": { "id": "mem-42" } }
 * }</pre>
 *
 * @param id                 unique memory identifier
 * @param text               raw memory text content
 * @param memoryType         cognitive tier (WORKING, EPISODIC, SEMANTIC, PROCEDURAL)
 * @param source             provenance (USER_STATED, OBSERVED, INFERRED, PROCEDURAL)
 * @param tags               synaptic tag strings (human-readable labels)
 * @param timestampMs        when the memory was created (epoch millis)
 * @param synapticTags       64-bit Bloom filter of encoded tag hashes
 * @param exactNorm          L2 norm of the vector (for distance computation)
 * @param importance         importance score (0.0–10.0, set by Prediction Error engine)
 * @param agentRecallCount   times the agent explicitly reinforced this memory
 * @param spectorRecallCount times the memory appeared in recall results (auto-LTP)
 * @param centroidId         IVF partition routing ID
 * @param valence            emotional coloring (-128 to +127)
 * @param arousal            emotional intensity (unsigned 0-255)
 * @param storageStrength    Two-Factor Memory storage strength (≥1.0)
 * @param flags              raw flags byte (tombstone, consolidated, pinned, resolved)
 * @param quantizedVector    quantized INT8 vector bytes (nullable if not requested)
 * @param partitionIndex     partition where this memory is stored (-1 for non-partitioned)
 * @param byteOffset         byte offset within the tier's MemorySegment
 *
 * @see com.spectrayan.spector.memory.SpectorMemory#inspect(String)
 */
public record CognitiveRecord(
        // ── Identity ──
        String id,
        String text,
        MemoryType memoryType,
        MemorySource source,
        String[] tags,

        // ── Cognitive Header (decoded from 64-byte off-heap) ──
        long timestampMs,
        long synapticTags,
        float exactNorm,
        float importance,
        int agentRecallCount,
        int spectorRecallCount,
        short centroidId,
        byte valence,
        byte arousal,
        float storageStrength,
        byte flags,

        // ── Vector ──
        byte[] quantizedVector,

        // ── Physical Location ──
        int partitionIndex,
        long byteOffset
) {

    // ══════════════════════════════════════════════════════════════
    // FLAG INTROSPECTION HELPERS
    // ══════════════════════════════════════════════════════════════

    /** Returns true if this memory has been logically deleted. */
    public boolean isTombstoned() {
        return SynapticHeaderConstants.isTombstoned(flags);
    }

    /** Returns true if this memory has been consolidated (reflected into Semantic tier). */
    public boolean isConsolidated() {
        return SynapticHeaderConstants.isConsolidated(flags);
    }

    /** Returns true if this memory is pinned (exempt from decay and pruning). */
    public boolean isPinned() {
        return SynapticHeaderConstants.isPinned(flags);
    }

    /** Returns true if this memory is resolved (Zeigarnik Effect — task completed). */
    public boolean isResolved() {
        return SynapticHeaderConstants.isResolved(flags);
    }

    /** Returns the source modality (TEXT, IMAGE, AUDIO, VIDEO) decoded from the flags byte. */
    public SourceModality sourceModality() {
        return SourceModality.fromOrdinal(SynapticHeaderConstants.sourceModalityOrdinal(flags));
    }

    /** Returns true if this memory is multimodal (non-text source). */
    public boolean isMultimodal() {
        return sourceModality() != SourceModality.TEXT;
    }

    /** Returns the creation timestamp as an Instant. */
    public Instant createdAt() {
        return Instant.ofEpochMilli(timestampMs);
    }

    /** Returns the age of the memory in days (from now). */
    public float ageDays() {
        long nowMs = System.currentTimeMillis();
        return (nowMs - timestampMs) / (1000f * 60 * 60 * 24);
    }

    /** Returns the total recall count (agent + spector). */
    public int totalRecallCount() {
        return agentRecallCount + spectorRecallCount;
    }

    /** Returns true if the quantized vector is available. */
    public boolean hasVector() {
        return quantizedVector != null && quantizedVector.length > 0;
    }

    /** Shared thread-safe ObjectMapper — Jackson 3 ObjectMapper is immutable after construction. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Returns a JSON-compatible string representation of this cognitive record.
     *
     * <p>Suitable for MCP tool responses and memory export. The quantized vector
     * is represented as a hex string to avoid JSON array verbosity.</p>
     */
    public String toJson() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", id);
        node.put("text", text);
        node.put("memoryType", memoryType.name());
        node.put("source", source.name());
        var tagsArray = node.putArray("tags");
        if (tags != null) {
            for (String tag : tags) tagsArray.add(tag);
        }
        node.put("createdAt", createdAt().toString());
        node.put("ageDays", Float.parseFloat(String.format("%.2f", ageDays())));
        node.put("synapticTags", "0x" + Long.toHexString(synapticTags));
        node.put("exactNorm", exactNorm);
        node.put("importance", Float.parseFloat(String.format("%.4f", importance)));
        node.put("agentRecallCount", agentRecallCount);
        node.put("spectorRecallCount", spectorRecallCount);
        node.put("centroidId", centroidId);
        node.put("valence", valence);
        node.put("arousal", Byte.toUnsignedInt(arousal));
        node.put("storageStrength", Float.parseFloat(String.format("%.4f", storageStrength)));
        node.put("tombstoned", isTombstoned());
        node.put("consolidated", isConsolidated());
        node.put("pinned", isPinned());
        node.put("resolved", isResolved());
        node.put("sourceModality", sourceModality().name());
        node.put("multimodal", isMultimodal());
        node.put("partitionIndex", partitionIndex);
        node.put("byteOffset", byteOffset);
        if (quantizedVector != null) {
            node.put("quantizedVectorHex", HexFormat.of().formatHex(quantizedVector));
            node.put("vectorDimensions", quantizedVector.length);
        }
        return node.toString();
    }
}
