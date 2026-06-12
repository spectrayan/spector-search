/*
 * Copyright 2026 Spectrayan
 */
package com.spectrayan.spector.node;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.spectrayan.spector.node.api.dto.*;

/**
 * Tests for all API DTO records in spector-node.
 */
@DisplayName("Node API DTOs")
class NodeApiDtoTest {

    // ══════════════════════════════════════════════════════════════
    // Response DTOs
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("IntrospectResponseDto")
    class IntrospectResponseTests {
        @Test void construction() {
            var dto = new IntrospectResponseDto(
                    "neural networks", true, 0.85f, 42, 6.5f, 0.3f, 15.2f,
                    0.1f, false, true, new String[]{"deep learning"}, 2.1f, "Review materials");
            assertThat(dto.query()).isEqualTo("neural networks");
            assertThat(dto.known()).isTrue();
            assertThat(dto.confidence()).isEqualTo(0.85f);
            assertThat(dto.totalMemories()).isEqualTo(42);
            assertThat(dto.hasGaps()).isTrue();
            assertThat(dto.gaps()).containsExactly("deep learning");
        }
    }

    @Nested
    @DisplayName("ReflectResponseDto")
    class ReflectResponseTests {
        @Test void construction() {
            var dto = new ReflectResponseDto(5, 2, 1, 3, 150L);
            assertThat(dto.consolidatedCount()).isEqualTo(5);
            assertThat(dto.tombstonedCount()).isEqualTo(2);
            assertThat(dto.compactedPartitions()).isEqualTo(1);
            assertThat(dto.temporalPrunedCount()).isEqualTo(3);
            assertThat(dto.durationMs()).isEqualTo(150L);
        }
    }

    @Nested
    @DisplayName("ReminderResponseDto")
    class ReminderResponseTests {
        @Test void construction() {
            var dto = new ReminderResponseDto("rem-1", "study HNSW", 3600, "2026-06-12T10:00:00Z", 5L);
            assertThat(dto.id()).isEqualTo("rem-1");
            assertThat(dto.text()).isEqualTo("study HNSW");
            assertThat(dto.delaySeconds()).isEqualTo(3600);
        }
    }

    @Nested
    @DisplayName("ScoreBreakdownDto")
    class ScoreBreakdownTests {
        @Test void construction() {
            var dto = new ScoreBreakdownDto(0.9f, 0.8f, 1.2f, 0.5f, 1.1f, 0.95f, 0.85f, "habituation");
            assertThat(dto.similarity()).isEqualTo(0.9f);
            assertThat(dto.importanceDecay()).isEqualTo(0.8f);
            assertThat(dto.finalScore()).isEqualTo(0.85f);
            assertThat(dto.weakestMultiplier()).isEqualTo("habituation");
        }
    }

    @Nested
    @DisplayName("WhyNotResponseDto")
    class WhyNotResponseTests {
        @Test void construction() {
            var breakdown = new ScoreBreakdownDto(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.15f, "similarity");
            var dto = new WhyNotResponseDto("mem-1", "too dissimilar", true, false, 0.7f, breakdown, "Memory scored too low");
            assertThat(dto.memoryId()).isEqualTo("mem-1");
            assertThat(dto.reason()).isEqualTo("too dissimilar");
            assertThat(dto.exists()).isTrue();
            assertThat(dto.suppressed()).isFalse();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Memory DTOs
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("MemoryRowDto")
    class MemoryRowTests {
        @Test void construction() {
            var dto = new MemoryRowDto(
                    "mem-1", "HNSW is an algorithm...", "SEMANTIC", "user-input",
                    7.5f, 3, System.currentTimeMillis(), 5, false, true, false, false,
                    new String[]{"algorithms", "search"});
            assertThat(dto.id()).isEqualTo("mem-1");
            assertThat(dto.tier()).isEqualTo("SEMANTIC");
            assertThat(dto.importance()).isEqualTo(7.5f);
            assertThat(dto.pinned()).isTrue();
            assertThat(dto.tags()).containsExactly("algorithms", "search");
        }
    }

    @Nested
    @DisplayName("MemoryTableDto")
    class MemoryTableTests {
        @Test void construction() {
            var row = new MemoryRowDto("m-1", "text", "SEMANTIC", "src", 5f, 0, 0L, 0, false, false, false, false, new String[0]);
            var dto = new MemoryTableDto(List.of(row), 100, 1, 20, Map.of("SEMANTIC", 80), Map.of("SEMANTIC", 0.05f));
            assertThat(dto.rows()).hasSize(1);
            assertThat(dto.totalCount()).isEqualTo(100);
            assertThat(dto.page()).isEqualTo(1);
            assertThat(dto.tierCounts()).containsKey("SEMANTIC");
        }
    }

    @Nested
    @DisplayName("Graph DTOs")
    class GraphDtoTests {
        @Test void graphNodeDto() {
            var dto = new GraphNodeDto("mem-1", "SEMANTIC", "HNSW algo...", 7.5f, 3);
            assertThat(dto.id()).isEqualTo("mem-1");
            assertThat(dto.tier()).isEqualTo("SEMANTIC");
        }

        @Test void graphEdgeDto() {
            var dto = new GraphEdgeDto("mem-1", "mem-2", "HEBBIAN", "co-activation", 0.8f);
            assertThat(dto.fromId()).isEqualTo("mem-1");
            assertThat(dto.toId()).isEqualTo("mem-2");
            assertThat(dto.type()).isEqualTo("HEBBIAN");
            assertThat(dto.weight()).isEqualTo(0.8f);
        }

        @Test void memoryGraphDto() {
            var node = new GraphNodeDto("m-1", "SEMANTIC", "text", 5f, 0);
            var edge = new GraphEdgeDto("m-1", "m-2", "TEMPORAL", "sequence", 1.0f);
            var dto = new MemoryGraphDto("m-1", List.of(node), List.of(edge));
            assertThat(dto.memoryId()).isEqualTo("m-1");
            assertThat(dto.nodes()).hasSize(1);
            assertThat(dto.edges()).hasSize(1);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Search DTO
    // ══════════════════════════════════════════════════════════════

    @Test @DisplayName("SearchResponseDto with mode")
    void searchResponseDto() {
        var sr = new SearchResponseDto(List.of(), 0, 5L, "VECTOR");
        assertThat(sr.results()).isEmpty();
        assertThat(sr.mode()).isEqualTo("VECTOR");
    }
}
