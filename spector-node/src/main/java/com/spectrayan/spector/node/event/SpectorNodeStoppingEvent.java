package com.spectrayan.spector.node.event;

import java.time.Instant;

/** Fired when the node is shutting down gracefully. */
public record SpectorNodeStoppingEvent(
        String nodeId, Instant timestamp,
        String reason
) implements SpectorEvent {
    @Override public String eventType() { return "node.stopping"; }
}
