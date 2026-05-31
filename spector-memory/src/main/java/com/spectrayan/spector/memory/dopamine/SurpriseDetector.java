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
package com.spectrayan.spector.memory.dopamine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adaptive surprise detection engine — automatically assigns importance at ingestion time.
 *
 * <h3>Biological Analog: Dopamine Prediction Error Signaling</h3>
 * <p>The brain is a prediction engine. If you eat a normal breakfast, you forget it in
 * an hour. If the toaster catches fire, a dopamine spike sears the event into your
 * brain forever. The brain scales memory strength based on <em>Prediction Error</em> —
 * the gap between what was expected and what actually happened.</p>
 *
 * <h3>Why Not Fixed L2 Thresholds?</h3>
 * <p>Fixed thresholds (e.g., {@code L2 < 0.1 = boring}) are embedding-model-dependent.
 * {@code nomic-embed-text} (768-dim) produces very different L2 ranges than
 * {@code all-MiniLM-L6-v2} (384-dim). Z-score normalization adapts to any model
 * automatically.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>This class is thread-safe. The internal {@link WelfordStats} uses synchronized
 * updates, and reads are volatile.</p>
 */
public final class SurpriseDetector {

    private static final Logger log = LoggerFactory.getLogger(SurpriseDetector.class);

    private final WelfordStats stats;

    /** Minimum samples required before z-score-based importance kicks in. */
    private final int warmupSamples;

    /** Default importance assigned during warmup period. */
    private static final float DEFAULT_IMPORTANCE = 1.0f;

    /**
     * Creates a new surprise detector.
     *
     * @param warmupSamples minimum observations before adaptive importance activates (default: 20)
     */
    public SurpriseDetector(int warmupSamples) {
        this.stats = new WelfordStats();
        this.warmupSamples = warmupSamples;
    }

    /**
     * Creates a surprise detector with default warmup (20 samples).
     */
    public SurpriseDetector() {
        this(20);
    }

    /**
     * Computes the surprise score for a new memory based on its distance to existing content.
     *
     * <p>Call this during ingestion: compute the L2 distance from the new vector to
     * the nearest existing centroid or cluster center, then pass that distance here.</p>
     *
     * @param distanceToNearest L2 distance from new vector to nearest existing memory/centroid
     * @return importance value (0.1 = mundane, 1.0 = default, 10.0 = extreme surprise)
     */
    public float computeImportance(float distanceToNearest) {
        // Update running statistics
        stats.update(distanceToNearest);

        // During warmup, return default importance
        if (stats.count() < warmupSamples) {
            return DEFAULT_IMPORTANCE;
        }

        // Compute z-score against the running distribution
        double zScore = stats.zScore(distanceToNearest);
        float importance = zScoreToImportance(zScore);

        if (importance >= 5.0f) {
            log.debug("Dopamine spike! z-score={}, importance={}", zScore, importance);
        }

        return importance;
    }

    /**
     * Maps a z-score to an importance value.
     *
     * <pre>
     *   z < -1.0 → very similar to existing memories → 0.1 (suppress)
     *   z ∈ [-1, 1] → normal → 0.5 (default)
     *   z > 1.0 → moderately novel → 2.0
     *   z > 2.0 → highly novel → 5.0
     *   z > 3.0 → extreme outlier → 10.0 (dopamine spike!)
     * </pre>
     */
    static float zScoreToImportance(double zScore) {
        if (zScore < -1.0) return 0.1f;  // Very similar to known memories
        if (zScore <= 1.0) return 0.5f;  // Normal, expected
        if (zScore <= 2.0) return 2.0f;  // Moderately novel
        if (zScore <= 3.0) return 5.0f;  // Highly novel
        return 10.0f;                     // Extreme outlier — dopamine spike!
    }

    /**
     * Returns the underlying statistics for introspection.
     */
    public WelfordStats stats() {
        return stats;
    }

    // ── V2: Dual Surprise Signal (Spatial + Temporal) ──

    private final WelfordStats temporalStats = new WelfordStats();
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> lastSeenByTags =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Computes dual surprise: spatial novelty + temporal recurrence.
     *
     * <p>Spatial surprise measures how far a new vector is from known clusters.
     * Temporal surprise measures how long since we saw something with similar tags —
     * a recurrence after a long gap is itself surprising (e.g., "the database crashed
     * again" is semantically familiar but temporally novel).</p>
     *
     * @param distanceToNearest L2 distance from new vector to nearest existing memory
     * @param synapticTags      Bloom filter tags of the new memory
     * @param spatialWeight     weight for spatial surprise (default: 0.6)
     * @param temporalWeight    weight for temporal surprise (default: 0.4)
     * @return importance value (0.1 to 10.0)
     */
    public float computeDualImportance(float distanceToNearest, long synapticTags,
                                        float spatialWeight, float temporalWeight) {
        // Spatial surprise
        stats.update(distanceToNearest);
        double spatialZ = stats.count() < warmupSamples ? 0.0 : stats.zScore(distanceToNearest);

        // Temporal surprise: time since last memory with overlapping tags
        long nowMs = System.currentTimeMillis();
        Long lastSeen = lastSeenByTags.put(synapticTags, nowMs);
        double temporalZ = 0.0;

        if (lastSeen != null) {
            float hoursSinceLast = (nowMs - lastSeen) / (1000f * 3600f);
            temporalStats.update(hoursSinceLast);
            if (temporalStats.count() >= warmupSamples) {
                temporalZ = temporalStats.zScore(hoursSinceLast);
            }
        }

        double combinedZ = spatialWeight * spatialZ + temporalWeight * temporalZ;
        float importance = zScoreToImportance(combinedZ);

        if (importance >= 5.0f) {
            log.debug("Dual dopamine spike! spatialZ={}, temporalZ={}, combined={}, importance={}",
                    spatialZ, temporalZ, combinedZ, importance);
        }

        return importance;
    }

    /**
     * Convenience: dual surprise with default weights (0.6 spatial, 0.4 temporal).
     */
    public float computeDualImportance(float distanceToNearest, long synapticTags) {
        return computeDualImportance(distanceToNearest, synapticTags, 0.6f, 0.4f);
    }

    /**
     * Resets the detector, clearing all learned baseline statistics.
     */
    public void reset() {
        stats.reset();
        temporalStats.reset();
        lastSeenByTags.clear();
    }
}
