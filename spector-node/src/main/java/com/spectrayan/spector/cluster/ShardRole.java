package com.spectrayan.spector.cluster;

/**
 * Role of a shard assignment on a node.
 */
public enum ShardRole {
    /** The authoritative copy of the shard data. */
    PRIMARY,
    /** A replica copy for fault tolerance. */
    REPLICA
}
