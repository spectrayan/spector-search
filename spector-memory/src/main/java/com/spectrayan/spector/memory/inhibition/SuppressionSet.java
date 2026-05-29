package com.spectrayan.spector.memory.inhibition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session-level recall suppression set.
 *
 * <h3>Biological Analog: Prefrontal Cortex Inhibition</h3>
 * <p>The prefrontal cortex actively suppresses irrelevant memories during focused
 * retrieval. This is why you can concentrate on a task despite having millions
 * of memories competing for attention.</p>
 *
 * <h3>Anti-Hallucination Mechanism</h3>
 * <p>If the agent recalls a memory that leads to a wrong answer, suppressing it
 * prevents repeating the same mistake in the same conversation. The suppression
 * is volatile — it dies when the session ends.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>Backed by {@link ConcurrentHashMap} key set — fully concurrent.</p>
 */
public final class SuppressionSet {

    private static final Logger log = LoggerFactory.getLogger(SuppressionSet.class);

    /** String ID based suppression (primary). */
    private final Set<String> suppressed = ConcurrentHashMap.newKeySet();

    /**
     * Offset-indexed suppression for hot-loop filtering.
     * Key = packed {@code (type_ordinal << 48 | offset)} for O(1) lookup
     * during scoring without requiring String ID resolution.
     */
    private final Set<Long> suppressedOffsets = ConcurrentHashMap.newKeySet();

    /**
     * Suppresses a memory by ID for the remainder of this session.
     *
     * @param memoryId the memory to suppress
     * @param reason   optional reason for suppression (for logging)
     */
    public void suppress(String memoryId, String reason) {
        suppressed.add(memoryId);
        log.debug("Memory suppressed: '{}' (reason: {})", memoryId,
                reason != null ? reason : "unspecified");
    }

    /**
     * Suppresses a memory by ID.
     */
    public void suppress(String memoryId) {
        suppress(memoryId, null);
    }

    /**
     * Registers a suppressed memory's offset for hot-loop filtering.
     *
     * <p>Call this after {@link #suppress(String)} when the memory's
     * location is known, to enable pre-scoring suppression checks.</p>
     *
     * @param typeOrdinal the memory type ordinal (e.g., MemoryType.EPISODIC.ordinal())
     * @param offset      the byte offset of the record in its tier segment
     */
    public void registerOffset(int typeOrdinal, long offset) {
        suppressedOffsets.add(packOffset(typeOrdinal, offset));
    }

    /**
     * Checks if a memory at the given offset is suppressed.
     *
     * <p>O(1) lookup for use in scoring hot loops — avoids the String ID
     * lookup required by {@link #isSuppressed(String)}.</p>
     *
     * @param typeOrdinal the memory type ordinal
     * @param offset      the byte offset of the record
     * @return true if the memory at this offset is suppressed
     */
    public boolean isSuppressedByOffset(int typeOrdinal, long offset) {
        return suppressedOffsets.contains(packOffset(typeOrdinal, offset));
    }

    /**
     * Checks if a memory ID is currently suppressed.
     *
     * @param memoryId the memory to check
     * @return true if suppressed
     */
    public boolean isSuppressed(String memoryId) {
        return suppressed.contains(memoryId);
    }

    /**
     * Removes suppression for a memory.
     *
     * @param memoryId the memory to unsuppress
     */
    public void unsuppress(String memoryId) {
        suppressed.remove(memoryId);
        log.debug("Memory unsuppressed: '{}'", memoryId);
    }

    /**
     * Returns the number of currently suppressed memories.
     */
    public int size() {
        return suppressed.size();
    }

    /**
     * Returns an unmodifiable view of all suppressed memory IDs.
     */
    public Set<String> suppressedIds() {
        return Collections.unmodifiableSet(suppressed);
    }

    /**
     * Clears all suppressions (typically called at session end).
     */
    public void clear() {
        int count = suppressed.size();
        suppressed.clear();
        suppressedOffsets.clear();
        log.debug("Suppression set cleared ({} entries)", count);
    }

    // ── Internal ──

    private static long packOffset(int typeOrdinal, long offset) {
        return ((long) typeOrdinal << 48) | (offset & 0x0000_FFFF_FFFF_FFFFL);
    }
}
