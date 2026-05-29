package com.spectrayan.spector.node.event;

import java.time.Instant;

/** Fired when the search index is rebuilt or optimized (e.g., HNSW re-indexing). */
public record SpectorIndexRebuiltEvent(
        String nodeId, Instant timestamp,
        String indexType, long documentCount, long rebuildTimeMs
) implements SpectorEvent {
    @Override public String eventType() { return "engine.index_rebuilt"; }
}
