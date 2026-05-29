package com.spectrayan.spector.cluster;

import java.util.List;

/**
 * Configuration for a Spector search cluster.
 *
 * @param shardCount   total number of shards in the cluster
 * @param nodes        list of shard node endpoints
 * @param replicaCount number of replicas per shard (0 = no replication)
 * @param shardStrategy partitioning strategy
 */
public record ClusterConfig(
        int shardCount,
        List<NodeEndpoint> nodes,
        int replicaCount,
        ShardStrategy shardStrategy
) {
    /**
     * A shard node endpoint.
     *
     * @param shardId  unique shard identifier
     * @param host     hostname or IP
     * @param port     gRPC port
     */
    public record NodeEndpoint(String shardId, String host, int port) {
        /** Returns the gRPC target string. */
        public String target() { return host + ":" + port; }
    }

    /** Shard partitioning strategy. */
    public enum ShardStrategy {
        /** Consistent hashing on document ID. */
        HASH,
        /** Range-based partitioning on document ID. */
        RANGE
    }

    /** Creates a single-shard configuration (no distribution). */
    public static ClusterConfig singleNode(String host, int port) {
        return new ClusterConfig(1,
                List.of(new NodeEndpoint("shard-0", host, port)),
                0, ShardStrategy.HASH);
    }

    /** Creates a multi-shard configuration. */
    public static ClusterConfig multiNode(List<NodeEndpoint> nodes) {
        return new ClusterConfig(nodes.size(), nodes, 0, ShardStrategy.HASH);
    }

    /**
     * Returns the shard ID for a given document.
     *
     * @param docId document identifier
     * @return shard index (0-based)
     */
    public int shardFor(String docId) {
        return switch (shardStrategy) {
            case HASH -> Math.abs(docId.hashCode()) % shardCount;
            case RANGE -> rangePartition(docId);
        };
    }

    private int rangePartition(String docId) {
        // Simple lexicographic range partitioning
        if (docId.isEmpty()) return 0;
        return (docId.charAt(0) * 256 + (docId.length() > 1 ? docId.charAt(1) : 0)) % shardCount;
    }
}
