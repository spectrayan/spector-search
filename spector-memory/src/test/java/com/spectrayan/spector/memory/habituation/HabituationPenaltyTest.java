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
import static org.assertj.core.api.Assertions.within;

class HabituationPenaltyTest {

    @Test
    void firstReturnGetsNoPenalty() {
        var hab = new HabituationPenalty(0.2f);
        float penalty = hab.recordAndComputePenalty("m1");
        assertThat(penalty).isCloseTo(1.0f, within(0.001f));
    }

    @Test
    void repeatedReturnsDecreasePenalty() {
        var hab = new HabituationPenalty(0.2f);
        float p1 = hab.recordAndComputePenalty("m1"); // 1st: 1.0
        float p2 = hab.recordAndComputePenalty("m1"); // 2nd: 1/(1 + 1*0.2) ≈ 0.833
        float p3 = hab.recordAndComputePenalty("m1"); // 3rd: 1/(1 + 2*0.2) ≈ 0.714

        assertThat(p2).isLessThan(p1);
        assertThat(p3).isLessThan(p2);
    }

    @Test
    void differentMemoriesTrackIndependently() {
        var hab = new HabituationPenalty();
        hab.recordAndComputePenalty("m1");
        hab.recordAndComputePenalty("m1");
        hab.recordAndComputePenalty("m1");

        float m2Penalty = hab.recordAndComputePenalty("m2");
        assertThat(m2Penalty).isCloseTo(1.0f, within(0.001f)); // m2 is fresh
    }

    @Test
    void currentPenaltyWithoutRecording() {
        var hab = new HabituationPenalty();
        assertThat(hab.currentPenalty("m1")).isCloseTo(1.0f, within(0.001f)); // never seen

        hab.recordAndComputePenalty("m1");
        hab.recordAndComputePenalty("m1");
        float penalty = hab.currentPenalty("m1");
        assertThat(penalty).isLessThan(1.0f);
    }

    @Test
    void clearResetsAll() {
        var hab = new HabituationPenalty();
        hab.recordAndComputePenalty("m1");
        hab.recordAndComputePenalty("m2");
        hab.clear();
        assertThat(hab.trackedCount()).isZero();
    }

    @Test
    void highDecayRatePenalizesMoreAggressively() {
        var slow = new HabituationPenalty(0.1f);
        var fast = new HabituationPenalty(0.5f);

        // After 5 returns
        for (int i = 0; i < 5; i++) {
            slow.recordAndComputePenalty("m1");
            fast.recordAndComputePenalty("m1");
        }

        assertThat(fast.currentPenalty("m1")).isLessThan(slow.currentPenalty("m1"));
    }
}
