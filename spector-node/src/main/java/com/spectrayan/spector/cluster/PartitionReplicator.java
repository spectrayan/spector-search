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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Partition-level snapshot replicator for directory-based partition storage.
 *
 * <h3>Design</h3>
 * <p>Works alongside the existing {@link ReplicationManager} to replicate
 * semantic memory partitions at the file level. While the ReplicationManager
 * handles shard-level write replication, this replicator handles the heavier
 * partition snapshot shipping needed when:</p>
 * <ul>
 *   <li>A new partition rolls (immutable file can be shipped once)</li>
 *   <li>A partition is compacted (compacted file replaces old version)</li>
 *   <li>A new replica joins and needs a full snapshot</li>
 * </ul>
 *
 * <h3>Protocol</h3>
 * <ol>
 *   <li>When a partition becomes immutable (full), register it for replication</li>
 *   <li>For each replica endpoint, ship the partition file via snapshot transfer</li>
 *   <li>Track which partitions have been shipped to which replicas</li>
 *   <li>On compaction, re-ship only the compacted partition</li>
 * </ol>
 *
 * <h3>Optimization</h3>
 * <p>Immutable partitions are shipped exactly once per replica. Only the active
 * (mutable) partition requires WAL-based delta replication via the
 * {@link ReplicationManager}.</p>
 */
public class PartitionReplicator {

    private static final Logger log = LoggerFactory.getLogger(PartitionReplicator.class);

    /**
     * Tracks which partitions have been shipped to which replica endpoints.
     * Key: partition file name, Value: set of replica endpoints that have received it.
     */
    private final Map<String, List<String>> shippedPartitions = new ConcurrentHashMap<>();

    /** The replication manager for accessing replica endpoints. */
    private final ReplicationManager replicationManager;

    /** The shard index this replicator is responsible for. */
    private final int shardIndex;

    /**
     * Creates a partition replicator for a specific shard.
     *
     * @param replicationManager the parent replication manager
     * @param shardIndex         the shard index for this replicator
     */
    public PartitionReplicator(ReplicationManager replicationManager, int shardIndex) {
        this.replicationManager = replicationManager;
        this.shardIndex = shardIndex;
    }

    /**
     * Registers a newly-rolled (immutable) partition for replication.
     *
     * <p>This should be called when a partition becomes full and a new
     * active partition is created. The immutable partition is then queued
     * for snapshot shipping to all active replicas.</p>
     *
     * @param partitionPath the path to the immutable partition file
     */
    public void registerImmutablePartition(Path partitionPath) {
        String fileName = partitionPath.getFileName().toString();
        shippedPartitions.putIfAbsent(fileName, new ArrayList<>());

        log.info("Registered immutable partition {} for replication (shard {})",
                fileName, shardIndex);

        // Ship to all active replicas
        List<String> activeEndpoints = replicationManager.getActiveReplicaEndpoints(shardIndex);
        for (String endpoint : activeEndpoints) {
            shipPartition(partitionPath, endpoint);
        }
    }

    /**
     * Notifies the replicator that a partition has been compacted.
     *
     * <p>The compacted partition file replaces the old version on all replicas.
     * This clears the shipping record for this partition and re-ships.</p>
     *
     * @param partitionPath the path to the compacted partition file
     */
    public void notifyCompacted(Path partitionPath) {
        String fileName = partitionPath.getFileName().toString();
        shippedPartitions.put(fileName, new ArrayList<>());

        log.info("Partition {} compacted — re-shipping to all replicas (shard {})",
                fileName, shardIndex);

        List<String> activeEndpoints = replicationManager.getActiveReplicaEndpoints(shardIndex);
        for (String endpoint : activeEndpoints) {
            shipPartition(partitionPath, endpoint);
        }
    }

    /**
     * Ships all unshipped partitions to a newly-joined replica.
     *
     * <p>Called when a new replica joins or an existing replica recovers.
     * Ships all partition files that the replica does not yet have.</p>
     *
     * @param partitionDir    the directory containing partition files
     * @param replicaEndpoint the endpoint of the new/recovering replica
     * @return the number of partitions shipped
     */
    public int syncAllPartitions(Path partitionDir, String replicaEndpoint) {
        int shipped = 0;

        try (var stream = Files.newDirectoryStream(partitionDir, "semantic-*.mem")) {
            for (Path partitionPath : stream) {
                String fileName = partitionPath.getFileName().toString();
                List<String> shippedTo = shippedPartitions.getOrDefault(fileName, List.of());

                if (!shippedTo.contains(replicaEndpoint)) {
                    if (shipPartition(partitionPath, replicaEndpoint)) {
                        shipped++;
                    }
                }
            }
        } catch (IOException e) {
            log.error("Error listing partitions in {} for sync: {}", partitionDir, e.getMessage());
        }

        log.info("Synced {} partitions to replica {} (shard {})",
                shipped, replicaEndpoint, shardIndex);
        return shipped;
    }

    /**
     * Returns a snapshot of replication state for monitoring.
     *
     * @return a snapshot record with partition shipping status
     */
    public ReplicationSnapshot snapshot() {
        return new ReplicationSnapshot(
                shardIndex,
                shippedPartitions.size(),
                replicationManager.getActiveReplicaEndpoints(shardIndex).size(),
                Instant.now()
        );
    }

    // ─────────────── Internal ───────────────

    /**
     * Ships a partition file to a replica endpoint.
     *
     * <p>In a production implementation, this would use gRPC streaming
     * or a chunked HTTP transfer to send the partition file.</p>
     *
     * @param partitionPath   the partition file to ship
     * @param replicaEndpoint the target replica endpoint
     * @return true if shipping succeeded
     */
    private boolean shipPartition(Path partitionPath, String replicaEndpoint) {
        String fileName = partitionPath.getFileName().toString();

        try {
            long fileSize = Files.size(partitionPath);
            log.debug("Shipping partition {} ({} KB) to {} (shard {})",
                    fileName, fileSize / 1024, replicaEndpoint, shardIndex);

            // TODO: Implement actual network transfer via gRPC or HTTP
            // For now, record the shipping as successful
            shippedPartitions.computeIfAbsent(fileName, k -> new ArrayList<>())
                    .add(replicaEndpoint);
            return true;
        } catch (IOException e) {
            log.error("Failed to ship partition {} to {}: {}",
                    fileName, replicaEndpoint, e.getMessage());
            return false;
        }
    }

    // ─────────────── Records ───────────────

    /**
     * Snapshot of partition replication state for monitoring/observability.
     *
     * @param shardIndex        the shard index
     * @param totalPartitions   total number of tracked partition files
     * @param activeReplicas    number of active replica endpoints
     * @param timestamp         when this snapshot was taken
     */
    public record ReplicationSnapshot(
            int shardIndex,
            int totalPartitions,
            int activeReplicas,
            Instant timestamp
    ) {}
}
