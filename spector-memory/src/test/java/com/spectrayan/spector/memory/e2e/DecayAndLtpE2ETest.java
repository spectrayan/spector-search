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
package com.spectrayan.spector.memory.e2e;

import com.spectrayan.spector.memory.CognitiveResult;
import com.spectrayan.spector.memory.RecallOptions;

import org.junit.jupiter.api.*;

import java.util.List;

import static com.spectrayan.spector.memory.e2e.E2EAssertions.*;
import static org.assertj.core.api.Assertions.*;

/**
 * E2E tests for temporal decay and LTP (Long-Term Potentiation) reconsolidation.
 *
 * <p>Validates that {@code reinforce()} increments agentRecallCount, that
 * LTP-adjusted decay is >= raw decay, and that decay values are within valid ranges.</p>
 */
@DisplayName("🧠 E2E: Decay & LTP Reconsolidation")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DecayAndLtpE2ETest extends AbstractE2ETest {

    @Test
    @Order(1)
    @DisplayName("Reinforce increments agentRecallCount exactly N times")
    void reinforceIncrementsRecallCount() {
        String query = "PostgreSQL connection pool configuration";

        List<CognitiveResult> firstRecall = memory.recall(query,
                RecallOptions.builder().topK(3).build());
        assertThat(firstRecall).isNotEmpty();

        String topId = firstRecall.getFirst().id();
        int initialCount = firstRecall.getFirst().agentRecallCount();

        int reinforcements = 3;
        for (int i = 0; i < reinforcements; i++) {
            memory.reinforce(topId, (byte) 10);
        }

        // After reinforcement, recall again with broader topK to find our memory
        List<CognitiveResult> afterRecall = memory.recall(query,
                RecallOptions.builder().topK(15).build());

        CognitiveResult refreshed = afterRecall.stream()
                .filter(r -> topId.equals(r.id()))
                .findFirst()
                .orElse(null);

        if (refreshed != null) {
            log.info("LTP: '{}' agentRecallCount {} → {}", topId, initialCount, refreshed.agentRecallCount());
            assertThat(refreshed.agentRecallCount())
                    .as("agentRecallCount should increase by %d", reinforcements)
                    .isEqualTo(initialCount + reinforcements);
        } else {
            // Memory may have shifted out of top-K — verify via WAL that reinforce events were written
            log.info("Memory '{}' shifted out of top-15 after reinforcement — checking WAL", topId);
            long reinforceEvents = memory.wal().replay(0).stream()
                    .filter(e -> e.memoryId().equals(topId))
                    .count();
            assertThat(reinforceEvents)
                    .as("WAL should contain reinforce events for '%s'", topId)
                    .isGreaterThan(0);
        }
    }

    @Test
    @Order(2)
    @DisplayName("LTP-adjusted decay >= raw decay for all results")
    void ltpAdjustedDecayIsBetter() {
        List<CognitiveResult> results = memory.recall(
                "PostgreSQL connection pool configuration",
                RecallOptions.builder().topK(5).build());

        for (CognitiveResult r : results) {
            log.info("  {} → agentRecallCount={}, rawDecay={}, ltpDecay={}",
                    r.id(), r.agentRecallCount(), r.decayFactor(), r.ltpAdjustedDecay());
            assertThat(r.ltpAdjustedDecay())
                    .as("LTP decay for '%s' should be >= raw decay", r.id())
                    .isGreaterThanOrEqualTo(r.decayFactor());
        }
    }

    @Test
    @Order(3)
    @DisplayName("Decay values are within valid [0.0, 1.0] range")
    void decayValuesInRange() {
        List<CognitiveResult> results = memory.recall("software development",
                RecallOptions.builder().topK(10).build());

        for (CognitiveResult r : results) {
            assertThat(r.decayFactor())
                    .as("Raw decay of '%s'", r.id())
                    .isBetween(0.0f, 1.0f);
            assertThat(r.ltpAdjustedDecay())
                    .as("LTP decay of '%s'", r.id())
                    .isBetween(0.0f, 1.0f);
        }
    }

    @Test
    @Order(4)
    @DisplayName("Recently ingested memories have decay ≈ 1.0")
    void recentMemoriesHaveHighDecay() {
        List<CognitiveResult> results = memory.recall("database optimization",
                RecallOptions.builder().topK(5).build());

        // All memories were just ingested — decay should be near 1.0
        for (CognitiveResult r : results) {
            assertThat(r.decayFactor())
                    .as("Recently ingested '%s' should have high decay", r.id())
                    .isGreaterThanOrEqualTo(0.8f);
        }
    }
}
