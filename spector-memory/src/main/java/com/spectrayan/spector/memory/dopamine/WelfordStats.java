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

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Welford's online algorithm for computing running mean and standard deviation.
 *
 * <p>O(1) space, O(1) per update, numerically stable. Thread-safe via atomic operations.</p>
 *
 * <h3>Biological Analog: Baseline Prediction</h3>
 * <p>The brain's dopamine system maintains an internal baseline of "expected" stimuli.
 * Welford's algorithm computes that baseline (mean) and the expected variance (stddev),
 * enabling the {@link SurpriseDetector} to calculate z-scores against the running distribution.</p>
 *
 * @see <a href="https://en.wikipedia.org/wiki/Algorithms_for_calculating_variance#Welford's_online_algorithm">
 *     Welford's Algorithm (Wikipedia)</a>
 */
public final class WelfordStats {

    private final AtomicLong count = new AtomicLong(0);
    private volatile double mean = 0.0;
    private volatile double m2 = 0.0;

    // Lock for update atomicity (cheap — updates are infrequent relative to reads)
    private final Object lock = new Object();

    /**
     * Incorporates a new sample into the running statistics.
     *
     * @param value the new observation
     */
    public void update(double value) {
        synchronized (lock) {
            long n = count.incrementAndGet();
            double delta = value - mean;
            mean += delta / n;
            double delta2 = value - mean;
            m2 += delta * delta2;
        }
    }

    /**
     * Returns the current running mean.
     *
     * @return mean of all observed values, or 0.0 if no values observed
     */
    public double mean() {
        return mean;
    }

    /**
     * Returns the current population standard deviation.
     *
     * @return stddev, or 0.0 if fewer than 2 values observed
     */
    public double stddev() {
        long n = count.get();
        if (n < 2) return 0.0;
        return Math.sqrt(m2 / n);
    }

    /**
     * Computes the z-score of a value against the running distribution.
     *
     * @param value the value to score
     * @return z-score (0.0 if stddev is zero or fewer than 2 samples)
     */
    public double zScore(double value) {
        double sd = stddev();
        if (sd < 1e-9) return 0.0;
        return (value - mean) / sd;
    }

    /**
     * Returns the number of samples observed.
     */
    public long count() {
        return count.get();
    }

    /**
     * Resets all statistics.
     */
    public void reset() {
        synchronized (lock) {
            count.set(0);
            mean = 0.0;
            m2 = 0.0;
        }
    }
}
