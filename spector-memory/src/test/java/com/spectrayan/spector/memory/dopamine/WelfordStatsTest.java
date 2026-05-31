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
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for {@link WelfordStats} — running mean/stddev.
 */
class WelfordStatsTest {

    @Test
    void emptyStatsReturnZero() {
        var stats = new WelfordStats();

        assertThat(stats.mean()).isZero();
        assertThat(stats.stddev()).isZero();
        assertThat(stats.count()).isZero();
    }

    @Test
    void singleValueGivesMeanNoStddev() {
        var stats = new WelfordStats();
        stats.update(5.0);

        assertThat(stats.mean()).isCloseTo(5.0, within(1e-9));
        assertThat(stats.stddev()).isZero(); // need at least 2 samples
        assertThat(stats.count()).isEqualTo(1);
    }

    @Test
    void knownInputProducesCorrectStats() {
        var stats = new WelfordStats();
        // Values: 2, 4, 4, 4, 5, 5, 7, 9
        // Mean = 5.0, Population stddev = √4 = 2.0
        double[] values = {2, 4, 4, 4, 5, 5, 7, 9};
        for (double v : values) {
            stats.update(v);
        }

        assertThat(stats.mean()).isCloseTo(5.0, within(1e-9));
        assertThat(stats.stddev()).isCloseTo(2.0, within(1e-9));
        assertThat(stats.count()).isEqualTo(8);
    }

    @Test
    void zScoreCalculation() {
        var stats = new WelfordStats();
        double[] values = {2, 4, 4, 4, 5, 5, 7, 9};
        for (double v : values) {
            stats.update(v);
        }

        // Mean = 5.0, stddev = 2.0
        assertThat(stats.zScore(5.0)).isCloseTo(0.0, within(1e-9));  // at mean
        assertThat(stats.zScore(7.0)).isCloseTo(1.0, within(1e-9));  // 1 sigma above
        assertThat(stats.zScore(3.0)).isCloseTo(-1.0, within(1e-9)); // 1 sigma below
        assertThat(stats.zScore(11.0)).isCloseTo(3.0, within(1e-9)); // 3 sigma above
    }

    @Test
    void zScoreWithZeroStddevReturnsZero() {
        var stats = new WelfordStats();
        stats.update(5.0);

        assertThat(stats.zScore(10.0)).isZero();
    }

    @Test
    void resetClearsAll() {
        var stats = new WelfordStats();
        stats.update(1.0);
        stats.update(2.0);
        stats.update(3.0);

        stats.reset();

        assertThat(stats.count()).isZero();
        assertThat(stats.mean()).isZero();
        assertThat(stats.stddev()).isZero();
    }
}
