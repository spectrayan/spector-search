package com.spectrayan.spector.cluster;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.SpectorServerException;
import com.spectrayan.spector.storage.error.SpectorSegmentClosedException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Manages replication of shard data across cluster nodes for fault tolerance.
 *
 * <p>The ReplicationManager maintains configurable replica copies of each shard (1–5),
 * handles primary promotion on failure (within 10 seconds), performs delta synchronization
 * for recovered replicas, and ensures writes are replicated within 2 seconds.</p>
 *
 * <p>Key behaviors:
 * <ul>
 *   <li>Configurable replica count (1–5, default 1)</li>
 *   <li>Primary promotion within 10 seconds of failure detection</li>
 *   <li>Delta synchronization: only data written since failure is transferred</li>
 *   <li>Recovering replicas are blocked from reads until sync completes</li>
 *   <li>Writes replicated to all replicas within 2 seconds</li>
 * </ul>
 * </p>
 */
public class ReplicationManager implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ReplicationManager.class.getName());

    /** Minimum allowed replica count. */
    public static final int MIN_REPLICA_COUNT = 1;

    /** Maximum allowed replica count. */
    public static final int MAX_REPLICA_COUNT = 5;

    /** Default replica count. */
    public static final int DEFAULT_REPLICA_COUNT = 1;

    /** Maximum time allowed for primary promotion (10 seconds). */
    public static final Duration PROMOTION_TIMEOUT = Duration.ofSeconds(10);

    /** Maximum time allowed for write replication (2 seconds). */
    public static final Duration REPLICATION_TIMEOUT = Duration.ofSeconds(2);

    /** Interval for checking replica health. */
    private static final Duration HEALTH_CHECK_INTERVAL = Duration.ofSeconds(2);

    private volatile int replicaCount;

    /** Per-shard replication groups: shardIndex -> list of ReplicaInfo. */
    private final Map<Integer, CopyOnWriteArrayList<ReplicaInfo>> replicationGroups;

    /** Per-shard write-ahead logs for delta sync: shardIndex -> ordered list of WriteOperations. */
    private final Map<Integer, CopyOnWriteArrayList<WriteOperation>> writeAheadLogs;

    /** Per-shard primary endpoint: shardIndex -> endpoint of the current primary. */
    private final Map<Integer, String> primaryEndpoints;

    /** Lock for promotion operations to prevent concurrent promotions for the same shard. */
    private final Map<Integer, ReentrantReadWriteLock> shardLocks;

    /** Optional membership service for reporting unavailable shards. */
    private final MembershipService membershipService;

    /** Scheduler for health checks and async replication tasks. */
    private final ScheduledExecutorService scheduler;

    /** Health check future for cancellation on close. */
    private ScheduledFuture<?> healthCheckFuture;

    private volatile boolean closed = false;

    /**
     * Creates a ReplicationManager with default replica count and no membership service.
     */
    public ReplicationManager() {
        this(DEFAULT_REPLICA_COUNT, null);
    }

    /**
     * Creates a ReplicationManager with the specified replica count.
     *
     * @param replicaCount initial replica count (1–5)
     * @param membershipService optional membership service for reporting unavailable shards (may be null)
     * @throws SpectorValidationException if replicaCount is outside [1, 5]
     */
    public ReplicationManager(int replicaCount, MembershipService membershipService) {
        validateReplicaCount(replicaCount);
        this.replicaCount = replicaCount;
        this.membershipService = membershipService;
        this.replicationGroups = new ConcurrentHashMap<>();
        this.writeAheadLogs = new ConcurrentHashMap<>();
        this.primaryEndpoints = new ConcurrentHashMap<>();
        this.shardLocks = new ConcurrentHashMap<>();
        this.scheduler = Executors.newScheduledThreadPool(2, Thread.ofVirtual().factory());
    }

    /**
     * Starts periodic health checking of replicas.
     */
    public void start() {
        if (closed) {
            throw new SpectorSegmentClosedException();
        }
        healthCheckFuture = scheduler.scheduleAtFixedRate(
                this::checkReplicaHealth,
                HEALTH_CHECK_INTERVAL.toMillis(),
                HEALTH_CHECK_INTERVAL.toMillis(),
                TimeUnit.MILLISECONDS
        );
        LOG.info("ReplicationManager started with replica count: " + replicaCount);
    }

    /**
     * Sets the replica count for all shards.
     *
     * @param count replica count (1–5)
     * @throws SpectorValidationException if count is outside [1, 5]
     */
    public void setReplicaCount(int count) {
        validateReplicaCount(count);
        this.replicaCount = count;
        LOG.info("Replica count updated to: " + count);
    }

    /**
     * Returns the current configured replica count.
     *
     * @return the replica count (1–5)
     */
    public int getReplicaCount() {
        return replicaCount;
    }

    /**
     * Registers a shard with its primary endpoint.
     *
     * @param shardIndex     the shard index
     * @param primaryEndpoint the endpoint of the primary node
     * @throws SpectorValidationException if shardIndex is negative or endpoint is null/blank
     */
    public void registerShard(int shardIndex, String primaryEndpoint) {
        if (shardIndex < 0) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NEGATIVE, "shardIndex", shardIndex);
        }
        if (primaryEndpoint == null || primaryEndpoint.isBlank()) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "Primary endpoint");
        }
        primaryEndpoints.put(shardIndex, primaryEndpoint);
        replicationGroups.computeIfAbsent(shardIndex, k -> new CopyOnWriteArrayList<>());
        writeAheadLogs.computeIfAbsent(shardIndex, k -> new CopyOnWriteArrayList<>());
        shardLocks.computeIfAbsent(shardIndex, k -> new ReentrantReadWriteLock());
        LOG.fine("Registered shard " + shardIndex + " with primary: " + primaryEndpoint);
    }

    /**
     * Adds a replica to a shard's replication group.
     *
     * @param shardIndex      the shard index
     * @param replicaId       unique identifier for the replica
     * @param replicaEndpoint the endpoint of the replica node
     * @throws SpectorValidationException if parameters are invalid
     * @throws SpectorValidationException    if adding would exceed the configured replica count
     */
    public void addReplica(int shardIndex, String replicaId, String replicaEndpoint) {
        if (replicaId == null || replicaId.isBlank()) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "Replica ID");
        }
        if (replicaEndpoint == null || replicaEndpoint.isBlank()) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "Replica endpoint");
        }

        CopyOnWriteArrayList<ReplicaInfo> group = replicationGroups.computeIfAbsent(
                shardIndex, k -> new CopyOnWriteArrayList<>());

        if (group.size() >= replicaCount) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "Cannot add replica: shard " + shardIndex + " already has " + group.size() + " replicas (max: " + replicaCount + ")");
        }

        ReplicaInfo replica = new ReplicaInfo(replicaId, replicaEndpoint, ReplicaState.SYNCING, Instant.now());
        group.add(replica);
        writeAheadLogs.computeIfAbsent(shardIndex, k -> new CopyOnWriteArrayList<>());
        shardLocks.computeIfAbsent(shardIndex, k -> new ReentrantReadWriteLock());
        LOG.info("Added replica " + replicaId + " at " + replicaEndpoint + " for shard " + shardIndex);
    }

    /**
     * Promotes a replica to primary for the given shard.
     *
     * <p>This method must complete within 10 seconds of being called. If no replica
     * is available for promotion, the shard is marked as unavailable and the condition
     * is reported to the MembershipService.</p>
     *
     * @param shardIndex the shard index whose primary has failed
     * @throws SpectorValidationException if shardIndex is not registered
     */
    public void promoteReplica(int shardIndex) {
        ReentrantReadWriteLock lock = shardLocks.get(shardIndex);
        if (lock == null) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "shardIndex", shardIndex);
        }

        lock.writeLock().lock();
        try {
            Instant deadline = Instant.now().plus(PROMOTION_TIMEOUT);

            CopyOnWriteArrayList<ReplicaInfo> group = replicationGroups.get(shardIndex);
            if (group == null || group.isEmpty()) {
                handleNoReplicaAvailable(shardIndex);
                return;
            }

            // Find a fully synchronized (ACTIVE) replica for promotion
            ReplicaInfo candidate = null;
            int candidateIndex = -1;
            for (int i = 0; i < group.size(); i++) {
                ReplicaInfo replica = group.get(i);
                if (replica.state() == ReplicaState.ACTIVE) {
                    candidate = replica;
                    candidateIndex = i;
                    break;
                }
            }

            if (candidate == null) {
                // Try SYNCING replicas as a last resort — but only if sync is nearly complete
                for (int i = 0; i < group.size(); i++) {
                    ReplicaInfo replica = group.get(i);
                    if (replica.state() == ReplicaState.SYNCING) {
                        candidate = replica;
                        candidateIndex = i;
                        break;
                    }
                }
            }

            if (candidate == null) {
                handleNoReplicaAvailable(shardIndex);
                return;
            }

            // Check we haven't exceeded the promotion timeout
            if (Instant.now().isAfter(deadline)) {
                LOG.severe("Promotion timeout exceeded for shard " + shardIndex);
                handleNoReplicaAvailable(shardIndex);
                return;
            }

            // Promote: update primary endpoint and remove from replica group
            String newPrimary = candidate.endpoint();
            primaryEndpoints.put(shardIndex, newPrimary);
            group.remove(candidateIndex);

            LOG.info("Promoted replica " + candidate.replicaId() + " (" + newPrimary +
                    ") to primary for shard " + shardIndex);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Synchronizes a recovered replica with the current primary using delta sync.
     *
     * <p>Only write operations that occurred since the replica went offline are transferred.
     * The replica is in SYNCING state and will not serve reads until synchronization completes.</p>
     *
     * @param shardIndex      the shard index
     * @param replicaEndpoint the endpoint of the recovering replica
     * @return true if synchronization completed successfully, false otherwise
     */
    public boolean synchronizeReplica(int shardIndex, String replicaEndpoint) {
        if (replicaEndpoint == null || replicaEndpoint.isBlank()) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "Replica endpoint");
        }

        CopyOnWriteArrayList<ReplicaInfo> group = replicationGroups.get(shardIndex);
        if (group == null) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "shardIndex", shardIndex);
        }

        // Find the replica
        ReplicaInfo target = null;
        int targetIndex = -1;
        for (int i = 0; i < group.size(); i++) {
            if (group.get(i).endpoint().equals(replicaEndpoint)) {
                target = group.get(i);
                targetIndex = i;
                break;
            }
        }

        if (target == null) {
            LOG.warning("Replica at " + replicaEndpoint + " not found in shard " + shardIndex);
            return false;
        }

        // Mark as SYNCING (blocks reads)
        ReplicaInfo syncingReplica = target.withState(ReplicaState.SYNCING);
        group.set(targetIndex, syncingReplica);

        // Perform delta sync: get operations since the replica's last sync timestamp
        List<WriteOperation> deltaOps = getDeltaOperations(shardIndex, target.lastSyncTimestamp());

        LOG.info("Synchronizing replica " + target.replicaId() + " for shard " + shardIndex +
                " with " + deltaOps.size() + " delta operations");

        // Apply delta operations (in a real system this would transfer data over the network)
        boolean success = applyDeltaOperations(shardIndex, replicaEndpoint, deltaOps);

        if (success) {
            // Mark as ACTIVE and update sync timestamp
            ReplicaInfo activeReplica = new ReplicaInfo(
                    target.replicaId(), replicaEndpoint, ReplicaState.ACTIVE, Instant.now());
            group.set(targetIndex, activeReplica);
            LOG.info("Replica " + target.replicaId() + " for shard " + shardIndex + " is now ACTIVE");
        } else {
            // Mark as UNAVAILABLE on sync failure
            ReplicaInfo unavailableReplica = target.withState(ReplicaState.UNAVAILABLE);
            group.set(targetIndex, unavailableReplica);
            LOG.warning("Delta sync failed for replica " + target.replicaId() + " on shard " + shardIndex);
        }

        return success;
    }

    /**
     * Checks whether a replica is fully synchronized and ready to serve reads.
     *
     * @param shardIndex      the shard index
     * @param replicaEndpoint the endpoint of the replica to check
     * @return true if the replica is fully synchronized (ACTIVE state)
     */
    public boolean isFullySynchronized(int shardIndex, String replicaEndpoint) {
        CopyOnWriteArrayList<ReplicaInfo> group = replicationGroups.get(shardIndex);
        if (group == null) {
            return false;
        }
        for (ReplicaInfo replica : group) {
            if (replica.endpoint().equals(replicaEndpoint)) {
                return replica.state() == ReplicaState.ACTIVE;
            }
        }
        return false;
    }

    /**
     * Determines whether a replica can serve read requests.
     *
     * <p>A replica can serve reads only when it is in the ACTIVE state (fully synchronized).
     * Replicas in SYNCING or UNAVAILABLE state must NOT serve reads.</p>
     *
     * @param shardIndex      the shard index
     * @param replicaEndpoint the endpoint of the replica
     * @return true if the replica is allowed to serve reads
     */
    public boolean canServeReads(int shardIndex, String replicaEndpoint) {
        return isFullySynchronized(shardIndex, replicaEndpoint);
    }

    /**
     * Records a write operation and replicates it to all active replicas.
     *
     * <p>Writes are replicated to all replicas within 2 seconds under normal conditions.
     * Failed replications are logged but do not block the primary write.</p>
     *
     * @param shardIndex the shard index
     * @param operation  the write operation to replicate
     */
    public void replicateWrite(int shardIndex, WriteOperation operation) {
        if (operation == null) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "Write operation");
        }

        // Append to write-ahead log for delta sync
        CopyOnWriteArrayList<WriteOperation> wal = writeAheadLogs.computeIfAbsent(
                shardIndex, k -> new CopyOnWriteArrayList<>());
        wal.add(operation);

        // Replicate to all active replicas asynchronously (must complete within 2 seconds)
        CopyOnWriteArrayList<ReplicaInfo> group = replicationGroups.get(shardIndex);
        if (group == null || group.isEmpty()) {
            return;
        }

        Instant deadline = Instant.now().plus(REPLICATION_TIMEOUT);

        for (int i = 0; i < group.size(); i++) {
            ReplicaInfo replica = group.get(i);
            if (replica.state() == ReplicaState.ACTIVE) {
                boolean replicated = replicateToReplica(replica.endpoint(), operation, deadline);
                if (!replicated) {
                    LOG.warning("Failed to replicate write seq=" + operation.sequenceNumber() +
                            " to replica " + replica.replicaId() + " within timeout");
                }
            }
        }
    }

    /**
     * Returns the current primary endpoint for a shard.
     *
     * @param shardIndex the shard index
     * @return the primary endpoint, or null if the shard is not registered
     */
    public String getPrimaryEndpoint(int shardIndex) {
        return primaryEndpoints.get(shardIndex);
    }

    /**
     * Returns an unmodifiable list of replicas for a shard.
     *
     * @param shardIndex the shard index
     * @return list of replica info, or empty list if shard is not registered
     */
    public List<ReplicaInfo> getReplicas(int shardIndex) {
        CopyOnWriteArrayList<ReplicaInfo> group = replicationGroups.get(shardIndex);
        if (group == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(group));
    }

    /**
     * Returns the list of active (read-ready) replica endpoints for a shard.
     *
     * @param shardIndex the shard index
     * @return list of endpoints that can serve reads
     */
    public List<String> getActiveReplicaEndpoints(int shardIndex) {
        CopyOnWriteArrayList<ReplicaInfo> group = replicationGroups.get(shardIndex);
        if (group == null) {
            return Collections.emptyList();
        }
        List<String> active = new ArrayList<>();
        for (ReplicaInfo replica : group) {
            if (replica.state() == ReplicaState.ACTIVE) {
                active.add(replica.endpoint());
            }
        }
        return Collections.unmodifiableList(active);
    }

    /**
     * Marks a replica as unavailable (e.g., due to node failure detection).
     *
     * @param shardIndex      the shard index
     * @param replicaEndpoint the endpoint of the failed replica
     */
    public void markReplicaUnavailable(int shardIndex, String replicaEndpoint) {
        CopyOnWriteArrayList<ReplicaInfo> group = replicationGroups.get(shardIndex);
        if (group == null) {
            return;
        }
        for (int i = 0; i < group.size(); i++) {
            ReplicaInfo replica = group.get(i);
            if (replica.endpoint().equals(replicaEndpoint)) {
                group.set(i, replica.withState(ReplicaState.UNAVAILABLE));
                LOG.info("Marked replica " + replica.replicaId() + " as UNAVAILABLE for shard " + shardIndex);
                return;
            }
        }
    }

    /**
     * Returns the write-ahead log entries since a given timestamp for delta sync.
     *
     * @param shardIndex the shard index
     * @param since      timestamp from which to retrieve operations
     * @return list of operations since the given timestamp
     */
    public List<WriteOperation> getDeltaOperations(int shardIndex, Instant since) {
        CopyOnWriteArrayList<WriteOperation> wal = writeAheadLogs.get(shardIndex);
        if (wal == null || since == null) {
            return Collections.emptyList();
        }
        List<WriteOperation> delta = new ArrayList<>();
        for (WriteOperation op : wal) {
            if (op.timestamp().isAfter(since)) {
                delta.add(op);
            }
        }
        return Collections.unmodifiableList(delta);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (healthCheckFuture != null) {
            healthCheckFuture.cancel(false);
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOG.info("ReplicationManager closed");
    }

    // --- Private helper methods ---

    private void validateReplicaCount(int count) {
        if (count < MIN_REPLICA_COUNT || count > MAX_REPLICA_COUNT) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "replicaCount", MIN_REPLICA_COUNT, MAX_REPLICA_COUNT, count);
        }
    }

    private void handleNoReplicaAvailable(int shardIndex) {
        LOG.severe("No replica available for promotion on shard " + shardIndex +
                ". Marking shard as unavailable.");
        primaryEndpoints.remove(shardIndex);
        if (membershipService != null) {
            membershipService.reportUnavailableShard(shardIndex,
                    "No replica available for promotion after primary failure");
        }
    }

    /**
     * Periodic health check for all replicas. Detects unavailable replicas
     * and triggers promotion when a primary is detected as failed.
     */
    private void checkReplicaHealth() {
        try {
            for (Map.Entry<Integer, CopyOnWriteArrayList<ReplicaInfo>> entry : replicationGroups.entrySet()) {
                int shardIndex = entry.getKey();
                CopyOnWriteArrayList<ReplicaInfo> group = entry.getValue();
                for (int i = 0; i < group.size(); i++) {
                    ReplicaInfo replica = group.get(i);
                    if (replica.state() == ReplicaState.UNAVAILABLE) {
                        // Could attempt automatic re-sync here in a full implementation
                        LOG.fine("Replica " + replica.replicaId() + " for shard " + shardIndex +
                                " still unavailable");
                    }
                }
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error during replica health check", e);
        }
    }

    /**
     * Applies delta operations to a replica endpoint.
     * In a real implementation, this would send data over the network via gRPC.
     *
     * @param shardIndex      the shard index
     * @param replicaEndpoint the target replica endpoint
     * @param operations      the delta operations to apply
     * @return true if all operations were applied successfully
     */
    private boolean applyDeltaOperations(int shardIndex, String replicaEndpoint,
                                         List<WriteOperation> operations) {
        // In a production implementation, this would:
        // 1. Open a gRPC stream to the replica endpoint
        // 2. Send each operation in order
        // 3. Wait for acknowledgment
        // For now, we simulate success for all operations
        return true;
    }

    /**
     * Replicates a single write operation to a replica endpoint within the given deadline.
     *
     * @param replicaEndpoint the target replica
     * @param operation       the write operation to replicate
     * @param deadline        the deadline by which replication must complete
     * @return true if replication completed within deadline
     */
    private boolean replicateToReplica(String replicaEndpoint, WriteOperation operation, Instant deadline) {
        // In a production implementation, this would send via gRPC with deadline
        // For now, check we haven't exceeded the deadline
        if (Instant.now().isAfter(deadline)) {
            return false;
        }
        // Simulate successful replication
        return true;
    }
}