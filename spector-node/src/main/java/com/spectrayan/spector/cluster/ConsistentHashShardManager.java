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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.SpectorInternalException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Consistent hash ring-based shard manager for distributed document assignment.
 *
 * <p>Uses virtual nodes (vnodes) to distribute shard ownership evenly across
 * the hash ring. Each physical shard is represented by multiple virtual nodes,
 * ensuring balanced load distribution and minimal data movement during rebalancing.</p>
 *
 * <h3>Guarantees</h3>
 * <ul>
 *   <li>Deterministic: same document ID always maps to same shard given same config</li>
 *   <li>Minimal migration: only documents hashing to new shard ranges are moved on topology change</li>
 *   <li>Assignment map reflects changes within 100ms of topology update</li>
 * </ul>
 */
public class ConsistentHashShardManager implements ShardManager {

    private static final Logger log = LoggerFactory.getLogger(ConsistentHashShardManager.class);

    /** Minimum allowed shard count. */
    public static final int MIN_SHARD_COUNT = 2;

    /** Maximum allowed shard count. */
    public static final int MAX_SHARD_COUNT = 256;

    /** Default number of virtual nodes per physical shard. */
    private static final int DEFAULT_VIRTUAL_NODES = 150;

    private final int shardCount;
    private final int virtualNodesPerShard;

    /** The consistent hash ring: hash position → shard index. */
    private final ConcurrentSkipListMap<Long, Integer> hashRing;

    /** Shard index → node endpoint. */
    private final ConcurrentHashMap<Integer, String> shardAssignments;

    /** Set of shards currently active in the ring. */
    private final Set<Integer> activeShards;

    /** Tracks shards that are unreachable during rebalancing. */
    private final Set<Integer> pausedShards;

    /** Read-write lock protecting ring modifications and assignment reads. */
    private final ReentrantReadWriteLock ringLock;

    /** Cached immutable snapshot of assignments for fast reads. */
    private final AtomicReference<Map<Integer, String>> assignmentSnapshot;

    /** Listener for rebalancing events — used to trigger document migration. */
    private volatile RebalanceListener rebalanceListener;

    /**
     * Creates a ConsistentHashShardManager with the specified shard count.
     *
     * @param shardCount the total number of shards (2–256)
     * @throws SpectorValidationException if shardCount is outside the valid range
     */
    public ConsistentHashShardManager(int shardCount) {
        this(shardCount, DEFAULT_VIRTUAL_NODES);
    }

    /**
     * Creates a ConsistentHashShardManager with specified shard count and virtual nodes.
     *
     * @param shardCount          the total number of shards (2–256)
     * @param virtualNodesPerShard number of virtual nodes per physical shard
     * @throws SpectorValidationException if shardCount is outside the valid range
     */
    public ConsistentHashShardManager(int shardCount, int virtualNodesPerShard) {
        if (shardCount < MIN_SHARD_COUNT || shardCount > MAX_SHARD_COUNT) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "shardCount", MIN_SHARD_COUNT, MAX_SHARD_COUNT, shardCount);
        }
        if (virtualNodesPerShard < 1) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "virtualNodesPerShard", 1, Integer.MAX_VALUE, 0);
        }

        this.shardCount = shardCount;
        this.virtualNodesPerShard = virtualNodesPerShard;
        this.hashRing = new ConcurrentSkipListMap<>();
        this.shardAssignments = new ConcurrentHashMap<>();
        this.activeShards = ConcurrentHashMap.newKeySet();
        this.pausedShards = ConcurrentHashMap.newKeySet();
        this.ringLock = new ReentrantReadWriteLock();
        this.assignmentSnapshot = new AtomicReference<>(Collections.emptyMap());

        log.info("ConsistentHashShardManager created: shardCount={}, virtualNodes={}",
                shardCount, virtualNodesPerShard);
    }

    @Override
    public int assignShard(String documentId) {
        if (documentId == null || documentId.isEmpty()) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "Document ID");
        }

        long hash = hash(documentId);

        ringLock.readLock().lock();
        try {
            if (hashRing.isEmpty()) {
                throw new SpectorInternalException(ErrorCode.EMPTY_COLLECTION, "shards");
            }

            // Find the first virtual node at or after the hash position
            Map.Entry<Long, Integer> entry = hashRing.ceilingEntry(hash);
            if (entry == null) {
                // Wrap around to the first entry in the ring
                entry = hashRing.firstEntry();
            }
            return entry.getValue();
        } finally {
            ringLock.readLock().unlock();
        }
    }

    @Override
    public void addShard(int shardIndex, String nodeEndpoint) {
        if (shardIndex < 0 || shardIndex >= shardCount) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "Shard index must be between 0 and " + (shardCount - 1) + ", got: " + shardIndex);
        }
        if (nodeEndpoint == null || nodeEndpoint.isBlank()) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "Node endpoint");
        }

        ringLock.writeLock().lock();
        try {
            // Add virtual nodes for this shard to the ring
            for (int i = 0; i < virtualNodesPerShard; i++) {
                long vnodeHash = hash("shard-" + shardIndex + "-vnode-" + i);
                hashRing.put(vnodeHash, shardIndex);
            }

            shardAssignments.put(shardIndex, nodeEndpoint);
            activeShards.add(shardIndex);
            pausedShards.remove(shardIndex);

            // Update the cached snapshot
            updateAssignmentSnapshot();

            log.info("Added shard {} at endpoint '{}' ({} virtual nodes)",
                    shardIndex, nodeEndpoint, virtualNodesPerShard);
        } finally {
            ringLock.writeLock().unlock();
        }
    }

    @Override
    public void rebalance() {
        ringLock.readLock().lock();
        try {
            if (activeShards.size() < 2) {
                log.info("Rebalance skipped: fewer than 2 active shards");
                return;
            }

            if (rebalanceListener == null) {
                log.debug("Rebalance: no listener registered, assignment map updated only");
                return;
            }

            // Determine which documents need migration based on current ring state.
            // The listener is responsible for iterating documents and checking
            // if their new assignment differs from their current location.
            log.info("Triggering rebalance across {} active shards (paused: {})",
                    activeShards.size(), pausedShards.size());

            rebalanceListener.onRebalance(this, Collections.unmodifiableSet(pausedShards));
        } finally {
            ringLock.readLock().unlock();
        }
    }

    @Override
    public Map<Integer, String> getShardAssignmentMap() {
        return assignmentSnapshot.get();
    }

    /**
     * Removes a shard from the hash ring.
     *
     * @param shardIndex the shard to remove
     */
    public void removeShard(int shardIndex) {
        ringLock.writeLock().lock();
        try {
            // Remove all virtual nodes for this shard
            hashRing.values().removeIf(idx -> idx == shardIndex);
            shardAssignments.remove(shardIndex);
            activeShards.remove(shardIndex);
            pausedShards.remove(shardIndex);
            updateAssignmentSnapshot();

            log.info("Removed shard {} from hash ring", shardIndex);
        } finally {
            ringLock.writeLock().unlock();
        }
    }

    /**
     * Marks a shard as unreachable, pausing migration to/from it.
     *
     * <p>Documents currently assigned to this shard remain in place.
     * Migration will resume when the shard becomes reachable again.</p>
     *
     * @param shardIndex the shard to pause
     */
    public void markShardUnreachable(int shardIndex) {
        pausedShards.add(shardIndex);
        log.warn("Shard {} marked as unreachable — migration paused", shardIndex);
    }

    /**
     * Marks a shard as reachable again, allowing migration to resume.
     *
     * @param shardIndex the shard to resume
     */
    public void markShardReachable(int shardIndex) {
        if (pausedShards.remove(shardIndex)) {
            log.info("Shard {} marked as reachable — migration resumed", shardIndex);
        }
    }

    /**
     * Returns whether a shard is currently paused (unreachable).
     *
     * @param shardIndex the shard to check
     * @return true if the shard is paused
     */
    public boolean isShardPaused(int shardIndex) {
        return pausedShards.contains(shardIndex);
    }

    /**
     * Returns the configured shard count.
     *
     * @return the total number of shards this manager supports
     */
    public int getShardCount() {
        return shardCount;
    }

    /**
     * Returns the number of active shards currently in the ring.
     *
     * @return active shard count
     */
    public int getActiveShardCount() {
        return activeShards.size();
    }

    /**
     * Sets the rebalance listener to be notified on rebalance events.
     *
     * @param listener the listener to set (may be null to clear)
     */
    public void setRebalanceListener(RebalanceListener listener) {
        this.rebalanceListener = listener;
    }

    /**
     * Determines which shard a document would be assigned to after a topology change,
     * useful for computing migration sets during rebalancing.
     *
     * @param documentId  the document to check
     * @param excludeShard shard index to exclude from ring (simulates pre-add state)
     * @return shard index the document would be assigned to without the excluded shard
     */
    public int assignShardExcluding(String documentId, int excludeShard) {
        if (documentId == null || documentId.isEmpty()) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "Document ID");
        }

        long hash = hash(documentId);

        ringLock.readLock().lock();
        try {
            // Walk the ring to find the first non-excluded shard
            Long position = hashRing.ceilingKey(hash);
            if (position == null) {
                position = hashRing.firstKey();
            }

            // Iterate until we find a shard that isn't excluded
            Long startPosition = position;
            boolean wrapped = false;
            while (true) {
                int shard = hashRing.get(position);
                if (shard != excludeShard) {
                    return shard;
                }

                Map.Entry<Long, Integer> next = hashRing.higherEntry(position);
                if (next == null) {
                    // Wrap around
                    next = hashRing.firstEntry();
                    wrapped = true;
                }
                position = next.getKey();

                if (wrapped && position.equals(startPosition)) {
                    // All nodes belong to excluded shard — shouldn't happen
                    throw new SpectorInternalException(ErrorCode.EMPTY_COLLECTION, "shards");
                }
            }
        } finally {
            ringLock.readLock().unlock();
        }
    }

    // ─────────────── Private helpers ───────────────

    /**
     * Updates the cached assignment snapshot atomically.
     * Must be called under write lock.
     */
    private void updateAssignmentSnapshot() {
        assignmentSnapshot.set(Collections.unmodifiableMap(new HashMap<>(shardAssignments)));
    }

    /**
     * Computes a consistent hash for the given key using MD5.
     * MD5 provides good distribution properties for hash ring placement.
     *
     * @param key the key to hash
     * @return a 64-bit hash value
     */
    static long hash(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(key.getBytes(StandardCharsets.UTF_8));
            // Use first 8 bytes as a long for ring position
            return ((long) (digest[0] & 0xFF) << 56)
                    | ((long) (digest[1] & 0xFF) << 48)
                    | ((long) (digest[2] & 0xFF) << 40)
                    | ((long) (digest[3] & 0xFF) << 32)
                    | ((long) (digest[4] & 0xFF) << 24)
                    | ((long) (digest[5] & 0xFF) << 16)
                    | ((long) (digest[6] & 0xFF) << 8)
                    | ((long) (digest[7] & 0xFF));
        } catch (NoSuchAlgorithmException e) {
            // MD5 is always available in standard JDK
            throw new AssertionError("MD5 algorithm not available", e);
        }
    }

    /**
     * Listener interface for rebalance events.
     */
    @FunctionalInterface
    public interface RebalanceListener {

        /**
         * Called when a rebalance is triggered.
         *
         * <p>The listener should iterate through documents currently stored,
         * call {@link ConsistentHashShardManager#assignShard(String)} for each,
         * and migrate documents whose new assignment differs from their current location.
         * Documents assigned to paused shards should be skipped.</p>
         *
         * @param shardManager the shard manager with updated ring state
         * @param pausedShards set of shard indices that are currently unreachable
         */
        void onRebalance(ConsistentHashShardManager shardManager, Set<Integer> pausedShards);
    }
}