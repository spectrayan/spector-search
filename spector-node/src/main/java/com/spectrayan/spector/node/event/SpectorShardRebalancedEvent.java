package com.spectrayan.spector.node.event;

import java.time.Instant;

/** Fired when shard assignments are rebalanced across the cluster. */
public record SpectorShardRebalancedEvent(
        String nodeId, Instant timestamp,
        int totalShards, int localShards
) implements SpectorEvent {
    @Override public String eventType() { return "cluster.shard_rebalanced"; }
}
