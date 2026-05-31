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
     * @throws SpectorValidationException if documentId is null or empty
     */
    int assignShard(String documentId);

    /**
     * Adds a new shard to the cluster topology.
     *
     * @param shardIndex   the index of the new shard
     * @param nodeEndpoint the network endpoint (host:port) of the node hosting this shard
     * @throws SpectorValidationException if shardIndex is out of configured range or endpoint is invalid
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
