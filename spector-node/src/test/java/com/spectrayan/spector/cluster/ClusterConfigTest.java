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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ClusterConfig} — shard routing and configuration.
 */
class ClusterConfigTest {

    @Test
    void singleNode_createsOneShard() {
        var config = ClusterConfig.singleNode("localhost", 50051);
        assertEquals(1, config.shardCount());
        assertEquals(1, config.nodes().size());
        assertEquals("shard-0", config.nodes().get(0).shardId());
    }

    @Test
    void multiNode_createsManyShards() {
        var nodes = List.of(
                new ClusterConfig.NodeEndpoint("shard-0", "host1", 50051),
                new ClusterConfig.NodeEndpoint("shard-1", "host2", 50051),
                new ClusterConfig.NodeEndpoint("shard-2", "host3", 50051)
        );
        var config = ClusterConfig.multiNode(nodes);
        assertEquals(3, config.shardCount());
    }

    @Test
    void hashSharding_isConsistent() {
        var nodes = List.of(
                new ClusterConfig.NodeEndpoint("shard-0", "host1", 50051),
                new ClusterConfig.NodeEndpoint("shard-1", "host2", 50051)
        );
        var config = ClusterConfig.multiNode(nodes);

        // Same doc ID should always route to same shard
        int shard1 = config.shardFor("doc-123");
        int shard2 = config.shardFor("doc-123");
        assertEquals(shard1, shard2, "Same doc should route to same shard");

        // Different docs should distribute across shards
        int[] distribution = new int[2];
        for (int i = 0; i < 100; i++) {
            distribution[config.shardFor("doc-" + i)]++;
        }
        assertTrue(distribution[0] > 10, "Shard 0 should get some docs");
        assertTrue(distribution[1] > 10, "Shard 1 should get some docs");
    }

    @Test
    void nodeEndpoint_target() {
        var endpoint = new ClusterConfig.NodeEndpoint("shard-0", "localhost", 50051);
        assertEquals("localhost:50051", endpoint.target());
    }

    @Test
    void shardFor_handlesEdgeCases() {
        var config = ClusterConfig.singleNode("localhost", 50051);
        assertEquals(0, config.shardFor(""));
        assertEquals(0, config.shardFor("a"));
        assertEquals(0, config.shardFor("any-doc-id")); // single shard = always 0
    }
}
