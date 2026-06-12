/*
 * Copyright 2026 Spectrayan
 */
package com.spectrayan.spector.query;

import static org.assertj.core.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.index.ScoredResult;

/**
 * Tests for query module: SearchQuery, SearchResponse, and ReciprocalRankFusion.
 */
@DisplayName("Query Module")
class QueryModuleTest {

    // ══════════════════════════════════════════════════════════════
    // SearchQuery
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("SearchQuery")
    class SearchQueryTests {

        @Test
        @DisplayName("keyword factory creates KEYWORD mode query")
        void keywordFactory() {
            var q = SearchQuery.keyword("hello world", 10);
            assertThat(q.text()).isEqualTo("hello world");
            assertThat(q.vector()).isNull();
            assertThat(q.mode()).isEqualTo(SearchQuery.SearchMode.KEYWORD);
            assertThat(q.topK()).isEqualTo(10);
            assertThat(q.metadata()).isEmpty();
        }

        @Test
        @DisplayName("vector factory creates VECTOR mode query")
        void vectorFactory() {
            float[] vec = {0.1f, 0.2f};
            var q = SearchQuery.vector(vec, 5);
            assertThat(q.text()).isNull();
            assertThat(q.vector()).containsExactly(0.1f, 0.2f);
            assertThat(q.mode()).isEqualTo(SearchQuery.SearchMode.VECTOR);
            assertThat(q.topK()).isEqualTo(5);
        }

        @Test
        @DisplayName("hybrid factory creates HYBRID mode query")
        void hybridFactory() {
            float[] vec = {1.0f};
            var q = SearchQuery.hybrid("query", vec, 20);
            assertThat(q.text()).isEqualTo("query");
            assertThat(q.vector()).containsExactly(1.0f);
            assertThat(q.mode()).isEqualTo(SearchQuery.SearchMode.HYBRID);
        }

        @Test
        @DisplayName("rejects topK <= 0")
        void rejectsInvalidTopK() {
            assertThatThrownBy(() -> SearchQuery.keyword("q", 0))
                    .isInstanceOf(SpectorValidationException.class);
            assertThatThrownBy(() -> SearchQuery.keyword("q", -1))
                    .isInstanceOf(SpectorValidationException.class);
        }

        @Test
        @DisplayName("null mode defaults to HYBRID")
        void nullModeDefaultsToHybrid() {
            var q = new SearchQuery("text", null, null, 5, null);
            assertThat(q.mode()).isEqualTo(SearchQuery.SearchMode.HYBRID);
        }

        @Test
        @DisplayName("null metadata defaults to empty map")
        void nullMetadataDefaultsToEmpty() {
            var q = SearchQuery.keyword("text", 5);
            assertThat(q.metadata()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("SearchMode enum has all values")
        void searchModeValues() {
            assertThat(SearchQuery.SearchMode.values()).hasSize(3);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // SearchResponse
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("SearchResponse")
    class SearchResponseTests {

        @Test
        @DisplayName("EMPTY response has zero results")
        void emptyResponse() {
            assertThat(SearchResponse.EMPTY.size()).isZero();
            assertThat(SearchResponse.EMPTY.totalHits()).isZero();
        }

        @Test
        @DisplayName("size returns array length")
        void sizeReturnsLength() {
            var results = new ScoredResult[]{
                    new ScoredResult("a", 0, 0.9f),
                    new ScoredResult("b", 1, 0.8f)
            };
            var resp = new SearchResponse(results, 100, 25, SearchQuery.SearchMode.HYBRID);
            assertThat(resp.size()).isEqualTo(2);
            assertThat(resp.totalHits()).isEqualTo(100);
            assertThat(resp.queryTimeMs()).isEqualTo(25);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ReciprocalRankFusion
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ReciprocalRankFusion")
    class RRFTests {

        @Test
        @DisplayName("fuses single list correctly")
        void fusesSingleList() {
            var list1 = new ScoredResult[]{
                    new ScoredResult("a", 0, 0.9f),
                    new ScoredResult("b", 1, 0.8f)
            };
            var fused = ReciprocalRankFusion.fuse(new ScoredResult[][]{list1}, 10);
            assertThat(fused).hasSize(2);
            // Rank 1 should have higher RRF score
            assertThat(fused[0].id()).isEqualTo("a");
        }

        @Test
        @DisplayName("fuses two lists — document in both ranks higher")
        void fusesOverlappingLists() {
            var list1 = new ScoredResult[]{
                    new ScoredResult("a", 0, 0.9f),
                    new ScoredResult("b", 1, 0.8f)
            };
            var list2 = new ScoredResult[]{
                    new ScoredResult("b", 1, 0.95f), // b ranked #1 here
                    new ScoredResult("c", 2, 0.7f)
            };
            var fused = ReciprocalRankFusion.fuse(new ScoredResult[][]{list1, list2}, 10);
            // "b" appears in both lists so should have highest RRF score
            assertThat(fused[0].id()).isEqualTo("b");
        }

        @Test
        @DisplayName("respects topK limit")
        void respectsTopK() {
            var list = new ScoredResult[]{
                    new ScoredResult("a", 0, 0.9f),
                    new ScoredResult("b", 1, 0.8f),
                    new ScoredResult("c", 2, 0.7f)
            };
            var fused = ReciprocalRankFusion.fuse(new ScoredResult[][]{list}, 2);
            assertThat(fused).hasSize(2);
        }

        @Test
        @DisplayName("empty result lists produce empty output")
        void emptyLists() {
            var fused = ReciprocalRankFusion.fuse(new ScoredResult[][]{new ScoredResult[0]}, 10);
            assertThat(fused).isEmpty();
        }

        @Test
        @DisplayName("custom k parameter works")
        void customK() {
            var list = new ScoredResult[]{
                    new ScoredResult("a", 0, 0.9f)
            };
            var fused = ReciprocalRankFusion.fuse(new ScoredResult[][]{list}, 10, 1);
            assertThat(fused).hasSize(1);
            // With k=1 and rank=1: score = 1/(1+1) = 0.5
            assertThat(fused[0].score()).isCloseTo(0.5f, within(0.001f));
        }
    }
}
