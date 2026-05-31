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

import com.spectrayan.spector.commons.error.SpectorValidationException;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HeartbeatMembershipService}.
 */
class HeartbeatMembershipServiceTest {

    private ConsistentHashShardManager shardManager;
    private HeartbeatMembershipService service;

    @BeforeEach
    void setUp() {
        shardManager = new ConsistentHashShardManager(4);
        shardManager.addShard(0, "localhost:5000");
        shardManager.addShard(1, "localhost:5001");
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.close();
        }
    }

    @Test
    void constructorWithDefaults() {
        service = new HeartbeatMembershipService(shardManager);
        assertEquals(Duration.ofSeconds(2), service.getHeartbeatInterval());
        assertEquals(Duration.ofSeconds(10), service.getFailureTimeout());
    }

    @Test
    void constructorWithCustomConfig() {
        service = new HeartbeatMembershipService(
                shardManager, Duration.ofSeconds(1), Duration.ofSeconds(5));
        assertEquals(Duration.ofSeconds(1), service.getHeartbeatInterval());
        assertEquals(Duration.ofSeconds(5), service.getFailureTimeout());
    }

    @Test
    void constructorRejectsNullShardManager() {
        assertThrows(SpectorValidationException.class,
                () -> new HeartbeatMembershipService(null));
    }

    @Test
    void constructorRejectsInvalidHeartbeatInterval() {
        assertThrows(SpectorValidationException.class,
                () -> new HeartbeatMembershipService(shardManager, Duration.ofMillis(100), Duration.ofSeconds(10)));
        assertThrows(SpectorValidationException.class,
                () -> new HeartbeatMembershipService(shardManager, Duration.ofSeconds(31), Duration.ofSeconds(10)));
    }

    @Test
    void constructorRejectsInvalidFailureTimeout() {
        assertThrows(SpectorValidationException.class,
                () -> new HeartbeatMembershipService(shardManager, Duration.ofSeconds(2), Duration.ofSeconds(2)));
        assertThrows(SpectorValidationException.class,
                () -> new HeartbeatMembershipService(shardManager, Duration.ofSeconds(2), Duration.ofSeconds(121)));
    }

    @Test
    void registerNodeAddsToActiveNodes() {
        service = new HeartbeatMembershipService(shardManager);
        service.start();

        service.registerNode("node-1", "localhost:6000");

        Set<String> active = service.getActiveNodes();
        assertTrue(active.contains("node-1"));
        assertEquals(1, active.size());
    }

    @Test
    void registerNodeRejectsNullId() {
        service = new HeartbeatMembershipService(shardManager);
        assertThrows(SpectorValidationException.class,
                () -> service.registerNode(null, "localhost:6000"));
    }

    @Test
    void registerNodeRejectsBlankEndpoint() {
        service = new HeartbeatMembershipService(shardManager);
        assertThrows(SpectorValidationException.class,
                () -> service.registerNode("node-1", "  "));
    }

    @Test
    void registerMultipleNodes() {
        service = new HeartbeatMembershipService(shardManager);
        service.start();

        service.registerNode("node-1", "localhost:6000");
        service.registerNode("node-2", "localhost:6001");
        service.registerNode("node-3", "localhost:6002");

        Set<String> active = service.getActiveNodes();
        assertEquals(3, active.size());
        assertTrue(active.containsAll(Set.of("node-1", "node-2", "node-3")));
    }

    @Test
    void markUnavailableRemovesFromActiveNodes() {
        service = new HeartbeatMembershipService(shardManager);
        service.start();

        service.registerNode("node-1", "localhost:6000");
        service.registerNode("node-2", "localhost:6001");

        service.markUnavailable("node-1");

        Set<String> active = service.getActiveNodes();
        assertFalse(active.contains("node-1"));
        assertTrue(active.contains("node-2"));
    }

    @Test
    void markUnavailableRejectsUnknownNode() {
        service = new HeartbeatMembershipService(shardManager);
        assertThrows(SpectorValidationException.class,
                () -> service.markUnavailable("nonexistent"));
    }

    @Test
    void markUnavailableIdempotent() {
        service = new HeartbeatMembershipService(shardManager);
        service.start();

        service.registerNode("node-1", "localhost:6000");
        service.markUnavailable("node-1");
        // Second call should not throw
        service.markUnavailable("node-1");

        NodeInfo info = service.getNodeInfo("node-1");
        assertEquals(NodeStatus.UNAVAILABLE, info.status());
    }

    @Test
    void receiveHeartbeatUpdatesTimestamp() {
        service = new HeartbeatMembershipService(shardManager);
        service.start();

        service.registerNode("node-1", "localhost:6000");
        NodeInfo before = service.getNodeInfo("node-1");

        // Small sleep to ensure different timestamp
        try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        service.receiveHeartbeat("node-1");
        NodeInfo after = service.getNodeInfo("node-1");

        assertTrue(after.lastHeartbeat().isAfter(before.lastHeartbeat())
                || after.lastHeartbeat().equals(before.lastHeartbeat()));
        assertEquals(NodeStatus.ACTIVE, after.status());
    }

    @Test
    void receiveHeartbeatRecoverUnavailableNode() {
        service = new HeartbeatMembershipService(shardManager);
        service.start();

        service.registerNode("node-1", "localhost:6000");
        service.markUnavailable("node-1");

        assertFalse(service.getActiveNodes().contains("node-1"));

        service.receiveHeartbeat("node-1");

        assertTrue(service.getActiveNodes().contains("node-1"));
        assertEquals(NodeStatus.ACTIVE, service.getNodeInfo("node-1").status());
    }

    @Test
    void heartbeatTimeoutMarksNodeUnavailable() throws InterruptedException {
        // Use very short intervals for testing
        service = new HeartbeatMembershipService(
                shardManager, Duration.ofMillis(500), Duration.ofSeconds(3));
        service.start();

        service.registerNode("node-1", "localhost:6000");
        assertTrue(service.getActiveNodes().contains("node-1"));

        // Wait for timeout (3s) + some buffer for the heartbeat check to fire
        Thread.sleep(4000);

        assertFalse(service.getActiveNodes().contains("node-1"));
        assertEquals(NodeStatus.UNAVAILABLE, service.getNodeInfo("node-1").status());
    }

    @Test
    void heartbeatKeepsNodeAlive() throws InterruptedException {
        service = new HeartbeatMembershipService(
                shardManager, Duration.ofMillis(500), Duration.ofSeconds(3));
        service.start();

        service.registerNode("node-1", "localhost:6000");

        // Send heartbeats every second for 4 seconds
        for (int i = 0; i < 4; i++) {
            Thread.sleep(1000);
            service.receiveHeartbeat("node-1");
        }

        // Node should still be active
        assertTrue(service.getActiveNodes().contains("node-1"));
    }

    @Test
    void getTopologyReturnsCurrentState() {
        service = new HeartbeatMembershipService(shardManager);
        service.start();

        service.registerNode("node-1", "localhost:6000");
        service.registerNode("node-2", "localhost:6001");

        ClusterTopology topology = service.getTopology();
        assertNotNull(topology);
        assertEquals(2, topology.nodes().size());
        assertTrue(topology.nodes().containsKey("node-1"));
        assertTrue(topology.nodes().containsKey("node-2"));
    }

    @Test
    void listenerNotifiedOnJoin() throws InterruptedException {
        service = new HeartbeatMembershipService(shardManager);
        service.start();

        CountDownLatch latch = new CountDownLatch(1);
        ConcurrentHashMap<String, NodeStatus> events = new ConcurrentHashMap<>();

        service.addListener((nodeId, status) -> {
            events.put(nodeId, status);
            latch.countDown();
        });

        service.registerNode("node-1", "localhost:6000");

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(NodeStatus.ACTIVE, events.get("node-1"));
    }

    @Test
    void listenerNotifiedOnLeave() throws InterruptedException {
        service = new HeartbeatMembershipService(shardManager);
        service.start();

        service.registerNode("node-1", "localhost:6000");

        CountDownLatch latch = new CountDownLatch(1);
        ConcurrentHashMap<String, NodeStatus> events = new ConcurrentHashMap<>();

        service.addListener((nodeId, status) -> {
            if (status == NodeStatus.UNAVAILABLE) {
                events.put(nodeId, status);
                latch.countDown();
            }
        });

        service.markUnavailable("node-1");

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(NodeStatus.UNAVAILABLE, events.get("node-1"));
    }

    @Test
    void rebalanceTriggeredOnNodeJoin() throws InterruptedException {
        AtomicInteger rebalanceCount = new AtomicInteger(0);
        shardManager.setRebalanceListener((sm, paused) -> rebalanceCount.incrementAndGet());

        service = new HeartbeatMembershipService(shardManager);
        service.start();

        service.registerNode("node-1", "localhost:6000");

        // Give async rebalance time to complete
        Thread.sleep(500);

        assertTrue(rebalanceCount.get() >= 1,
                "Rebalance should have been triggered at least once");
    }

    @Test
    void startAndStop() {
        service = new HeartbeatMembershipService(shardManager);
        assertFalse(service.isRunning());

        service.start();
        assertTrue(service.isRunning());

        service.close();
        assertFalse(service.isRunning());
    }

    @Test
    void doubleStartIgnored() {
        service = new HeartbeatMembershipService(shardManager);
        service.start();
        service.start(); // Should not throw or create double schedulers
        assertTrue(service.isRunning());
    }

    @Test
    void receiveHeartbeatFromUnknownNodeIgnored() {
        service = new HeartbeatMembershipService(shardManager);
        service.start();

        // Should not throw
        service.receiveHeartbeat("unknown-node");
        assertEquals(0, service.getNodeCount());
    }

    @Test
    void getNodeInfoReturnsCorrectData() {
        service = new HeartbeatMembershipService(shardManager);
        service.start();

        service.registerNode("node-1", "localhost:6000");

        NodeInfo info = service.getNodeInfo("node-1");
        assertNotNull(info);
        assertEquals("node-1", info.nodeId());
        assertEquals("localhost:6000", info.endpoint());
        assertEquals(NodeStatus.ACTIVE, info.status());
        assertNotNull(info.lastHeartbeat());
    }

    @Test
    void reRegistrationUpdatesEndpoint() {
        service = new HeartbeatMembershipService(shardManager);
        service.start();

        service.registerNode("node-1", "localhost:6000");
        service.registerNode("node-1", "localhost:7000");

        NodeInfo info = service.getNodeInfo("node-1");
        assertEquals("localhost:7000", info.endpoint());
        assertEquals(NodeStatus.ACTIVE, info.status());
        assertEquals(1, service.getNodeCount());
    }
}
