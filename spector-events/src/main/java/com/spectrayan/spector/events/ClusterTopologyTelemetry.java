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
package com.spectrayan.spector.events;

import java.util.List;

/**
 * Cluster topology telemetry — periodic snapshot of cluster state.
 *
 * @param nodes            list of node state snapshots
 * @param replicationLinks pairs of node IDs with replication links
 */
public record ClusterTopologyTelemetry(
        List<ClusterNodeSnapshot> nodes,
        List<String[]> replicationLinks
) implements TelemetryEvent {

    /**
     * State snapshot of a single cluster node.
     *
     * @param nodeId          unique node identifier
     * @param status          "active", "draining", or "down"
     * @param shardCount      number of shards on this node
     * @param memoryUsedBytes memory used in bytes
     * @param queryRate       queries per second
     */
    public record ClusterNodeSnapshot(
            String nodeId,
            String status,
            int shardCount,
            long memoryUsedBytes,
            double queryRate
    ) {}
}
