package com.spectrayan.spector.node.event;

import java.time.Instant;

/** Fired when a peer node joins the cluster. */
public record SpectorNodeJoinedEvent(
        String nodeId, Instant timestamp,
        String joinedNodeId, String endpoint
) implements SpectorEvent {
    @Override public String eventType() { return "cluster.node_joined"; }
}
