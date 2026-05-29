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
