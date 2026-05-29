package com.spectrayan.spector.node.event;

import java.time.Instant;

/** Fired when the node has started and is ready to accept requests. */
public record SpectorNodeStartedEvent(
        String nodeId, Instant timestamp,
        int port, String mode
) implements SpectorEvent {
    @Override public String eventType() { return "node.started"; }
}
