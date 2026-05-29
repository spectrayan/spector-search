package com.spectrayan.spector.node.event;

import java.time.Instant;

/** Fired when the node's health status changes (healthy → unhealthy or vice versa). */
public record SpectorNodeHealthChangedEvent(
        String nodeId, Instant timestamp,
        boolean healthy, String reason
) implements SpectorEvent {
    @Override public String eventType() { return "node.health_changed"; }
}
