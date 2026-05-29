package com.spectrayan.spector.node.event;

import java.time.Instant;

/** Fired when a peer node leaves the cluster (heartbeat failure or graceful shutdown). */
public record SpectorNodeLeftEvent(
        String nodeId, Instant timestamp,
        String leftNodeId, String reason
) implements SpectorEvent {
    @Override public String eventType() { return "cluster.node_left"; }
}
