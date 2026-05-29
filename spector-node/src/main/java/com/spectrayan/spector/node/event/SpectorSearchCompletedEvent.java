package com.spectrayan.spector.node.event;

import java.time.Instant;

/** Fired when a search query completes successfully. */
public record SpectorSearchCompletedEvent(
        String nodeId, Instant timestamp,
        int resultCount, long latencyMs, String searchMode
) implements SpectorEvent {
    @Override public String eventType() { return "search.completed"; }
}
