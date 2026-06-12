/*
 * Copyright 2026 Spectrayan
 */
package com.spectrayan.spector.query;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.spectrayan.spector.index.ScoredResult;
import com.spectrayan.spector.index.VectorIndex;
import com.spectrayan.spector.index.KeywordIndex;
import com.spectrayan.spector.query.ranking.Reranker;
import com.spectrayan.spector.storage.DocumentStore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Extended tests for {@link HybridSearchOrchestrator} — keyword, vector,
 * hybrid search modes, re-ranking, edge cases.
 */
@DisplayName("HybridSearchOrchestrator — Extended")
class HybridSearchOrchestratorExtendedTest {

    private ScoredResult sr(String id, int idx, float score) {
        return new ScoredResult(id, idx, score);
    }

    // ══════════════════════════════════════════════════════════════
    // Keyword-only search
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("KEYWORD mode")
    class KeywordModeTests {

        @Test
        @DisplayName("keyword search delegates to keyword index")
        void keywordSearch() {
            var kwIndex = mock(KeywordIndex.class);
            when(kwIndex.search("hello", 5)).thenReturn(new ScoredResult[]{sr("a", 0, 1.0f)});

            try (var orch = new HybridSearchOrchestrator(kwIndex, null)) {
                var resp = orch.search(SearchQuery.keyword("hello", 5));
                assertThat(resp.mode()).isEqualTo(SearchQuery.SearchMode.KEYWORD);
                assertThat(resp.size()).isEqualTo(1);
                assertThat(resp.results()[0].id()).isEqualTo("a");
            }
        }

        @Test
        @DisplayName("keyword search with null keyword index returns empty")
        void nullKeywordIndex() {
            try (var orch = new HybridSearchOrchestrator(null, null)) {
                var resp = orch.search(SearchQuery.keyword("hello", 5));
                assertThat(resp.size()).isZero();
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Vector-only search
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("VECTOR mode")
    class VectorModeTests {

        @Test
        @DisplayName("vector search delegates to vector index")
        void vectorSearch() {
            var vecIndex = mock(VectorIndex.class);
            float[] query = {0.1f, 0.2f};
            when(vecIndex.search(query, 10)).thenReturn(new ScoredResult[]{
                    sr("b", 1, 0.9f), sr("c", 2, 0.8f)
            });

            try (var orch = new HybridSearchOrchestrator(null, vecIndex)) {
                var resp = orch.search(SearchQuery.vector(query, 10));
                assertThat(resp.mode()).isEqualTo(SearchQuery.SearchMode.VECTOR);
                assertThat(resp.size()).isEqualTo(2);
            }
        }

        @Test
        @DisplayName("vector search with null vector index returns empty")
        void nullVectorIndex() {
            try (var orch = new HybridSearchOrchestrator(null, null)) {
                var resp = orch.search(SearchQuery.vector(new float[]{1f}, 5));
                assertThat(resp.size()).isZero();
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Hybrid search
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("HYBRID mode")
    class HybridModeTests {

        @Test
        @DisplayName("hybrid falls back to keyword-only when no vector index")
        void hybridFallsBackToKeyword() {
            var kwIndex = mock(KeywordIndex.class);
            when(kwIndex.search(anyString(), anyInt())).thenReturn(
                    new ScoredResult[]{sr("a", 0, 1.0f)});

            try (var orch = new HybridSearchOrchestrator(kwIndex, null)) {
                var resp = orch.search(SearchQuery.hybrid("hello", new float[]{1f}, 5));
                assertThat(resp.size()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("hybrid falls back to vector-only when no keyword index")
        void hybridFallsBackToVector() {
            var vecIndex = mock(VectorIndex.class);
            float[] qv = {0.1f};
            when(vecIndex.search(qv, 5)).thenReturn(new ScoredResult[]{sr("b", 0, 0.9f)});

            try (var orch = new HybridSearchOrchestrator(null, vecIndex)) {
                var resp = orch.search(SearchQuery.hybrid("hello", qv, 5));
                assertThat(resp.size()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("hybrid with no indexes returns empty")
        void hybridNoIndexes() {
            try (var orch = new HybridSearchOrchestrator(null, null)) {
                var resp = orch.search(SearchQuery.hybrid("hello", new float[]{1f}, 5));
                assertThat(resp.size()).isZero();
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Response metadata
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("response includes query time")
    void responseIncludesTime() {
        try (var orch = new HybridSearchOrchestrator(null, null)) {
            var resp = orch.search(SearchQuery.keyword("test", 5));
            assertThat(resp.queryTimeMs()).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    @DisplayName("close is idempotent")
    void closeIdempotent() {
        var orch = new HybridSearchOrchestrator(null, null);
        assertThatCode(() -> {
            orch.close();
            orch.close();
        }).doesNotThrowAnyException();
    }
}
