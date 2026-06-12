/*
 * Copyright 2026 Spectrayan
 */
package com.spectrayan.spector.memory.model;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.spectrayan.spector.memory.cortex.MemorySource;

/**
 * Tests for memory model records, enums, and computed helpers:
 * ConfidenceBand, ImportanceEstimate, MemoryType, RecallMode, ScoringMode.
 */
@DisplayName("Memory Model")
class MemoryModelTest {

    /** Helper to create a CognitiveResult with just id, text, and score. */
    private static CognitiveResult result(String id, String text, float score) {
        return new CognitiveResult(id, text, score, 1.0f, 0f, 0, (byte) 0,
                MemoryType.SEMANTIC, MemorySource.OBSERVED, null, 1.0f, 1.0f);
    }

    // ══════════════════════════════════════════════════════════════
    // ConfidenceBand
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ConfidenceBand")
    class ConfidenceBandTests {

        @Test
        @DisplayName("null results → LOW")
        void nullResults() {
            assertThat(ConfidenceBand.classify(null)).isEqualTo(ConfidenceBand.LOW);
        }

        @Test
        @DisplayName("empty results → LOW")
        void emptyResults() {
            assertThat(ConfidenceBand.classify(List.of())).isEqualTo(ConfidenceBand.LOW);
        }

        @Test
        @DisplayName("single high-score result → HIGH")
        void singleHighScore() {
            assertThat(ConfidenceBand.classify(List.of(result("a", "text", 0.9f))))
                    .isEqualTo(ConfidenceBand.HIGH);
        }

        @Test
        @DisplayName("single near-zero result → MEDIUM")
        void singleNearZero() {
            assertThat(ConfidenceBand.classify(List.of(result("a", "text", 0.05f))))
                    .isEqualTo(ConfidenceBand.MEDIUM);
        }

        @Test
        @DisplayName("two results with ratio ≥ 2.0 → HIGH")
        void highRatio() {
            assertThat(ConfidenceBand.classify(List.of(
                    result("a", "text", 0.8f),
                    result("b", "text", 0.3f))))
                    .isEqualTo(ConfidenceBand.HIGH);
        }

        @Test
        @DisplayName("two results with ratio 1.2-2.0 → MEDIUM")
        void mediumRatio() {
            assertThat(ConfidenceBand.classify(List.of(
                    result("a", "text", 0.6f),
                    result("b", "text", 0.45f))))
                    .isEqualTo(ConfidenceBand.MEDIUM);
        }

        @Test
        @DisplayName("two results with ratio < 1.2 → LOW")
        void lowRatio() {
            assertThat(ConfidenceBand.classify(List.of(
                    result("a", "text", 0.5f),
                    result("b", "text", 0.48f))))
                    .isEqualTo(ConfidenceBand.LOW);
        }

        @Test
        @DisplayName("second score is zero, top is positive → HIGH")
        void secondScoreZero() {
            assertThat(ConfidenceBand.classify(List.of(
                    result("a", "text", 0.5f),
                    result("b", "text", 0.0f))))
                    .isEqualTo(ConfidenceBand.HIGH);
        }

        @Test
        @DisplayName("both scores are zero → LOW")
        void bothZero() {
            assertThat(ConfidenceBand.classify(List.of(
                    result("a", "text", 0.0f),
                    result("b", "text", 0.0f))))
                    .isEqualTo(ConfidenceBand.LOW);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ImportanceEstimate
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ImportanceEstimate")
    class ImportanceEstimateTests {

        @Test
        @DisplayName("toSummary includes key fields")
        void toSummaryIncludesFields() {
            var est = new ImportanceEstimate(0.82f, 1.5, 7.8f, 6.2f,
                    "mem-42", 0.15f, false, "I=0.3, C=0.2, N=0.4, U=0.1");
            var summary = est.toSummary();
            assertThat(summary).contains("Novelty:");
            assertThat(summary).contains("Fused:");
            assertThat(summary).contains("mem-42");
            assertThat(summary).contains("0.82");
        }

        @Test
        @DisplayName("toSummary handles null nearestMemoryId")
        void toSummaryNullNearest() {
            var est = new ImportanceEstimate(0.5f, 0.0, 5.0f, 5.0f,
                    null, 0.0f, false, "default");
            var summary = est.toSummary();
            assertThat(summary).contains("no existing memories");
        }

        @Test
        @DisplayName("toSummary shows FLASHBULB when true")
        void toSummaryFlashbulb() {
            var est = new ImportanceEstimate(0.99f, 3.0, 10.0f, 9.5f,
                    "mem-1", 0.01f, true, "weights");
            var summary = est.toSummary();
            assertThat(summary).contains("FLASHBULB");
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Enums
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("MemoryType enum")
    class MemoryTypeTests {
        @Test
        @DisplayName("has all 4 values")
        void allValues() {
            assertThat(MemoryType.values()).containsExactlyInAnyOrder(
                    MemoryType.WORKING, MemoryType.EPISODIC,
                    MemoryType.SEMANTIC, MemoryType.PROCEDURAL);
        }
    }

    @Nested
    @DisplayName("RecallMode enum")
    class RecallModeTests {
        @Test
        @DisplayName("has LEARN, OBSERVE, REPLAY")
        void allValues() {
            assertThat(RecallMode.values()).containsExactlyInAnyOrder(
                    RecallMode.LEARN, RecallMode.OBSERVE, RecallMode.REPLAY);
        }
    }

    @Nested
    @DisplayName("ScoringMode enum")
    class ScoringModeTests {
        @Test
        @DisplayName("has all values")
        void allValues() {
            assertThat(ScoringMode.values()).hasSizeGreaterThanOrEqualTo(1);
        }
    }

    @Nested
    @DisplayName("MemoryPersistenceMode enum")
    class MemoryPersistenceModeTests {
        @Test
        @DisplayName("has all values")
        void allValues() {
            assertThat(MemoryPersistenceMode.values()).hasSizeGreaterThanOrEqualTo(1);
        }
    }

    @Nested
    @DisplayName("TextSearchMode enum")
    class TextSearchModeTests {
        @Test
        @DisplayName("has all values")
        void allValues() {
            assertThat(TextSearchMode.values()).hasSizeGreaterThanOrEqualTo(1);
        }
    }
}
