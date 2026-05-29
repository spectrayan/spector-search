package com.spectrayan.spector.node.event;

import java.time.Instant;

/** Fired when a search query fails with an error. */
public record SpectorSearchFailedEvent(
        String nodeId, Instant timestamp,
        String searchMode, String errorMessage
) implements SpectorEvent {
    @Override public String eventType() { return "search.failed"; }
}
