/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
