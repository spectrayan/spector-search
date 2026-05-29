package com.spectrayan.spector.node.event;

import java.time.Instant;

/** Fired when a shard replica finishes synchronization with its primary. */
public record SpectorReplicaSyncCompletedEvent(
        String nodeId, Instant timestamp,
        String shardId, long documentsSynced
) implements SpectorEvent {
    @Override public String eventType() { return "cluster.replica_synced"; }
}
