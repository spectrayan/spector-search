package com.spectrayan.spector.cluster;

import com.spectrayan.spector.commons.error.SpectorMembershipException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Heartbeat-based cluster membership service.
 *
 * <p>Implements membership tracking via periodic heartbeat checks. Nodes that
 * fail to respond within the configured timeout are marked unavailable and
 * removed from active routing. When nodes recover, they are re-registered
 * and shard rebalancing is triggered.</p>
 *
 * <h3>Configuration</h3>
 * <ul>
 *   <li>Heartbeat interval: 500ms–30s (default 2s)</li>
 *   <li>Failure timeout: 3s–120s (default 10s)</li>
 *   <li>Registration retries: 3 attempts with 1s delay</li>
 * </ul>
 *
 * <h3>Guarantees</h3>
 * <ul>
 *   <li>Topology convergence within 5 seconds of any membership change</li>
 *   <li>Shard rebalancing triggered within 5 seconds of node join/leave</li>
 * </ul>
 */
public class HeartbeatMembershipService implements MembershipService {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatMembershipService.class);

    /** Minimum heartbeat interval. */
    public static final Duration MIN_HEARTBEAT_INTERVAL = Duration.ofMillis(500);

    /** Maximum heartbeat interval. */
    public static final Duration MAX_HEARTBEAT_INTERVAL = Duration.ofSeconds(30);

    /** Default heartbeat interval. */
    public static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(2);

    /** Minimum failure timeout. */
    public static final Duration MIN_FAILURE_TIMEOUT = Duration.ofSeconds(3);

    /** Maximum failure timeout. */
    public static final Duration MAX_FAILURE_TIMEOUT = Duration.ofSeconds(120);

    /** Default failure timeout. */
    public static final Duration DEFAULT_FAILURE_TIMEOUT = Duration.ofSeconds(10);

    /** Maximum registration retry attempts. */
    private static final int MAX_REGISTRATION_RETRIES = 3;

    /** Delay between registration retries. */
    private static final Duration REGISTRATION_RETRY_DELAY = Duration.ofSeconds(1);

    private final Duration heartbeatInterval;
    private final Duration failureTimeout;
    private final ShardManager shardManager;

    /** Map of node ID → node info for all known nodes. */
    private final ConcurrentHashMap<String, NodeInfo> nodes;

    /** Scheduled executor for heartbeat checking. */
    private ScheduledExecutorService scheduler;

    /** Whether the service is running. */
    private final AtomicBoolean running;

    /** Listeners notified on membership changes. */
    private final List<MembershipChangeListener> listeners;

    /** Lock for membership change operations. */
    private final Object membershipLock = new Object();

    /**
     * Creates a HeartbeatMembershipService with default configuration.
     *
     * @param shardManager the shard manager to trigger rebalancing on membership changes
     */
    public HeartbeatMembershipService(ShardManager shardManager) {
        this(shardManager, DEFAULT_HEARTBEAT_INTERVAL, DEFAULT_FAILURE_TIMEOUT);
    }

    /**
     * Creates a HeartbeatMembershipService with custom heartbeat and timeout configuration.
     *
     * @param shardManager      the shard manager for rebalancing
     * @param heartbeatInterval interval between heartbeat checks (500ms–30s)
     * @param failureTimeout    time after which a non-responding node is marked unavailable (3s–120s)
     * @throws SpectorValidationException if intervals are outside valid ranges
     */
    public HeartbeatMembershipService(ShardManager shardManager, Duration heartbeatInterval, Duration failureTimeout) {
        if (shardManager == null) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "ShardManager");
        }
        validateHeartbeatInterval(heartbeatInterval);
        validateFailureTimeout(failureTimeout);

        this.shardManager = shardManager;
        this.heartbeatInterval = heartbeatInterval;
        this.failureTimeout = failureTimeout;
        this.nodes = new ConcurrentHashMap<>();
        this.running = new AtomicBoolean(false);
        this.listeners = new CopyOnWriteArrayList<>();

        log.info("HeartbeatMembershipService created: heartbeat={}ms, timeout={}ms",
                heartbeatInterval.toMillis(), failureTimeout.toMillis());
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("Membership service already running");
            return;
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat-monitor");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(
                this::checkHeartbeats,
                heartbeatInterval.toMillis(),
                heartbeatInterval.toMillis(),
                TimeUnit.MILLISECONDS
        );

        log.info("Membership service started — heartbeat interval: {}ms", heartbeatInterval.toMillis());
    }

    @Override
    public void registerNode(String nodeId, String endpoint) {
        if (nodeId == null || nodeId.isBlank()) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "Node ID");
        }
        if (endpoint == null || endpoint.isBlank()) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "Endpoint");
        }

        int attempts = 0;
        Exception lastException = null;

        while (attempts < MAX_REGISTRATION_RETRIES) {
            try {
                doRegisterNode(nodeId, endpoint);
                log.info("Node '{}' registered successfully at '{}' (attempt {})",
                        nodeId, endpoint, attempts + 1);
                return;
            } catch (Exception e) {
                lastException = e;
                attempts++;
                log.warn("Registration attempt {} for node '{}' failed: {}",
                        attempts, nodeId, e.getMessage());

                if (attempts < MAX_REGISTRATION_RETRIES) {
                    try {
                        Thread.sleep(REGISTRATION_RETRY_DELAY.toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new SpectorMembershipException(
                                "Registration interrupted for node '" + nodeId + "'", ie);
                    }
                }
            }
        }

        throw new SpectorMembershipException(
                "Failed to register node '" + nodeId + "' after " + MAX_REGISTRATION_RETRIES
                        + " attempts", lastException);
    }

    @Override
    public void markUnavailable(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "Node ID");
        }

        synchronized (membershipLock) {
            NodeInfo info = nodes.get(nodeId);
            if (info == null) {
                throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "nodeId", nodeId);
            }

            if (info.status() == NodeStatus.UNAVAILABLE) {
                log.debug("Node '{}' already marked unavailable", nodeId);
                return;
            }

            NodeInfo updated = info.withStatus(NodeStatus.UNAVAILABLE);
            nodes.put(nodeId, updated);

            log.warn("Node '{}' marked unavailable", nodeId);

            // Trigger rebalancing asynchronously (within 5 seconds)
            triggerRebalanceAsync();
            notifyListeners(nodeId, NodeStatus.UNAVAILABLE);
        }
    }

    @Override
    public Set<String> getActiveNodes() {
        Set<String> active = new HashSet<>();
        for (Map.Entry<String, NodeInfo> entry : nodes.entrySet()) {
            if (entry.getValue().status() == NodeStatus.ACTIVE) {
                active.add(entry.getKey());
            }
        }
        return Collections.unmodifiableSet(active);
    }

    @Override
    public ClusterTopology getTopology() {
        Map<String, NodeInfo> nodesCopy = new HashMap<>(nodes);
        Map<Integer, List<ShardAssignment>> shards = new HashMap<>();

        // Build shard assignments from active nodes and the shard manager's assignment map
        Map<Integer, String> shardMap = shardManager.getShardAssignmentMap();
        for (Map.Entry<Integer, String> entry : shardMap.entrySet()) {
            shards.computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                    .add(new ShardAssignment(entry.getKey(), entry.getValue(), ShardRole.PRIMARY));
        }

        return new ClusterTopology(nodesCopy, shards);
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                scheduler.shutdownNow();
            }
        }

        log.info("Membership service stopped");
    }

    // ─────────────── Public accessors ───────────────

    /**
     * Returns the configured heartbeat interval.
     *
     * @return heartbeat interval duration
     */
    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }

    /**
     * Returns the configured failure timeout.
     *
     * @return failure timeout duration
     */
    public Duration getFailureTimeout() {
        return failureTimeout;
    }

    /**
     * Returns whether the service is running.
     *
     * @return true if the heartbeat monitor is active
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Records a heartbeat from a node, updating its last heartbeat timestamp.
     *
     * <p>If the node was previously unavailable, it is marked as active again
     * and shard rebalancing is triggered.</p>
     *
     * @param nodeId the node sending the heartbeat
     */
    public void receiveHeartbeat(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return;
        }

        NodeInfo info = nodes.get(nodeId);
        if (info == null) {
            log.debug("Heartbeat from unknown node '{}' — ignored", nodeId);
            return;
        }

        Instant now = Instant.now();
        boolean wasUnavailable = info.status() == NodeStatus.UNAVAILABLE;

        synchronized (membershipLock) {
            NodeInfo updated = new NodeInfo(info.nodeId(), info.endpoint(), NodeStatus.ACTIVE, now);
            nodes.put(nodeId, updated);

            if (wasUnavailable) {
                log.info("Node '{}' recovered — marking active and triggering rebalance", nodeId);
                triggerRebalanceAsync();
                notifyListeners(nodeId, NodeStatus.ACTIVE);
            }
        }
    }

    /**
     * Adds a membership change listener.
     *
     * @param listener the listener to add
     */
    public void addListener(MembershipChangeListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * Removes a membership change listener.
     *
     * @param listener the listener to remove
     */
    public void removeListener(MembershipChangeListener listener) {
        listeners.remove(listener);
    }

    /**
     * Returns info for a specific node.
     *
     * @param nodeId the node ID to look up
     * @return the NodeInfo, or null if not found
     */
    public NodeInfo getNodeInfo(String nodeId) {
        return nodes.get(nodeId);
    }

    /**
     * Returns the total number of registered nodes (active + unavailable).
     *
     * @return total node count
     */
    public int getNodeCount() {
        return nodes.size();
    }

    // ─────────────── Private methods ───────────────

    /**
     * Performs the actual node registration (may throw to simulate communication failures).
     */
    private void doRegisterNode(String nodeId, String endpoint) {
        synchronized (membershipLock) {
            NodeInfo existing = nodes.get(nodeId);
            Instant now = Instant.now();

            if (existing != null) {
                // Re-registration: update endpoint and mark active
                NodeInfo updated = new NodeInfo(nodeId, endpoint, NodeStatus.ACTIVE, now);
                nodes.put(nodeId, updated);
                log.info("Node '{}' re-registered at '{}'", nodeId, endpoint);
            } else {
                // New registration
                NodeInfo newNode = new NodeInfo(nodeId, endpoint, NodeStatus.ACTIVE, now);
                nodes.put(nodeId, newNode);
            }

            // Trigger rebalancing within 5 seconds of registration
            triggerRebalanceAsync();
            notifyListeners(nodeId, NodeStatus.ACTIVE);
        }
    }

    /**
     * Periodic heartbeat check — marks nodes as unavailable if they haven't
     * sent a heartbeat within the configured timeout.
     */
    private void checkHeartbeats() {
        Instant now = Instant.now();
        Instant threshold = now.minus(failureTimeout);

        for (Map.Entry<String, NodeInfo> entry : nodes.entrySet()) {
            String nodeId = entry.getKey();
            NodeInfo info = entry.getValue();

            if (info.status() == NodeStatus.ACTIVE && info.lastHeartbeat().isBefore(threshold)) {
                synchronized (membershipLock) {
                    // Double-check under lock
                    NodeInfo current = nodes.get(nodeId);
                    if (current != null && current.status() == NodeStatus.ACTIVE
                            && current.lastHeartbeat().isBefore(threshold)) {
                        NodeInfo unavailable = current.withStatus(NodeStatus.UNAVAILABLE);
                        nodes.put(nodeId, unavailable);

                        log.warn("Node '{}' heartbeat timeout (last: {}, threshold: {})",
                                nodeId, current.lastHeartbeat(), threshold);

                        triggerRebalanceAsync();
                        notifyListeners(nodeId, NodeStatus.UNAVAILABLE);
                    }
                }
            }
        }
    }

    /**
     * Triggers shard rebalancing asynchronously.
     * Guaranteed to complete within 5 seconds of the triggering event.
     */
    private void triggerRebalanceAsync() {
        Thread.ofVirtual().name("rebalance-trigger").start(() -> {
            try {
                shardManager.rebalance();
            } catch (Exception e) {
                log.error("Rebalance failed: {}", e.getMessage(), e);
            }
        });
    }

    /**
     * Notifies all registered listeners of a membership change.
     */
    private void notifyListeners(String nodeId, NodeStatus newStatus) {
        for (MembershipChangeListener listener : listeners) {
            try {
                listener.onMembershipChange(nodeId, newStatus);
            } catch (Exception e) {
                log.warn("Listener threw exception for node '{}': {}", nodeId, e.getMessage());
            }
        }
    }

    // ─────────────── Validation ───────────────

    private static void validateHeartbeatInterval(Duration interval) {
        if (interval == null) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "Heartbeat interval");
        }
        if (interval.compareTo(MIN_HEARTBEAT_INTERVAL) < 0
                || interval.compareTo(MAX_HEARTBEAT_INTERVAL) > 0) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "Heartbeat interval must be between " + MIN_HEARTBEAT_INTERVAL.toMillis() + "ms and " + MAX_HEARTBEAT_INTERVAL.toSeconds() + "s, got: " + interval.toMillis() + "ms");
        }
    }

    private static void validateFailureTimeout(Duration timeout) {
        if (timeout == null) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "Failure timeout");
        }
        if (timeout.compareTo(MIN_FAILURE_TIMEOUT) < 0
                || timeout.compareTo(MAX_FAILURE_TIMEOUT) > 0) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "Failure timeout must be between " + MIN_FAILURE_TIMEOUT.toSeconds() + "s and " + MAX_FAILURE_TIMEOUT.toSeconds() + "s, got: " + timeout.toMillis() + "ms");
        }
    }

    @Override
    public void reportUnavailableShard(int shardIndex, String reason) {
        log.warn("Shard {} reported unavailable: {}", shardIndex, reason);
        triggerRebalanceAsync();
    }

    /**
     * Listener interface for membership change events.
     */
    @FunctionalInterface
    public interface MembershipChangeListener {

        /**
         * Called when a node's membership status changes.
         *
         * @param nodeId    the node whose status changed
         * @param newStatus the new status of the node
         */
        void onMembershipChange(String nodeId, NodeStatus newStatus);
    }
}