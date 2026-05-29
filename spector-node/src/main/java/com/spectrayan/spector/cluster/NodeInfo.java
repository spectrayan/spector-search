package com.spectrayan.spector.cluster;

import java.time.Instant;

/**
 * Information about a node in the cluster.
 *
 * @param nodeId        unique node identifier
 * @param endpoint      network endpoint (host:port) for the node
 * @param status        current status of the node
 * @param lastHeartbeat timestamp of the last successful heartbeat received
 */
public record NodeInfo(String nodeId, String endpoint, NodeStatus status, Instant lastHeartbeat) {

    /**
     * Creates a new NodeInfo with updated status.
     *
     * @param newStatus the new status
     * @return a new NodeInfo with the updated status
     */
    public NodeInfo withStatus(NodeStatus newStatus) {
        return new NodeInfo(nodeId, endpoint, newStatus, lastHeartbeat);
    }

    /**
     * Creates a new NodeInfo with updated heartbeat timestamp.
     *
     * @param heartbeatTime the new heartbeat timestamp
     * @return a new NodeInfo with the updated heartbeat time
     */
    public NodeInfo withHeartbeat(Instant heartbeatTime) {
        return new NodeInfo(nodeId, endpoint, status, heartbeatTime);
    }
}
