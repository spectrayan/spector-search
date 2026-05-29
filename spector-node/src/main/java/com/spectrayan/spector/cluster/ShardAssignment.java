package com.spectrayan.spector.cluster;

/**
 * Represents a shard assignment to a node with a specific role.
 *
 * @param shardIndex   the shard index
 * @param nodeEndpoint the endpoint of the node hosting this shard
 * @param role         the role of this assignment (PRIMARY or REPLICA)
 */
public record ShardAssignment(int shardIndex, String nodeEndpoint, ShardRole role) {
}
