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
package com.spectrayan.spector.memory;

import com.spectrayan.spector.memory.graph.ExtractedEntity;
import com.spectrayan.spector.memory.neurodivergent.IngestionHints;

import java.util.List;

/**
 * Consolidated cognitive metadata for memory ingestion.
 *
 * <p>Carries all optional LLM-provided context that enriches a memory at ingestion
 * time: ICNU importance hints, pre-extracted entities, Hebbian co-activation edges,
 * and temporal chain links. This supplements (and can replace) the automatic
 * extraction pipelines when the caller already has this data.</p>
 *
 * <h3>Precedence Rules</h3>
 * <ul>
 *   <li><b>Entities:</b> If {@code entities} is non-empty, the {@code EntityExtractor}
 *       SPI is <em>not</em> called. Otherwise falls back to the configured extractor.</li>
 *   <li><b>Hebbian edges:</b> If {@code hebbianEdges} is non-empty, edges are added
 *       <em>in addition to</em> any automatic co-ingestion strengthening.</li>
 *   <li><b>Temporal links:</b> If {@code temporalLinks} is non-empty, links are added
 *       <em>in addition to</em> automatic session-based linking.</li>
 *   <li><b>Hints:</b> If {@code hints} is non-null, ICNU fusion is applied during
 *       importance computation.</li>
 * </ul>
 *
 * <h3>MCP Usage</h3>
 * <pre>{@code
 *   // LLM provides full cognitive context in a single remember() call:
 *   memory.remember("mem-123", "The database crashed after migration...",
 *       MemoryType.EPISODIC, MemorySource.OBSERVED,
 *       IngestionContext.builder()
 *           .hints(new IngestionHints(0.8f, 0.6f, 0.9f))
 *           .entities(List.of(
 *               new ExtractedEntity("PostgreSQL", EntityType.TECHNOLOGY),
 *               new ExtractedEntity("migration-v3", EntityType.ARTIFACT)))
 *           .hebbianEdge("mem-120", 0.8f)
 *           .temporalLink("mem-122", 5)
 *           .build(),
 *       "database", "crash");
 * }</pre>
 *
 * @param hints               ICNU importance hints (nullable — novelty-only if absent)
 * @param entities            pre-extracted entities for graph population (nullable — uses EntityExtractor if absent)
 * @param hebbianEdges        pre-computed Hebbian co-activation edges (nullable — auto co-ingestion still fires)
 * @param temporalLinks       pre-computed temporal chain links (nullable — auto session linking still fires)
 * @param overrideTimestampMs optional epoch-ms timestamp to use instead of {@code System.currentTimeMillis()}.
 *                            When null, the ingestion pipeline uses wall-clock time. Essential for benchmark
 *                            data and historical memory import where the original event time must be preserved
 *                            for correct temporal decay, circadian scoring, and temporal chain ordering.
 */
public record IngestionContext(
        IngestionHints hints,
        List<ExtractedEntity> entities,
        List<HebbianEdgeHint> hebbianEdges,
        List<TemporalLinkHint> temporalLinks,
        Long overrideTimestampMs
) {

    /** Backward-compatible constructor — no timestamp override. */
    public IngestionContext(IngestionHints hints, List<ExtractedEntity> entities,
                            List<HebbianEdgeHint> hebbianEdges, List<TemporalLinkHint> temporalLinks) {
        this(hints, entities, hebbianEdges, temporalLinks, null);
    }

    /** Empty context — triggers all automatic pipelines with novelty-only importance. */
    public static final IngestionContext EMPTY = new IngestionContext(null, null, null, null, null);

    /** Returns true if pre-extracted entities are provided. */
    public boolean hasEntities() { return entities != null && !entities.isEmpty(); }

    /** Returns true if Hebbian edge hints are provided. */
    public boolean hasHebbianEdges() { return hebbianEdges != null && !hebbianEdges.isEmpty(); }

    /** Returns true if temporal link hints are provided. */
    public boolean hasTemporalLinks() { return temporalLinks != null && !temporalLinks.isEmpty(); }

    /** Returns true if ICNU hints are provided. */
    public boolean hasHints() { return hints != null && !hints.isEmpty(); }

    /** Returns true if a timestamp override is provided. */
    public boolean hasTimestampOverride() { return overrideTimestampMs != null && overrideTimestampMs > 0; }

    /** Returns the effective timestamp — override if provided, otherwise wall-clock time. */
    public long effectiveTimestampMs() {
        return hasTimestampOverride() ? overrideTimestampMs : System.currentTimeMillis();
    }

    /**
     * Pre-computed Hebbian co-activation edge to an existing memory.
     *
     * <p>Links the newly ingested memory to an existing memory by ID,
     * with a specified association weight. Used when the caller (e.g., LLM)
     * already knows which memories are related.</p>
     *
     * @param targetMemoryId the ID of the existing memory to link to
     * @param weight         association strength (0.0–1.0)
     */
    public record HebbianEdgeHint(String targetMemoryId, float weight) {
        public HebbianEdgeHint {
            if (targetMemoryId == null || targetMemoryId.isBlank()) {
                throw new IllegalArgumentException("targetMemoryId must not be null or blank");
            }
            weight = Math.clamp(weight, 0f, 1f);
        }
    }

    /**
     * Pre-computed temporal chain link to a predecessor memory.
     *
     * <p>Establishes a temporal sequence relationship: the newly ingested
     * memory follows the specified predecessor in a conversation/session.</p>
     *
     * @param predecessorMemoryId the ID of the predecessor memory in the temporal chain
     * @param sessionId           the session identifier (groups related memories)
     */
    public record TemporalLinkHint(String predecessorMemoryId, int sessionId) {
        public TemporalLinkHint {
            if (predecessorMemoryId == null || predecessorMemoryId.isBlank()) {
                throw new IllegalArgumentException("predecessorMemoryId must not be null or blank");
            }
        }
    }

    /**
     * Creates a builder for constructing an IngestionContext.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link IngestionContext}.
     */
    public static final class Builder {
        private IngestionHints hints;
        private List<ExtractedEntity> entities;
        private java.util.List<HebbianEdgeHint> hebbianEdges;
        private java.util.List<TemporalLinkHint> temporalLinks;
        private Long overrideTimestampMs;

        public Builder hints(IngestionHints hints) {
            this.hints = hints;
            return this;
        }

        public Builder entities(List<ExtractedEntity> entities) {
            this.entities = entities;
            return this;
        }

        public Builder hebbianEdge(String targetMemoryId, float weight) {
            if (hebbianEdges == null) hebbianEdges = new java.util.ArrayList<>();
            hebbianEdges.add(new HebbianEdgeHint(targetMemoryId, weight));
            return this;
        }

        public Builder hebbianEdges(List<HebbianEdgeHint> edges) {
            this.hebbianEdges = edges;
            return this;
        }

        public Builder temporalLink(String predecessorMemoryId, int sessionId) {
            if (temporalLinks == null) temporalLinks = new java.util.ArrayList<>();
            temporalLinks.add(new TemporalLinkHint(predecessorMemoryId, sessionId));
            return this;
        }

        public Builder temporalLinks(List<TemporalLinkHint> links) {
            this.temporalLinks = links;
            return this;
        }

        /**
         * Sets a timestamp override for historical data import.
         * When set, the cognitive header uses this timestamp instead of wall-clock time.
         *
         * @param timestampMs epoch milliseconds of the original event
         */
        public Builder overrideTimestampMs(long timestampMs) {
            this.overrideTimestampMs = timestampMs;
            return this;
        }

        public IngestionContext build() {
            return new IngestionContext(hints, entities, hebbianEdges, temporalLinks, overrideTimestampMs);
        }
    }
}
