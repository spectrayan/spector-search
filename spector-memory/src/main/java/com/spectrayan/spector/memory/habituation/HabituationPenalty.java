/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Business Source License 1.1 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://github.com/spectrayan/spector/blob/main/spector-memory/LICENSE
 *
 * Change Date: May 27, 2030
 * Change License: Apache License, Version 2.0
 */
package com.spectrayan.spector.memory.habituation;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Session-level result diversity penalty to prevent recall fixation.
 *
 * <h3>Biological Analog: Sensory Habituation</h3>
 * <p>Repeated exposure to the same stimulus decreases neural response. You stop
 * hearing the clock ticking after a few minutes. The brain deprioritizes repetitive
 * input to make room for novel information.</p>
 *
 * <h3>Anti-Filter-Bubble Mechanism</h3>
 * <p>Tracks how many times each memory has been returned in the current session.
 * Applies a diminishing multiplier to frequently-returned memories, forcing the
 * agent to consider alternative information.</p>
 *
 * <pre>
 *   1st return → 1.0x (no penalty)
 *   5th return → 0.5x
 *   10th return → 0.33x
 *   20th return → 0.2x
 * </pre>
 *
 * <h3>Thread Safety</h3>
 * <p>Fully concurrent via {@link ConcurrentHashMap} + {@link AtomicInteger}.</p>
 */
public final class HabituationPenalty {

    /** Habituation decay rate. Higher = faster habituation. */
    private final float decayRate;

    /** Per-memory return counts for this session. */
    private final ConcurrentHashMap<String, AtomicInteger> returnCounts = new ConcurrentHashMap<>();

    // ── Inhibition of Return (TTL-based refractory period) ──

    /** Per-memory last-recall timestamps for IOR penalty. */
    private final ConcurrentHashMap<String, Long> lastRecallTimestamps = new ConcurrentHashMap<>();

    /** Inhibition of Return TTL in milliseconds (default: 5 minutes). */
    private final long inhibitionTtlMs;

    /** Minimum multiplier during IOR (default: 0.1 = 90% suppression). */
    private final float inhibitionFloor;

    /**
     * Creates a habituation penalty calculator.
     *
     * @param decayRate habituation strength (default: 0.2, higher = faster habituation)
     * @param inhibitionTtlMs IOR refractory period in millis (default: 300_000 = 5 minutes)
     * @param inhibitionFloor minimum IOR multiplier (default: 0.1)
     */
    public HabituationPenalty(float decayRate, long inhibitionTtlMs, float inhibitionFloor) {
        this.decayRate = decayRate;
        this.inhibitionTtlMs = inhibitionTtlMs;
        this.inhibitionFloor = inhibitionFloor;
    }

    /**
     * Creates a habituation penalty calculator with default IOR settings.
     *
     * @param decayRate habituation strength (default: 0.2, higher = faster habituation)
     */
    public HabituationPenalty(float decayRate) {
        this(decayRate, 300_000L, 0.1f);
    }

    /**
     * Creates a habituation penalty with all defaults (decayRate=0.2, TTL=5min, floor=0.1).
     */
    public HabituationPenalty() {
        this(0.2f, 300_000L, 0.1f);
    }

    /**
     * Records that a memory was returned in a recall result and computes the
     * habituation multiplier.
     *
     * @param memoryId the memory that was returned
     * @return habituation multiplier (1.0 = first time, decreasing for repeats)
     */
    public float recordAndComputePenalty(String memoryId) {
        int timesReturned = returnCounts
                .computeIfAbsent(memoryId, k -> new AtomicInteger(0))
                .incrementAndGet();
        return computePenalty(timesReturned);
    }

    /**
     * Computes the habituation penalty without recording a return.
     *
     * @param memoryId the memory to check
     * @return current habituation multiplier
     */
    public float currentPenalty(String memoryId) {
        AtomicInteger count = returnCounts.get(memoryId);
        if (count == null) return 1.0f;
        return computePenalty(count.get());
    }

    /**
     * Computes the penalty for a given return count.
     * Formula: 1.0 / (1.0 + timesReturned * decayRate)
     */
    private float computePenalty(int timesReturned) {
        return 1.0f / (1.0f + (timesReturned - 1) * decayRate);
    }

    /**
     * Returns the number of unique memories tracked.
     */
    public int trackedCount() {
        return returnCounts.size();
    }

    /**
     * Clears all habituation data and IOR timestamps (typically at session end).
     */
    public void clear() {
        returnCounts.clear();
        lastRecallTimestamps.clear();
    }

    // ══════════════════════════════════════════════════════════════
    // INHIBITION OF RETURN — TTL-based refractory period
    // ══════════════════════════════════════════════════════════════

    /**
     * Records a recall timestamp for Inhibition of Return tracking.
     *
     * <p>Call this after a memory is returned in a recall result. The timestamp
     * is used to compute the IOR penalty on subsequent recalls.</p>
     *
     * @param memoryId the recalled memory's ID
     * @param nowMs    current time in epoch millis
     */
    public void recordRecall(String memoryId, long nowMs) {
        lastRecallTimestamps.put(memoryId, nowMs);
    }

    /**
     * Computes the Inhibition of Return penalty for a memory.
     *
     * <h3>Biological Analog: Refractory Period</h3>
     * <p>After a neuron fires, it enters a refractory period where it cannot
     * fire again at full strength. This prevents the brain from getting stuck
     * in activation loops. The penalty recovers linearly from {@code inhibitionFloor}
     * to {@code 1.0} over the TTL duration.</p>
     *
     * <pre>
     *   Just recalled → 0.1x (strong suppression)
     *   2.5 min later → 0.55x (recovering)
     *   5+ min later  → 1.0x (fully recovered)
     * </pre>
     *
     * @param memoryId the memory to check
     * @param nowMs    current time in epoch millis
     * @return multiplier in [{@code inhibitionFloor}, 1.0]
     */
    public float computeInhibitionOfReturn(String memoryId, long nowMs) {
        Long lastRecall = lastRecallTimestamps.get(memoryId);
        if (lastRecall == null) return 1.0f;

        long ageMs = nowMs - lastRecall;
        if (ageMs >= inhibitionTtlMs) {
            lastRecallTimestamps.remove(memoryId); // cleanup expired
            return 1.0f;
        }

        // Linear recovery: inhibitionFloor → 1.0 over TTL
        return inhibitionFloor + (1.0f - inhibitionFloor) * ((float) ageMs / inhibitionTtlMs);
    }

    /**
     * Returns the IOR TTL in milliseconds.
     */
    public long inhibitionTtlMs() {
        return inhibitionTtlMs;
    }

    /**
     * Returns the number of memories with active IOR timestamps.
     */
    public int iorTrackedCount() {
        return lastRecallTimestamps.size();
    }

    /**
     * Batch penalty computation — records all IDs and returns their penalties.
     *
     * <p>Minimizes ConcurrentHashMap contention by processing all results
     * in a tight loop. Particularly effective when called from a single
     * recall thread (no cross-thread CHM contention).</p>
     *
     * @param memoryIds array of memory IDs to record
     * @return array of habituation multipliers (1.0 = first time, decreasing for repeats)
     */
    public float[] recordAndComputeBatch(String[] memoryIds) {
        float[] penalties = new float[memoryIds.length];
        for (int i = 0; i < memoryIds.length; i++) {
            penalties[i] = recordAndComputePenalty(memoryIds[i]);
        }
        return penalties;
    }
}
