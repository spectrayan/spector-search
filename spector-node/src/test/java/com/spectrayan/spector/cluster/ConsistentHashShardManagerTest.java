package com.spectrayan.spector.cluster;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ConsistentHashShardManager}.
 */
class ConsistentHashShardManagerTest {

    private ConsistentHashShardManager manager;

    @BeforeEach
    void setUp() {
        manager = new ConsistentHashShardManager(4);
        // Register all 4 shards
        manager.addShard(0, "node0:8080");
        manager.addShard(1, "node1:8080");
        manager.addShard(2, "node2:8080");
        manager.addShard(3, "node3:8080");
    }

    // ─────── Shard count validation ───────

    @Test
    void shouldRejectShardCountBelowMinimum() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConsistentHashShardManager(1));
    }

    @Test
    void shouldRejectShardCountAboveMaximum() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConsistentHashShardManager(257));
    }

    @Test
    void shouldAcceptMinimumShardCount() {
        assertDoesNotThrow(() -> new ConsistentHashShardManager(2));
    }

    @Test
    void shouldAcceptMaximumShardCount() {
        assertDoesNotThrow(() -> new ConsistentHashShardManager(256));
    }

    // ─────── Deterministic assignment ───────

    @Test
    void shouldAssignSameDocumentToSameShardConsistently() {
        int shard1 = manager.assignShard("doc-123");
        int shard2 = manager.assignShard("doc-123");
        int shard3 = manager.assignShard("doc-123");

        assertEquals(shard1, shard2);
        assertEquals(shard2, shard3);
    }

    @Test
    void shouldDistributeDocumentsAcrossShards() {
        Set<Integer> assignedShards = new HashSet<>();
        // With enough documents, we should hit multiple shards
        for (int i = 0; i < 100; i++) {
            assignedShards.add(manager.assignShard("document-" + i));
        }
        // With 4 shards and 100 documents, we should hit at least 2 shards
        assertTrue(assignedShards.size() >= 2,
                "Expected documents to be distributed across multiple shards");
    }

    @Test
    void shouldReturnValidShardIndex() {
        for (int i = 0; i < 50; i++) {
            int shard = manager.assignShard("test-doc-" + i);
            assertTrue(shard >= 0 && shard < 4,
                    "Shard index " + shard + " out of range [0, 4)");
        }
    }

    // ─────── Input validation ───────

    @Test
    void shouldRejectNullDocumentId() {
        assertThrows(IllegalArgumentException.class,
                () -> manager.assignShard(null));
    }

    @Test
    void shouldRejectEmptyDocumentId() {
        assertThrows(IllegalArgumentException.class,
                () -> manager.assignShard(""));
    }

    @Test
    void shouldRejectInvalidShardIndex() {
        assertThrows(IllegalArgumentException.class,
                () -> manager.addShard(5, "node5:8080"));
    }

    @Test
    void shouldRejectNullEndpoint() {
        var mgr = new ConsistentHashShardManager(4);
        assertThrows(IllegalArgumentException.class,
                () -> mgr.addShard(0, null));
    }

    @Test
    void shouldRejectBlankEndpoint() {
        var mgr = new ConsistentHashShardManager(4);
        assertThrows(IllegalArgumentException.class,
                () -> mgr.addShard(0, "  "));
    }

    // ─────── Shard assignment map ───────

    @Test
    void shouldReturnCompleteAssignmentMap() {
        Map<Integer, String> map = manager.getShardAssignmentMap();
        assertEquals(4, map.size());
        assertEquals("node0:8080", map.get(0));
        assertEquals("node1:8080", map.get(1));
        assertEquals("node2:8080", map.get(2));
        assertEquals("node3:8080", map.get(3));
    }

    @Test
    void shouldReturnUnmodifiableAssignmentMap() {
        Map<Integer, String> map = manager.getShardAssignmentMap();
        assertThrows(UnsupportedOperationException.class,
                () -> map.put(5, "node5:8080"));
    }

    // ─────── Add/remove shard ───────

    @Test
    void shouldUpdateAssignmentMapOnAddShard() {
        var mgr = new ConsistentHashShardManager(8);
        mgr.addShard(0, "nodeA:8080");
        mgr.addShard(3, "nodeB:8080");

        Map<Integer, String> map = mgr.getShardAssignmentMap();
        assertEquals(2, map.size());
        assertEquals("nodeA:8080", map.get(0));
        assertEquals("nodeB:8080", map.get(3));
    }

    @Test
    void shouldRemoveShardFromRing() {
        manager.removeShard(2);

        Map<Integer, String> map = manager.getShardAssignmentMap();
        assertEquals(3, map.size());
        assertNull(map.get(2));

        // Documents previously on shard 2 should now go elsewhere
        for (int i = 0; i < 50; i++) {
            int shard = manager.assignShard("doc-" + i);
            assertNotEquals(2, shard, "No document should be assigned to removed shard");
        }
    }

    // ─────── Rebalancing minimality ───────

    @Test
    void shouldOnlyMigrateAffectedDocumentsOnAddShard() {
        // Start with 3 shards
        var mgr = new ConsistentHashShardManager(8, 50);
        mgr.addShard(0, "node0:8080");
        mgr.addShard(1, "node1:8080");
        mgr.addShard(2, "node2:8080");

        // Record initial assignments for 200 documents
        Map<String, Integer> beforeAssignments = new java.util.HashMap<>();
        for (int i = 0; i < 200; i++) {
            String docId = "doc-" + i;
            beforeAssignments.put(docId, mgr.assignShard(docId));
        }

        // Add a new shard
        mgr.addShard(3, "node3:8080");

        // Check that only documents now assigned to shard 3 have changed
        int migrated = 0;
        int unchanged = 0;
        for (int i = 0; i < 200; i++) {
            String docId = "doc-" + i;
            int newShard = mgr.assignShard(docId);
            int oldShard = beforeAssignments.get(docId);

            if (newShard != oldShard) {
                // Migrated documents should now be on the new shard
                assertEquals(3, newShard,
                        "Migrated document should move to newly added shard, not another existing one");
                migrated++;
            } else {
                unchanged++;
            }
        }

        // Some documents should have migrated, but not all
        assertTrue(migrated > 0, "Adding a shard should cause some migration");
        assertTrue(unchanged > 0, "Not all documents should migrate");
        assertTrue(migrated < 200, "Only affected documents should migrate");
    }

    // ─────── Unreachable shard handling ───────

    @Test
    void shouldPauseRebalancingForUnreachableShard() {
        manager.markShardUnreachable(2);
        assertTrue(manager.isShardPaused(2));
    }

    @Test
    void shouldResumeAfterShardBecomesReachable() {
        manager.markShardUnreachable(1);
        assertTrue(manager.isShardPaused(1));

        manager.markShardReachable(1);
        assertFalse(manager.isShardPaused(1));
    }

    @Test
    void shouldNotifyListenerWithPausedShardsOnRebalance() {
        AtomicBoolean called = new AtomicBoolean(false);
        Set<Integer> capturedPaused = new HashSet<>();

        manager.setRebalanceListener((mgr, paused) -> {
            called.set(true);
            capturedPaused.addAll(paused);
        });

        manager.markShardUnreachable(2);
        manager.rebalance();

        assertTrue(called.get(), "Rebalance listener should have been called");
        assertTrue(capturedPaused.contains(2), "Paused shards should include shard 2");
    }

    // ─────── Edge cases ───────

    @Test
    void shouldThrowWhenNoShardsRegistered() {
        var mgr = new ConsistentHashShardManager(4);
        assertThrows(IllegalStateException.class,
                () -> mgr.assignShard("doc-1"));
    }

    @Test
    void shouldWorkWithSingleRegisteredShard() {
        var mgr = new ConsistentHashShardManager(4);
        mgr.addShard(0, "node0:8080");

        // All documents should go to shard 0
        for (int i = 0; i < 20; i++) {
            assertEquals(0, mgr.assignShard("doc-" + i));
        }
    }

    @Test
    void shouldReturnCorrectShardCount() {
        assertEquals(4, manager.getShardCount());
    }

    @Test
    void shouldReturnCorrectActiveShardCount() {
        assertEquals(4, manager.getActiveShardCount());
        manager.removeShard(1);
        assertEquals(3, manager.getActiveShardCount());
    }
}
