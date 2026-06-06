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

        // A value near the mean should produce importance around the sigmoid midpoint
        // For z≈0, sigmoid(1.2*(0-1)) ≈ 0.23 → importance ≈ 0.05 + 0.23*9.95 ≈ 2.34
        float normalImportance = detector.computeImportance(1.02f);
        assertThat(normalImportance).isBetween(0.5f, 5.0f); // moderate range, not extreme
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
    void zScoreToImportanceMappingContinuous() {
        // Continuous sigmoid: verify monotonicity and range
        float atNeg2 = SurpriseDetector.zScoreToImportance(-2.0);
        float atNeg05 = SurpriseDetector.zScoreToImportance(-0.5);
        float at0 = SurpriseDetector.zScoreToImportance(0.0);
        float at1 = SurpriseDetector.zScoreToImportance(1.0);
        float at2 = SurpriseDetector.zScoreToImportance(2.0);
        float at4 = SurpriseDetector.zScoreToImportance(4.0);

        // Monotonically increasing
        assertThat(atNeg2).isLessThan(atNeg05);
        assertThat(atNeg05).isLessThan(at0);
        assertThat(at0).isLessThan(at1);
        assertThat(at1).isLessThan(at2);
        assertThat(at2).isLessThan(at4);

        // Range: all values in [0.05, 10.0]
        assertThat(atNeg2).isGreaterThanOrEqualTo(0.05f);
        assertThat(at4).isLessThanOrEqualTo(10.0f);

        // At z=1.0 (sigmoid center), importance should be ~5.0 (midpoint)
        assertThat(at1).isBetween(4.5f, 5.5f);

        // Extreme low z should be close to floor
        assertThat(atNeg2).isLessThan(1.0f);

        // Extreme high z should be close to ceiling
        assertThat(at4).isGreaterThan(9.0f);
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
