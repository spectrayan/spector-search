package com.spectrayan.spector.cluster;

/**
 * Represents the current status of a node in the cluster.
 */
public enum NodeStatus {
    /** Node is actively participating and responding to heartbeats. */
    ACTIVE,
    /** Node has failed heartbeat checks and is considered down. */
    UNAVAILABLE,
    /** Node is recovering and synchronizing data. */
    SYNCING
}
