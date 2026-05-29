package com.spectrayan.spector.cluster;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ReplicationManager}.
 */
class ReplicationManagerTest {

    private ReplicationManager replicationManager;

    @BeforeEach
    void setUp() {
        replicationManager = new ReplicationManager(3, null);
    }

    @AfterEach
    void tearDown() {
        replicationManager.close();
    }

    // --- Replica count configuration tests ---

    @Test
    void setReplicaCount_validRange_updates() {
        replicationManager.setReplicaCount(1);
        assertEquals(1, replicationManager.getReplicaCount());

        replicationManager.setReplicaCount(5);
        assertEquals(5, replicationManager.getReplicaCount());
    }

    @Test
    void setReplicaCount_belowMinimum_throws() {
        assertThrows(IllegalArgumentException.class, () -> replicationManager.setReplicaCount(0));
    }

    @Test
    void setReplicaCount_aboveMaximum_throws() {
        assertThrows(IllegalArgumentException.class, () -> replicationManager.setReplicaCount(6));
    }

    @Test
    void constructor_invalidReplicaCount_throws() {
        assertThrows(IllegalArgumentException.class, () -> new ReplicationManager(0, null));
        assertThrows(IllegalArgumentException.class, () -> new ReplicationManager(6, null));
    }

    // --- Shard registration tests ---

    @Test
    void registerShard_validParams_succeeds() {
        replicationManager.registerShard(0, "node1:9090");
        assertEquals("node1:9090", replicationManager.getPrimaryEndpoint(0));
    }

    @Test
    void registerShard_negativeIndex_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> replicationManager.registerShard(-1, "node1:9090"));
    }

    @Test
    void registerShard_nullEndpoint_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> replicationManager.registerShard(0, null));
    }

    @Test
    void registerShard_blankEndpoint_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> replicationManager.registerShard(0, "  "));
    }

    // --- Add replica tests ---

    @Test
    void addReplica_validParams_addsToGroup() {
        replicationManager.registerShard(0, "primary:9090");
        replicationManager.addReplica(0, "replica-1", "node2:9090");

        List<ReplicaInfo> replicas = replicationManager.getReplicas(0);
        assertEquals(1, replicas.size());
        assertEquals("replica-1", replicas.get(0).replicaId());
        assertEquals("node2:9090", replicas.get(0).endpoint());
        assertEquals(ReplicaState.SYNCING, replicas.get(0).state());
    }

    @Test
    void addReplica_exceedsReplicaCount_throws() {
        replicationManager.setReplicaCount(2);
        replicationManager.registerShard(0, "primary:9090");
        replicationManager.addReplica(0, "r1", "node2:9090");
        replicationManager.addReplica(0, "r2", "node3:9090");

        assertThrows(IllegalStateException.class,
                () -> replicationManager.addReplica(0, "r3", "node4:9090"));
    }

    @Test
    void addReplica_nullId_throws() {
        replicationManager.registerShard(0, "primary:9090");
        assertThrows(IllegalArgumentException.class,
                () -> replicationManager.addReplica(0, null, "node2:9090"));
    }

    // --- Promotion tests ---

    @Test
    void promoteReplica_activeReplicaAvailable_promotes() {
        replicationManager.registerShard(0, "primary:9090");
        replicationManager.addReplica(0, "r1", "node2:9090");

        // Synchronize the replica to make it ACTIVE
        replicationManager.synchronizeReplica(0, "node2:9090");
        assertTrue(replicationManager.isFullySynchronized(0, "node2:9090"));

        // Now promote
        replicationManager.promoteReplica(0);

        assertEquals("node2:9090", replicationManager.getPrimaryEndpoint(0));
        assertTrue(replicationManager.getReplicas(0).isEmpty());
    }

    @Test
    void promoteReplica_noReplicaAvailable_marksUnavailable() {
        replicationManager.registerShard(0, "primary:9090");
        // No replicas added

        replicationManager.promoteReplica(0);

        assertNull(replicationManager.getPrimaryEndpoint(0));
    }

    @Test
    void promoteReplica_unregisteredShard_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> replicationManager.promoteReplica(99));
    }

    // --- Synchronization tests ---

    @Test
    void synchronizeReplica_completesSuccessfully_marksActive() {
        replicationManager.registerShard(0, "primary:9090");
        replicationManager.addReplica(0, "r1", "node2:9090");

        assertFalse(replicationManager.isFullySynchronized(0, "node2:9090"));

        boolean result = replicationManager.synchronizeReplica(0, "node2:9090");

        assertTrue(result);
        assertTrue(replicationManager.isFullySynchronized(0, "node2:9090"));
    }

    @Test
    void synchronizeReplica_unknownEndpoint_returnsFalse() {
        replicationManager.registerShard(0, "primary:9090");

        boolean result = replicationManager.synchronizeReplica(0, "unknown:9090");
        assertFalse(result);
    }

    @Test
    void synchronizeReplica_unregisteredShard_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> replicationManager.synchronizeReplica(99, "node:9090"));
    }

    // --- Read blocking tests ---

    @Test
    void canServeReads_syncingReplica_returnsFalse() {
        replicationManager.registerShard(0, "primary:9090");
        replicationManager.addReplica(0, "r1", "node2:9090");

        // Replica is in SYNCING state after being added
        assertFalse(replicationManager.canServeReads(0, "node2:9090"));
    }

    @Test
    void canServeReads_activeReplica_returnsTrue() {
        replicationManager.registerShard(0, "primary:9090");
        replicationManager.addReplica(0, "r1", "node2:9090");
        replicationManager.synchronizeReplica(0, "node2:9090");

        assertTrue(replicationManager.canServeReads(0, "node2:9090"));
    }

    @Test
    void canServeReads_unavailableReplica_returnsFalse() {
        replicationManager.registerShard(0, "primary:9090");
        replicationManager.addReplica(0, "r1", "node2:9090");
        replicationManager.synchronizeReplica(0, "node2:9090");
        replicationManager.markReplicaUnavailable(0, "node2:9090");

        assertFalse(replicationManager.canServeReads(0, "node2:9090"));
    }

    // --- Write replication tests ---

    @Test
    void replicateWrite_appendsToWal() {
        replicationManager.registerShard(0, "primary:9090");

        WriteOperation op = new WriteOperation(
                1L, "doc-1", WriteOperation.OperationType.INSERT,
                new byte[]{1, 2, 3}, Instant.now());

        replicationManager.replicateWrite(0, op);

        List<WriteOperation> delta = replicationManager.getDeltaOperations(0, Instant.EPOCH);
        assertEquals(1, delta.size());
        assertEquals("doc-1", delta.get(0).documentId());
    }

    @Test
    void replicateWrite_nullOperation_throws() {
        replicationManager.registerShard(0, "primary:9090");
        assertThrows(IllegalArgumentException.class,
                () -> replicationManager.replicateWrite(0, null));
    }

    // --- Delta sync tests ---

    @Test
    void getDeltaOperations_returnsOnlyOperationsSinceTimestamp() {
        replicationManager.registerShard(0, "primary:9090");

        Instant t1 = Instant.now().minusSeconds(10);
        Instant t2 = Instant.now().minusSeconds(5);
        Instant t3 = Instant.now();

        WriteOperation op1 = new WriteOperation(1L, "doc-1",
                WriteOperation.OperationType.INSERT, null, t1);
        WriteOperation op2 = new WriteOperation(2L, "doc-2",
                WriteOperation.OperationType.INSERT, null, t2);
        WriteOperation op3 = new WriteOperation(3L, "doc-3",
                WriteOperation.OperationType.INSERT, null, t3);

        replicationManager.replicateWrite(0, op1);
        replicationManager.replicateWrite(0, op2);
        replicationManager.replicateWrite(0, op3);

        // Get ops since t1 (should include t2 and t3 but not t1)
        List<WriteOperation> delta = replicationManager.getDeltaOperations(0, t1);
        assertEquals(2, delta.size());
        assertEquals("doc-2", delta.get(0).documentId());
        assertEquals("doc-3", delta.get(1).documentId());
    }

    // --- Active replica endpoint tests ---

    @Test
    void getActiveReplicaEndpoints_returnsOnlyActive() {
        replicationManager.registerShard(0, "primary:9090");
        replicationManager.addReplica(0, "r1", "node2:9090");
        replicationManager.addReplica(0, "r2", "node3:9090");

        // Sync only one
        replicationManager.synchronizeReplica(0, "node2:9090");

        List<String> active = replicationManager.getActiveReplicaEndpoints(0);
        assertEquals(1, active.size());
        assertEquals("node2:9090", active.get(0));
    }

    // --- Mark unavailable tests ---

    @Test
    void markReplicaUnavailable_updatesState() {
        replicationManager.registerShard(0, "primary:9090");
        replicationManager.addReplica(0, "r1", "node2:9090");
        replicationManager.synchronizeReplica(0, "node2:9090");

        replicationManager.markReplicaUnavailable(0, "node2:9090");

        List<ReplicaInfo> replicas = replicationManager.getReplicas(0);
        assertEquals(ReplicaState.UNAVAILABLE, replicas.get(0).state());
    }

    // --- Membership service integration ---

    @Test
    void promoteReplica_noAvailable_reportsMembershipService() {
        // Create a simple mock membership service
        var reported = new java.util.concurrent.atomic.AtomicBoolean(false);
        MembershipService mockService = new MembershipService() {
            @Override public void start() {}
            @Override public void registerNode(String nodeId, String endpoint) {}
            @Override public void markUnavailable(String nodeId) {}
            @Override public java.util.Set<String> getActiveNodes() { return java.util.Set.of(); }
            @Override public ClusterTopology getTopology() { return null; }
            @Override public void reportUnavailableShard(int shardIndex, String reason) {
                reported.set(true);
            }
            @Override public void close() {}
        };

        ReplicationManager rm = new ReplicationManager(2, mockService);
        try {
            rm.registerShard(0, "primary:9090");
            rm.promoteReplica(0);
            assertTrue(reported.get());
        } finally {
            rm.close();
        }
    }

    // --- Close / lifecycle tests ---

    @Test
    void close_multipleInvocations_noError() {
        replicationManager.close();
        replicationManager.close(); // Should not throw
    }
}
