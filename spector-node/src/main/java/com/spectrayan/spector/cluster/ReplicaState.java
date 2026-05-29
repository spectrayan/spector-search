package com.spectrayan.spector.cluster;

/**
 * Represents the state of a replica in the replication group.
 */
public enum ReplicaState {
    /** Replica is fully synchronized and serving reads. */
    ACTIVE,
    /** Replica is synchronizing with the primary (not serving reads). */
    SYNCING,
    /** Replica is unreachable/failed. */
    UNAVAILABLE
}
