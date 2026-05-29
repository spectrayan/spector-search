package com.spectrayan.spector.cluster;

import java.util.Map;

/**
 * Manages document-to-shard assignment and rebalancing for distributed search.
 *
 * <p>Implementations partition documents across shards using a deterministic
 * assignment strategy, ensuring the same document ID always maps to the same
 * shard given the same configuration.</p>
 */
public interface ShardManager {

    /**
     * Assigns a document to a shard based on its identifier.
     *
     * @param documentId the document identifier
     * @return the shard index (0-based) for the document
     * @throws IllegalArgumentException if documentId is null or empty
     */
    int assignShard(String documentId);

    /**
     * Adds a new shard to the cluster topology.
     *
     * @param shardIndex   the index of the new shard
     * @param nodeEndpoint the network endpoint (host:port) of the node hosting this shard
     * @throws IllegalArgumentException if shardIndex is out of configured range or endpoint is invalid
     */
    void addShard(int shardIndex, String nodeEndpoint);

    /**
     * Triggers a rebalance operation, migrating only documents affected by topology changes.
     *
     * <p>Documents whose consistent hash maps to newly added shards are migrated;
     * all other documents remain on their original shard.</p>
     */
    void rebalance();

    /**
     * Returns the current shard assignment map.
     *
     * <p>This map is guaranteed to reflect topology changes within 100ms.</p>
     *
     * @return an unmodifiable map of shard index to node endpoint
     */
    Map<Integer, String> getShardAssignmentMap();
}
