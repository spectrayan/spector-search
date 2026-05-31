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
package com.spectrayan.spector.memory.metamemory;

import com.spectrayan.spector.memory.CognitiveResult;
import com.spectrayan.spector.memory.MemoryType;
import com.spectrayan.spector.memory.cortex.MemorySource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryIntrospectorTest {

    private final MemoryIntrospector introspector = new MemoryIntrospector();

    @Test
    void emptyResultsProduceEmptyInsight() {
        var insight = introspector.analyze("kubernetes", List.of());
        assertThat(insight.isKnown()).isFalse();
        assertThat(insight.totalMemories()).isZero();
        assertThat(insight.confidence()).isZero();
        assertThat(insight.recommendation()).contains("No memories found");
    }

    @Test
    void nullResultsProduceEmptyInsight() {
        var insight = introspector.analyze("topic", null);
        assertThat(insight.isKnown()).isFalse();
    }

    @Test
    void highConfidenceWithManyReinforcedMemories() {
        var results = List.of(
                makeResult(5.0f, 1.0f, (short) 3, (byte) 50),
                makeResult(3.0f, 2.0f, (short) 5, (byte) 30),
                makeResult(4.0f, 3.0f, (short) 2, (byte) 40),
                makeResult(6.0f, 0.5f, (short) 4, (byte) 60),
                makeResult(7.0f, 1.0f, (short) 6, (byte) 20),
                makeResult(5.0f, 2.0f, (short) 3, (byte) 50),
                makeResult(4.0f, 0.5f, (short) 1, (byte) 30),
                makeResult(8.0f, 1.0f, (short) 2, (byte) 70),
                makeResult(3.0f, 3.0f, (short) 4, (byte) 40),
                makeResult(5.0f, 0.5f, (short) 3, (byte) 50)
        );

        var insight = introspector.analyze("java-performance", results);
        assertThat(insight.isKnown()).isTrue();
        assertThat(insight.confidence()).isGreaterThan(0.3f);
        assertThat(insight.totalMemories()).isEqualTo(10);
    }

    @Test
    void staleKnowledgeDetected() {
        var results = List.of(
                makeResult(2.0f, 100f, (short) 0, (byte) 0)  // 100 days old
        );

        var insight = introspector.analyze("old-topic", results);
        assertThat(insight.staleness()).isGreaterThan(0.7f);
        // With just 1 memory, confidence is low → recommendation mentions "sparse"
        // Staleness is still correctly detected via the staleness field
        assertThat(insight.isStale()).isTrue();
    }

    @Test
    void lowConfidenceWithFewMemories() {
        var results = List.of(
                makeResult(0.5f, 1.0f, (short) 0, (byte) 0)
        );

        var insight = introspector.analyze("obscure-topic", results);
        assertThat(insight.confidence()).isLessThan(0.3f);
        assertThat(insight.recommendation()).contains("sparse");
    }

    private CognitiveResult makeResult(float importance, float ageDays,
                                        short recallCount, byte valence) {
        return new CognitiveResult(
                "test-id", "test text", 0.8f, importance, ageDays,
                recallCount, valence, MemoryType.SEMANTIC, MemorySource.OBSERVED,
                new String[]{"test"}, 0.7f, 0.8f
        );
    }
}
