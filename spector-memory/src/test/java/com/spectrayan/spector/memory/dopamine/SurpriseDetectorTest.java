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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SurpriseDetector} — dopamine-based importance assignment.
 */
class SurpriseDetectorTest {

    @Test
    void duringWarmupReturnsDefaultImportance() {
        var detector = new SurpriseDetector(20);

        // First 19 samples should return default importance (1.0)
        for (int i = 0; i < 19; i++) {
            float importance = detector.computeImportance(0.5f + i * 0.01f);
            assertThat(importance).isEqualTo(1.0f);
        }
    }

    @Test
    void afterWarmupUsesZScoreMapping() {
        var detector = new SurpriseDetector(5);

        // Warmup with 5 samples around distance 1.0
        for (int i = 0; i < 5; i++) {
            detector.computeImportance(1.0f + i * 0.01f);
        }

        // A value at the mean should get normal importance
        float normalImportance = detector.computeImportance(1.02f);
        assertThat(normalImportance).isEqualTo(0.5f);
    }

    @Test
    void extremeOutlierGetsDopamineSpike() {
        var detector = new SurpriseDetector(5);

        // Warmup with tight cluster
        for (int i = 0; i < 10; i++) {
            detector.computeImportance(1.0f);
        }

        // Extreme outlier
        float importance = detector.computeImportance(100.0f);
        assertThat(importance).isGreaterThanOrEqualTo(5.0f);
    }

    @Test
    void verySimilarValueGetsSuppressed() {
        var detector = new SurpriseDetector(5);

        // Build baseline around 10.0 with some spread
        for (int i = 0; i < 20; i++) {
            detector.computeImportance(10.0f + (float)(Math.random() * 2.0 - 1.0));
        }

        // A value well below the mean (very similar to existing memories)
        float importance = detector.computeImportance(5.0f);
        assertThat(importance).isLessThanOrEqualTo(0.5f);
    }

    @Test
    void zScoreToImportanceMappingBoundaries() {
        assertThat(SurpriseDetector.zScoreToImportance(-2.0)).isEqualTo(0.1f);
        assertThat(SurpriseDetector.zScoreToImportance(-0.5)).isEqualTo(0.5f);
        assertThat(SurpriseDetector.zScoreToImportance(0.0)).isEqualTo(0.5f);
        assertThat(SurpriseDetector.zScoreToImportance(1.0)).isEqualTo(0.5f);
        assertThat(SurpriseDetector.zScoreToImportance(1.5)).isEqualTo(2.0f);
        assertThat(SurpriseDetector.zScoreToImportance(2.5)).isEqualTo(5.0f);
        assertThat(SurpriseDetector.zScoreToImportance(4.0)).isEqualTo(10.0f);
    }

    @Test
    void resetClearsBaseline() {
        var detector = new SurpriseDetector(5);

        for (int i = 0; i < 10; i++) {
            detector.computeImportance(1.0f);
        }

        detector.reset();
        assertThat(detector.stats().count()).isZero();
    }
}
