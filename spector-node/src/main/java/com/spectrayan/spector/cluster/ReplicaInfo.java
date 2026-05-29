package com.spectrayan.spector.cluster;

import java.time.Instant;

/**
 * Information about a specific replica in a replication group.
 *
 * @param replicaId unique identifier of the replica
 * @param endpoint  network endpoint (host:port) of the replica node
 * @param state     current state of the replica
 * @param lastSyncTimestamp the timestamp of the last successful synchronization
 */
public record ReplicaInfo(
        String replicaId,
        String endpoint,
        ReplicaState state,
        Instant lastSyncTimestamp
) {
    /**
     * Creates a new ReplicaInfo with an updated state.
     */
    public ReplicaInfo withState(ReplicaState newState) {
        return new ReplicaInfo(replicaId, endpoint, newState, lastSyncTimestamp);
    }

    /**
     * Creates a new ReplicaInfo with an updated sync timestamp.
     */
    public ReplicaInfo withSyncTimestamp(Instant timestamp) {
        return new ReplicaInfo(replicaId, endpoint, state, timestamp);
    }
}
