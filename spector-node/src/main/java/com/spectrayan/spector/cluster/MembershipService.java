package com.spectrayan.spector.cluster;

import com.spectrayan.spector.commons.error.SpectorMembershipException;

import java.util.Set;

/**
 * Service interface for cluster membership management.
 *
 * <p>Tracks node liveness via heartbeats and triggers topology changes
 * when nodes join or leave the cluster.</p>
 */
public interface MembershipService extends AutoCloseable {

    /**
     * Starts the membership service, beginning periodic heartbeat monitoring.
     */
    void start();

    /**
     * Registers a new node in the cluster topology.
     *
     * <p>Registration triggers shard rebalancing within 5 seconds of successful registration.
     * If registration fails due to a communication error, it is retried up to 3 times
     * with a 1-second delay between attempts.</p>
     *
     * @param nodeId   unique identifier for the node
     * @param endpoint network endpoint (host:port) for the node
     * @throws SpectorValidationException if nodeId or endpoint is null or blank
     * @throws SpectorMembershipException      if registration fails after all retry attempts
     */
    void registerNode(String nodeId, String endpoint);

    /**
     * Marks a node as unavailable and ceases routing requests to it.
     *
     * <p>Triggers shard rebalancing within 5 seconds of the status change.</p>
     *
     * @param nodeId the node to mark as unavailable
     * @throws SpectorValidationException if nodeId is null, blank, or not found in the cluster
     */
    void markUnavailable(String nodeId);

    /**
     * Returns the set of currently active (healthy) node IDs.
     *
     * @return an unmodifiable set of active node IDs
     */
    Set<String> getActiveNodes();

    /**
     * Returns the current cluster topology including all nodes and shard assignments.
     *
     * @return the current cluster topology
     */
    ClusterTopology getTopology();

    /**
     * Reports an unavailable shard condition.
     *
     * @param shardIndex the index of the shard that became unavailable
     * @param reason     description of why the shard is unavailable
     */
    void reportUnavailableShard(int shardIndex, String reason);

    /**
     * Stops the membership service and releases resources.
     */
    @Override
    void close();
}
