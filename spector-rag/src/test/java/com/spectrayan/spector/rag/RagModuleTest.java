/*
 * Copyright 2026 Spectrayan
 */
package com.spectrayan.spector.rag;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.spectrayan.spector.commons.TextChunk;
import com.spectrayan.spector.commons.error.SpectorValidationException;

/**
 * Tests for RAG module records — ScoredChunk, ChunkAttribution, ContextResult,
 * RagRequest, RagResponse, and ContextBuilder.
 */
@DisplayName("RAG Module")
class RagModuleTest {

    // ══════════════════════════════════════════════════════════════
    // ScoredChunk
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ScoredChunk")
    class ScoredChunkTests {

        @Test
        @DisplayName("construction with valid chunk and score")
        void validConstruction() {
            var chunk = new TextChunk("Hello world", 2, 0, 11, "doc-1");
            var sc = new ScoredChunk(chunk, 0.95f);
            assertThat(sc.chunk()).isEqualTo(chunk);
            assertThat(sc.score()).isEqualTo(0.95f);
        }

        @Test
        @DisplayName("rejects null chunk")
        void rejectsNullChunk() {
            assertThatThrownBy(() -> new ScoredChunk(null, 0.5f))
                    .isInstanceOf(SpectorValidationException.class);
        }

        @Test
        @DisplayName("rejects NaN score")
        void rejectsNanScore() {
            var chunk = new TextChunk("text", 1, 0, 4, "d");
            assertThatThrownBy(() -> new ScoredChunk(chunk, Float.NaN))
                    .isInstanceOf(SpectorValidationException.class);
        }

        @Test
        @DisplayName("accepts zero score")
        void acceptsZeroScore() {
            var chunk = new TextChunk("text", 1, 0, 4, "d");
            var sc = new ScoredChunk(chunk, 0.0f);
            assertThat(sc.score()).isZero();
        }

        @Test
        @DisplayName("accepts negative score")
        void acceptsNegativeScore() {
            var chunk = new TextChunk("text", 1, 0, 4, "d");
            var sc = new ScoredChunk(chunk, -1.0f);
            assertThat(sc.score()).isEqualTo(-1.0f);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ChunkAttribution
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ChunkAttribution")
    class ChunkAttributionTests {

        @Test
        @DisplayName("valid construction")
        void validConstruction() {
            var attr = new ChunkAttribution("doc-1", 5);
            assertThat(attr.documentId()).isEqualTo("doc-1");
            assertThat(attr.chunkOffset()).isEqualTo(5);
        }

        @Test
        @DisplayName("rejects null documentId")
        void rejectsNullDocId() {
            assertThatThrownBy(() -> new ChunkAttribution(null, 0))
                    .isInstanceOf(SpectorValidationException.class);
        }

        @Test
        @DisplayName("rejects blank documentId")
        void rejectsBlankDocId() {
            assertThatThrownBy(() -> new ChunkAttribution("  ", 0))
                    .isInstanceOf(SpectorValidationException.class);
        }

        @Test
        @DisplayName("rejects negative chunkOffset")
        void rejectsNegativeOffset() {
            assertThatThrownBy(() -> new ChunkAttribution("doc-1", -1))
                    .isInstanceOf(SpectorValidationException.class);
        }

        @Test
        @DisplayName("accepts zero chunkOffset")
        void acceptsZeroOffset() {
            var attr = new ChunkAttribution("doc-1", 0);
            assertThat(attr.chunkOffset()).isZero();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ContextResult
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ContextResult")
    class ContextResultTests {

        @Test
        @DisplayName("empty result")
        void emptyResult() {
            var result = ContextResult.empty();
            assertThat(result.isEmpty()).isTrue();
            assertThat(result.contextText()).isEmpty();
            assertThat(result.attributions()).isEmpty();
        }

        @Test
        @DisplayName("non-empty result")
        void nonEmptyResult() {
            var attrs = List.of(new ChunkAttribution("doc-1", 0));
            var result = new ContextResult("Some context text", attrs, false);
            assertThat(result.isEmpty()).isFalse();
            assertThat(result.contextText()).isEqualTo("Some context text");
            assertThat(result.attributions()).hasSize(1);
        }

        @Test
        @DisplayName("attributions are defensively copied")
        void attributionsDefensivelyCopied() {
            var mutableAttrs = new java.util.ArrayList<ChunkAttribution>();
            mutableAttrs.add(new ChunkAttribution("doc-1", 0));
            var result = new ContextResult("text", mutableAttrs, false);
            mutableAttrs.add(new ChunkAttribution("doc-2", 1));
            assertThat(result.attributions()).hasSize(1); // not 2
        }

        @Test
        @DisplayName("rejects null contextText")
        void rejectsNullContext() {
            assertThatThrownBy(() -> new ContextResult(null, List.of(), false))
                    .isInstanceOf(SpectorValidationException.class);
        }

        @Test
        @DisplayName("rejects null attributions")
        void rejectsNullAttributions() {
            assertThatThrownBy(() -> new ContextResult("text", null, false))
                    .isInstanceOf(SpectorValidationException.class);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // RagRequest
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("RagRequest")
    class RagRequestTests {

        @Test
        @DisplayName("query-only constructor sets null defaults")
        void queryOnlyConstructor() {
            var req = new RagRequest("test query");
            assertThat(req.query()).isEqualTo("test query");
            assertThat(req.topK()).isNull();
            assertThat(req.tokenLimit()).isNull();
            assertThat(req.searchMode()).isNull();
        }

        @Test
        @DisplayName("resolvedTopK defaults to 5")
        void resolvedTopKDefault() {
            assertThat(new RagRequest("q").resolvedTopK()).isEqualTo(5);
        }

        @Test
        @DisplayName("resolvedTopK clamps to [1, 100]")
        void resolvedTopKClamped() {
            assertThat(new RagRequest("q", 0, null, null).resolvedTopK()).isEqualTo(1);
            assertThat(new RagRequest("q", -10, null, null).resolvedTopK()).isEqualTo(1);
            assertThat(new RagRequest("q", 200, null, null).resolvedTopK()).isEqualTo(100);
            assertThat(new RagRequest("q", 50, null, null).resolvedTopK()).isEqualTo(50);
        }

        @Test
        @DisplayName("resolvedTokenLimit defaults to 4096")
        void resolvedTokenLimitDefault() {
            assertThat(new RagRequest("q").resolvedTokenLimit()).isEqualTo(4096);
        }

        @Test
        @DisplayName("resolvedTokenLimit clamps to [256, 131072]")
        void resolvedTokenLimitClamped() {
            assertThat(new RagRequest("q", null, 100, null).resolvedTokenLimit()).isEqualTo(256);
            assertThat(new RagRequest("q", null, 200_000, null).resolvedTokenLimit()).isEqualTo(131_072);
        }

        @Test
        @DisplayName("resolvedSearchMode defaults to vector")
        void resolvedSearchModeDefault() {
            assertThat(new RagRequest("q").resolvedSearchMode()).isEqualTo("vector");
        }

        @Test
        @DisplayName("resolvedSearchMode accepts hybrid")
        void resolvedSearchModeHybrid() {
            assertThat(new RagRequest("q", null, null, "HYBRID").resolvedSearchMode()).isEqualTo("hybrid");
        }

        @Test
        @DisplayName("resolvedSearchMode normalizes unknown to vector")
        void resolvedSearchModeUnknown() {
            assertThat(new RagRequest("q", null, null, "bm25").resolvedSearchMode()).isEqualTo("vector");
        }
    }

    // ══════════════════════════════════════════════════════════════
    // RagResponse
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("RagResponse")
    class RagResponseTests {

        @Test
        @DisplayName("empty response")
        void emptyResponse() {
            var resp = RagResponse.empty(150);
            assertThat(resp.contextText()).isEmpty();
            assertThat(resp.attributions()).isEmpty();
            assertThat(resp.message()).contains("No matching");
            assertThat(resp.queryTimeMs()).isEqualTo(150);
        }

        @Test
        @DisplayName("full response with attributions")
        void fullResponse() {
            var attrs = List.of(new RagResponse.Attribution("doc-1", 0));
            var resp = new RagResponse("Context here", attrs, null, 200);
            assertThat(resp.contextText()).isEqualTo("Context here");
            assertThat(resp.attributions()).hasSize(1);
            assertThat(resp.attributions().get(0).documentId()).isEqualTo("doc-1");
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ContextBuilder
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ContextBuilder")
    class ContextBuilderTests {

        private final ContextBuilder builder = new ContextBuilder();

        @Test
        @DisplayName("empty chunks returns empty result")
        void emptyChunks() {
            var result = builder.build(List.of(), 4096);
            assertThat(result.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("null chunks returns empty result")
        void nullChunks() {
            var result = builder.build(null, 4096);
            assertThat(result.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("single chunk fits within limit")
        void singleChunkFits() {
            var chunk = new TextChunk("Hello world", 2, 0, 11, "doc-1");
            var sc = new ScoredChunk(chunk, 0.9f);
            var result = builder.build(List.of(sc), 4096);
            assertThat(result.isEmpty()).isFalse();
            assertThat(result.contextText()).isEqualTo("Hello world");
            assertThat(result.attributions()).hasSize(1);
        }

        @Test
        @DisplayName("chunks ordered by descending score")
        void chunksOrderedByScore() {
            var c1 = new ScoredChunk(new TextChunk("Low", 1, 0, 3, "d"), 0.1f);
            var c2 = new ScoredChunk(new TextChunk("High", 1, 10, 14, "d"), 0.9f);
            var c3 = new ScoredChunk(new TextChunk("Mid", 1, 20, 23, "d"), 0.5f);
            var result = builder.build(List.of(c1, c2, c3), 4096);
            // Highest score first
            assertThat(result.contextText()).startsWith("High");
        }

        @Test
        @DisplayName("rejects token limit below 256")
        void rejectsLowTokenLimit() {
            assertThatThrownBy(() -> builder.build(List.of(), 100))
                    .isInstanceOf(SpectorValidationException.class);
        }

        @Test
        @DisplayName("rejects token limit above 131072")
        void rejectsHighTokenLimit() {
            assertThatThrownBy(() -> builder.build(List.of(), 200_000))
                    .isInstanceOf(SpectorValidationException.class);
        }
    }
}
