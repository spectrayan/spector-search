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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for Inhibition of Return (IOR) — TTL-based refractory period.
 *
 * <p>Validates that recently recalled memories receive a penalty that
 * linearly recovers from {@code inhibitionFloor} to 1.0 over the TTL.</p>
 */
class InhibitionOfReturnTest {

    @Test
    void noRecallHistory_returnsFullMultiplier() {
        HabituationPenalty penalty = new HabituationPenalty(0.2f, 300_000L, 0.1f);
        float result = penalty.computeInhibitionOfReturn("unknown-memory", System.currentTimeMillis());
        assertThat(result).isEqualTo(1.0f);
    }

    @Test
    void justRecalled_returnsFloor() {
        HabituationPenalty penalty = new HabituationPenalty(0.2f, 300_000L, 0.1f);
        long now = System.currentTimeMillis();

        penalty.recordRecall("mem-1", now);
        float result = penalty.computeInhibitionOfReturn("mem-1", now);

        assertThat(result).isEqualTo(0.1f);
    }

    @Test
    void halfwayThroughTtl_returnsHalfRecovery() {
        HabituationPenalty penalty = new HabituationPenalty(0.2f, 300_000L, 0.1f);
        long recallTime = 1_000_000L;
        long halfwayTime = recallTime + 150_000L; // 2.5 minutes into 5 minute TTL

        penalty.recordRecall("mem-1", recallTime);
        float result = penalty.computeInhibitionOfReturn("mem-1", halfwayTime);

        // Expected: 0.1 + 0.9 * (150_000 / 300_000) = 0.1 + 0.45 = 0.55
        assertThat(result).isCloseTo(0.55f, org.assertj.core.data.Offset.offset(0.01f));
    }

    @Test
    void afterTtlExpires_returnsFullAndCleansUp() {
        HabituationPenalty penalty = new HabituationPenalty(0.2f, 300_000L, 0.1f);
        long recallTime = 1_000_000L;
        long afterTtl = recallTime + 300_001L; // just past 5 minutes

        penalty.recordRecall("mem-1", recallTime);
        assertThat(penalty.iorTrackedCount()).isEqualTo(1);

        float result = penalty.computeInhibitionOfReturn("mem-1", afterTtl);

        assertThat(result).isEqualTo(1.0f);
        assertThat(penalty.iorTrackedCount()).isEqualTo(0); // expired entry cleaned up
    }

    @Test
    void customTtlAndFloor_respected() {
        // Short TTL (10 seconds), higher floor (0.3)
        HabituationPenalty penalty = new HabituationPenalty(0.2f, 10_000L, 0.3f);
        long now = 1_000_000L;

        penalty.recordRecall("mem-1", now);

        // Immediately after: should be 0.3 (the floor)
        assertThat(penalty.computeInhibitionOfReturn("mem-1", now)).isEqualTo(0.3f);

        // 5 seconds in (halfway): 0.3 + 0.7 * 0.5 = 0.65
        float midway = penalty.computeInhibitionOfReturn("mem-1", now + 5_000L);
        assertThat(midway).isCloseTo(0.65f, org.assertj.core.data.Offset.offset(0.01f));

        // After TTL: fully recovered
        assertThat(penalty.computeInhibitionOfReturn("mem-1", now + 10_001L)).isEqualTo(1.0f);
    }

    @Test
    void clearResetsIorTimestamps() {
        HabituationPenalty penalty = new HabituationPenalty();
        long now = System.currentTimeMillis();

        penalty.recordRecall("mem-1", now);
        penalty.recordRecall("mem-2", now);
        assertThat(penalty.iorTrackedCount()).isEqualTo(2);

        penalty.clear();
        assertThat(penalty.iorTrackedCount()).isEqualTo(0);

        // After clear, no penalty applied
        assertThat(penalty.computeInhibitionOfReturn("mem-1", now)).isEqualTo(1.0f);
    }

    @Test
    void multipleMemories_trackedIndependently() {
        HabituationPenalty penalty = new HabituationPenalty(0.2f, 300_000L, 0.1f);
        long t0 = 1_000_000L;

        penalty.recordRecall("mem-1", t0);
        penalty.recordRecall("mem-2", t0 + 100_000L); // recalled 100s later

        // At t0 + 150_000 (2.5 min):
        // mem-1: 150s into 300s = 0.1 + 0.9*(150/300) = 0.55
        // mem-2: 50s into 300s  = 0.1 + 0.9*(50/300) = 0.25
        long queryTime = t0 + 150_000L;
        float mem1Penalty = penalty.computeInhibitionOfReturn("mem-1", queryTime);
        float mem2Penalty = penalty.computeInhibitionOfReturn("mem-2", queryTime);

        assertThat(mem1Penalty).isCloseTo(0.55f, org.assertj.core.data.Offset.offset(0.01f));
        assertThat(mem2Penalty).isCloseTo(0.25f, org.assertj.core.data.Offset.offset(0.01f));
    }
}
