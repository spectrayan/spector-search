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

import java.util.Collections;
import java.util.Map;

/**
 * Represents the current cluster topology including all known nodes and shard assignments.
 *
 * @param nodes  map of node ID to node info
 * @param shards map of shard index to list of shard assignments (primary + replicas)
 */
public record ClusterTopology(
        Map<String, NodeInfo> nodes,
        Map<Integer, java.util.List<ShardAssignment>> shards
) {

    /**
     * Returns an unmodifiable view of the nodes map.
     */
    @Override
    public Map<String, NodeInfo> nodes() {
        return Collections.unmodifiableMap(nodes);
    }

    /**
     * Returns an unmodifiable view of the shards map.
     */
    @Override
    public Map<Integer, java.util.List<ShardAssignment>> shards() {
        return Collections.unmodifiableMap(shards);
    }
}
