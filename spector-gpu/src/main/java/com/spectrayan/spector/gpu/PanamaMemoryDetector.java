package com.spectrayan.spector.gpu;

import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Detects potential memory leaks in Panama FFM MemorySegment allocations by
 * tracking their lifecycle (creation and closure) and reporting segments that
 * exceed a configurable lifetime threshold.
 *
 * <p>The detector attaches lifecycle hooks on each tracked MemorySegment to
 * monitor allocation and deallocation. Segments that remain allocated beyond
 * the threshold (default 300 seconds) are flagged as leak candidates with
 * their allocation stack trace, size, and elapsed time.</p>
 *
 * <h3>Monitoring API</h3>
 * <ul>
 *   <li>Total tracked segment count</li>
 *   <li>Total tracked bytes</li>
 *   <li>Count of segments exceeding the lifetime threshold</li>
 *   <li>Count of untrackable segments (hook attachment failed)</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * var detector = new PanamaMemoryDetector(Duration.ofSeconds(300));
 * detector.trackAllocation(segment, Thread.currentThread().getStackTrace());
 * // ... later ...
 * detector.trackDeallocation(segment);
 * // Query leak candidates
 * List<LeakCandidate> leaks = detector.getLeakCandidates(Duration.ofSeconds(300));
 * }</pre>
 *
 * @see LeakCandidate
 * @see AllocationMetrics
 */
public class PanamaMemoryDetector {

    private static final Logger log = LoggerFactory.getLogger(PanamaMemoryDetector.class);

    /** Default lifetime threshold: 300 seconds. */
    private static final Duration DEFAULT_THRESHOLD = Duration.ofSeconds(300);

    /** Minimum allowed threshold: 1 second. */
    private static final Duration MIN_THRESHOLD = Duration.ofSeconds(1);

    private final Duration lifetimeThreshold;
    private final ConcurrentHashMap<Long, TrackedSegment> activeSegments;
    private final AtomicLong idGenerator;
    private final AtomicLong untrackedCount;

    // Maps MemorySegment identity hash to allocation ID for deallocation lookup
    private final ConcurrentHashMap<Long, Long> segmentToId;

    /**
     * Creates a PanamaMemoryDetector with the default lifetime threshold (300s).
     */
    public PanamaMemoryDetector() {
        this(DEFAULT_THRESHOLD);
    }

    /**
     * Creates a PanamaMemoryDetector with the specified lifetime threshold.
     *
     * @param lifetimeThreshold threshold beyond which a segment is reported as a leak candidate;
     *                          minimum value is 1 second
     * @throws SpectorValidationException if threshold is less than 1 second
     */
    public PanamaMemoryDetector(Duration lifetimeThreshold) {
        if (lifetimeThreshold == null || lifetimeThreshold.compareTo(MIN_THRESHOLD) < 0) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "lifetimeThreshold", 1, Integer.MAX_VALUE, lifetimeThreshold);
        }
        this.lifetimeThreshold = lifetimeThreshold;
        this.activeSegments = new ConcurrentHashMap<>();
        this.idGenerator = new AtomicLong(0);
        this.untrackedCount = new AtomicLong(0);
        this.segmentToId = new ConcurrentHashMap<>();
    }

    /**
     * Tracks a new MemorySegment allocation with the given allocation site stack trace.
     *
     * <p>If the segment cannot be tracked (e.g., null segment or scope already closed),
     * a warning is logged and the untracked-segment counter is incremented.</p>
     *
     * @param segment    the MemorySegment to track
     * @param allocSite  the stack trace at the point of allocation
     */
    public void trackAllocation(MemorySegment segment, StackTraceElement[] allocSite) {
        if (segment == null) {
            handleUntrackable("null segment provided");
            return;
        }

        try {
            // Verify the segment's scope is still alive (hookable)
            if (!segment.scope().isAlive()) {
                handleUntrackable("segment scope already closed at tracking time");
                return;
            }
        } catch (Exception e) {
            handleUntrackable("failed to check segment scope: " + e.getMessage());
            return;
        }

        long allocationId = idGenerator.incrementAndGet();
        long segmentKey = segmentIdentityKey(segment);
        long sizeBytes = segmentSize(segment);

        TrackedSegment tracked = new TrackedSegment(
                allocationId, segment, sizeBytes, Instant.now(),
                allocSite != null ? allocSite : new StackTraceElement[0]
        );

        activeSegments.put(allocationId, tracked);
        segmentToId.put(segmentKey, allocationId);

        // Start a virtual thread to monitor the segment's scope lifecycle
        Thread.startVirtualThread(() -> monitorSegmentLifecycle(allocationId, tracked));

        log.debug("Tracking allocation: id={}, size={} bytes", allocationId, sizeBytes);
    }

    /**
     * Explicitly marks a tracked MemorySegment as deallocated, removing it
     * from the active allocation registry.
     *
     * @param segment the MemorySegment that has been closed/deallocated
     */
    public void trackDeallocation(MemorySegment segment) {
        if (segment == null) {
            return;
        }

        long segmentKey = segmentIdentityKey(segment);
        Long allocationId = segmentToId.remove(segmentKey);

        if (allocationId != null) {
            TrackedSegment removed = activeSegments.remove(allocationId);
            if (removed != null) {
                log.debug("Deallocation tracked: id={}, lived for {}ms",
                        allocationId, Duration.between(removed.allocatedAt(), Instant.now()).toMillis());
            }
        }
    }

    /**
     * Returns all segments that have been allocated longer than the specified threshold.
     *
     * @param threshold the duration threshold; segments alive longer than this are returned
     * @return list of leak candidates exceeding the threshold
     */
    public List<LeakCandidate> getLeakCandidates(Duration threshold) {
        Duration effectiveThreshold = (threshold != null) ? threshold : lifetimeThreshold;
        Instant now = Instant.now();
        List<LeakCandidate> candidates = new ArrayList<>();

        for (TrackedSegment tracked : activeSegments.values()) {
            Duration elapsed = Duration.between(tracked.allocatedAt(), now);
            if (elapsed.compareTo(effectiveThreshold) > 0) {
                candidates.add(new LeakCandidate(
                        tracked.allocationId(),
                        tracked.sizeBytes(),
                        tracked.allocatedAt(),
                        elapsed,
                        tracked.allocationSite()
                ));

                // Log the leak candidate details (Req 23.3)
                log.warn("Potential memory leak detected: id={}, size={} bytes, elapsed={}s, allocSite={}",
                        tracked.allocationId(),
                        tracked.sizeBytes(),
                        elapsed.getSeconds(),
                        formatStackTrace(tracked.allocationSite()));
            }
        }

        return candidates;
    }

    /**
     * Returns leak candidates using the configured default lifetime threshold.
     *
     * @return list of leak candidates exceeding the default threshold
     */
    public List<LeakCandidate> getLeakCandidates() {
        return getLeakCandidates(lifetimeThreshold);
    }

    /**
     * Returns current allocation metrics for the monitoring API.
     *
     * <p>Includes total segments, total bytes, threshold-exceeding count, and
     * untracked segment count.</p>
     *
     * @return current allocation metrics snapshot
     */
    public AllocationMetrics getMetrics() {
        Instant now = Instant.now();
        int totalSegments = activeSegments.size();
        long totalBytes = 0;
        int thresholdExceeding = 0;

        for (TrackedSegment tracked : activeSegments.values()) {
            totalBytes += tracked.sizeBytes();
            Duration elapsed = Duration.between(tracked.allocatedAt(), now);
            if (elapsed.compareTo(lifetimeThreshold) > 0) {
                thresholdExceeding++;
            }
        }

        return new AllocationMetrics(totalSegments, totalBytes, thresholdExceeding, untrackedCount.get());
    }

    /**
     * Returns the configured lifetime threshold.
     *
     * @return the lifetime threshold duration
     */
    public Duration getLifetimeThreshold() {
        return lifetimeThreshold;
    }

    /**
     * Returns the count of segments that could not be tracked.
     *
     * @return untracked segment count
     */
    public long getUntrackedSegmentCount() {
        return untrackedCount.get();
    }

    // ──── Internal ────────────────────────────────────────────────────────────

    /**
     * Monitors a tracked segment's scope. When the scope becomes non-alive,
     * the segment is removed from the registry (within 1 second per Req 23.6).
     */
    private void monitorSegmentLifecycle(long allocationId, TrackedSegment tracked) {
        while (activeSegments.containsKey(allocationId)) {
            try {
                if (!tracked.segment().scope().isAlive()) {
                    // Scope closed — remove from registry
                    removeTrackedSegment(allocationId, tracked);
                    return;
                }
                // Poll every 500ms to ensure removal within 1 second of close
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // Segment may have become invalid — remove from tracking
                removeTrackedSegment(allocationId, tracked);
                return;
            }
        }
    }

    private void removeTrackedSegment(long allocationId, TrackedSegment tracked) {
        if (activeSegments.remove(allocationId) != null) {
            long segmentKey = segmentIdentityKey(tracked.segment());
            segmentToId.remove(segmentKey);
            log.debug("Segment removed from registry after scope close: id={}", allocationId);
        }
    }

    private void handleUntrackable(String reason) {
        untrackedCount.incrementAndGet();
        log.warn("Unable to track MemorySegment: {}", reason);
    }

    /**
     * Generates a unique key for a MemorySegment based on its identity hash code.
     * This allows mapping from segment instance back to allocation ID for deallocation tracking.
     */
    private static long segmentIdentityKey(MemorySegment segment) {
        return System.identityHashCode(segment);
    }

    /**
     * Safely retrieves the byte size of a MemorySegment.
     * Returns 0 if the size cannot be determined (e.g., zero-length or native segment).
     */
    private static long segmentSize(MemorySegment segment) {
        try {
            return segment.byteSize();
        } catch (UnsupportedOperationException e) {
            return 0;
        }
    }

    private static String formatStackTrace(StackTraceElement[] stackTrace) {
        if (stackTrace == null || stackTrace.length == 0) {
            return "<no stack trace>";
        }
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(stackTrace.length, 5);
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append(" <- ");
            sb.append(stackTrace[i]);
        }
        if (stackTrace.length > limit) {
            sb.append(" ... (").append(stackTrace.length - limit).append(" more)");
        }
        return sb.toString();
    }

    // ──── Internal record for tracking ────────────────────────────────────────

    /**
     * Internal record holding all data for a tracked segment.
     */
    private record TrackedSegment(
            long allocationId,
            MemorySegment segment,
            long sizeBytes,
            Instant allocatedAt,
            StackTraceElement[] allocationSite
    ) {}
}
