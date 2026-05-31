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
package com.spectrayan.spector.memory.synapse;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Flyweight factory for identity calibration arrays.
 *
 * <h3>Design Pattern: Flyweight</h3>
 * <p>In uncalibrated mode, {@link CognitiveScorer} and
 * {@link com.spectrayan.spector.memory.interference.SemanticDeduplicator}
 * create identical identity calibration arrays on every call. This factory
 * caches arrays by dimension count, eliminating redundant allocations.</p>
 *
 * <h3>Identity Transform</h3>
 * <p>Maps unsigned byte [0, 255] to [-1.0, 1.0] range:
 * {@code min = -1.0}, {@code scale = 2.0/255}.</p>
 */
public final class IdentityCalibration {

    private static final ConcurrentHashMap<Integer, float[]> MINS_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, float[]> SCALES_CACHE = new ConcurrentHashMap<>();

    private IdentityCalibration() {}

    /**
     * Returns a cached identity minimum array for the given dimension count.
     * All values are -1.0f.
     */
    public static float[] mins(int dims) {
        return MINS_CACHE.computeIfAbsent(dims, d -> {
            float[] mins = new float[d];
            java.util.Arrays.fill(mins, -1.0f);
            return mins;
        });
    }

    /**
     * Returns a cached identity scale array for the given dimension count.
     * All values are 2.0/255.
     */
    public static float[] scales(int dims) {
        return SCALES_CACHE.computeIfAbsent(dims, d -> {
            float[] scales = new float[d];
            java.util.Arrays.fill(scales, 2.0f / 255.0f);
            return scales;
        });
    }
}
