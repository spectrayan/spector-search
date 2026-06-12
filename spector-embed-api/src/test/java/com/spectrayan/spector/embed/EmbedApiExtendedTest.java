/*
 * Copyright 2026 Spectrayan
 */
package com.spectrayan.spector.embed;

import static org.assertj.core.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.spectrayan.spector.embed.error.SpectorEmbeddingUnavailableException;
import com.spectrayan.spector.embed.error.SpectorEmbeddingTimeoutException;

/**
 * Tests for embed-api DTOs — EmbeddingResult, GenerationOptions,
 * SparseEncodingResult, TokenEmbeddingResult, PipelineEmbeddingResult.
 */
@DisplayName("Embed API DTOs")
class EmbedApiExtendedTest {

    @Nested
    @DisplayName("EmbeddingResult")
    class EmbeddingResultTests {

        @Test @DisplayName("of() factory with unknown token count")
        void factoryMethod() {
            var result = EmbeddingResult.of(new float[]{1f, 2f, 3f}, "nomic-embed-text");
            assertThat(result.vector()).hasSize(3);
            assertThat(result.tokenCount()).isEqualTo(-1);
            assertThat(result.model()).isEqualTo("nomic-embed-text");
        }

        @Test @DisplayName("dimensions() returns vector length")
        void dimensions() {
            var result = new EmbeddingResult(new float[384], 50, "model");
            assertThat(result.dimensions()).isEqualTo(384);
        }

        @Test @DisplayName("full constructor preserves all fields")
        void fullConstructor() {
            var result = new EmbeddingResult(new float[768], 100, "bge-large");
            assertThat(result.tokenCount()).isEqualTo(100);
            assertThat(result.model()).isEqualTo("bge-large");
        }
    }

    @Nested
    @DisplayName("GenerationOptions")
    class GenerationOptionsTests {

        @Test @DisplayName("DEFAULT preset")
        void defaultPreset() {
            assertThat(GenerationOptions.DEFAULT.temperature()).isEqualTo(0.1f);
            assertThat(GenerationOptions.DEFAULT.maxTokens()).isEqualTo(512);
            assertThat(GenerationOptions.DEFAULT.topP()).isEqualTo(0.9f);
        }

        @Test @DisplayName("CREATIVE preset")
        void creativePreset() {
            assertThat(GenerationOptions.CREATIVE.temperature()).isEqualTo(0.7f);
            assertThat(GenerationOptions.CREATIVE.maxTokens()).isEqualTo(1024);
        }

        @Test @DisplayName("CONCISE preset")
        void concisePreset() {
            assertThat(GenerationOptions.CONCISE.maxTokens()).isEqualTo(256);
        }

        @Test @DisplayName("Builder creates custom options")
        void builder() {
            var opts = GenerationOptions.builder()
                    .temperature(0.5f)
                    .maxTokens(2048)
                    .topP(0.95f)
                    .stopSequences("</s>", "[END]")
                    .build();
            assertThat(opts.temperature()).isEqualTo(0.5f);
            assertThat(opts.maxTokens()).isEqualTo(2048);
            assertThat(opts.topP()).isEqualTo(0.95f);
            assertThat(opts.stopSequences()).containsExactly("</s>", "[END]");
        }

        @Test @DisplayName("Builder defaults")
        void builderDefaults() {
            var opts = GenerationOptions.builder().build();
            assertThat(opts.temperature()).isEqualTo(0.1f);
            assertThat(opts.maxTokens()).isEqualTo(512);
        }
    }

    @Nested
    @DisplayName("SparseEncodingResult")
    class SparseEncodingTests {

        @Test @DisplayName("nonZeroCount returns map size")
        void nonZeroCount() {
            var result = new SparseEncodingResult(
                    Map.of("memory", 2.3f, "consolidation", 1.8f, "sleep", 2.1f),
                    15, "splade-v2");
            assertThat(result.nonZeroCount()).isEqualTo(3);
        }

        @Test @DisplayName("l1Norm sums all weights")
        void l1Norm() {
            var result = new SparseEncodingResult(
                    Map.of("a", 1.0f, "b", 2.0f, "c", 3.0f), 10, "model");
            assertThat(result.l1Norm()).isEqualTo(6.0f);
        }

        @Test @DisplayName("empty weights")
        void emptyWeights() {
            var result = new SparseEncodingResult(Map.of(), 0, "model");
            assertThat(result.nonZeroCount()).isZero();
            assertThat(result.l1Norm()).isZero();
        }
    }

    @Nested
    @DisplayName("TokenEmbeddingResult")
    class TokenEmbeddingTests {

        @Test @DisplayName("tokenEmbedding returns correct vector")
        void tokenEmbedding() {
            float[][] embeddings = {{1f, 2f}, {3f, 4f}};
            var result = new TokenEmbeddingResult(embeddings, new String[]{"hello", "world"}, 2, "colbert");
            assertThat(result.tokenEmbedding(0)).containsExactly(1f, 2f);
            assertThat(result.tokenEmbedding(1)).containsExactly(3f, 4f);
        }

        @Test @DisplayName("dimensions returns per-token dimension")
        void dimensions() {
            float[][] embeddings = {{1f, 2f, 3f}, {4f, 5f, 6f}};
            var result = new TokenEmbeddingResult(embeddings, new String[]{"a", "b"}, 2, "model");
            assertThat(result.dimensions()).isEqualTo(3);
        }

        @Test @DisplayName("dimensions returns 0 for empty embeddings")
        void emptyDimensions() {
            var result = new TokenEmbeddingResult(new float[0][], new String[0], 0, "model");
            assertThat(result.dimensions()).isZero();
        }

        @Test @DisplayName("out of bounds token index throws")
        void outOfBounds() {
            float[][] embeddings = {{1f}};
            var result = new TokenEmbeddingResult(embeddings, new String[]{"a"}, 1, "model");
            assertThatThrownBy(() -> result.tokenEmbedding(5))
                    .isInstanceOf(ArrayIndexOutOfBoundsException.class);
        }
    }

    @Nested
    @DisplayName("PipelineEmbeddingResult")
    class PipelineEmbeddingTests {

        @Test @DisplayName("success factory creates successful result")
        void successFactory() {
            var result = PipelineEmbeddingResult.success(0, new float[]{1f, 2f});
            assertThat(result.success()).isTrue();
            assertThat(result.embedding()).hasSize(2);
            assertThat(result.error()).isNull();
            assertThat(result.chunkIndex()).isZero();
        }

        @Test @DisplayName("failure factory creates failed result")
        void failureFactory() {
            var result = PipelineEmbeddingResult.failure(3, "timeout");
            assertThat(result.success()).isFalse();
            assertThat(result.embedding()).isNull();
            assertThat(result.error()).isEqualTo("timeout");
            assertThat(result.chunkIndex()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("Exception classes")
    class EmbedExceptionTests {

        @Test @DisplayName("SpectorEmbeddingUnavailableException with provider")
        void unavailable() {
            var ex = new SpectorEmbeddingUnavailableException("ollama");
            assertThat(ex.getMessage()).contains("ollama");
        }

        @Test @DisplayName("SpectorEmbeddingUnavailableException with cause")
        void unavailableWithCause() {
            var cause = new RuntimeException("connection refused");
            var ex = new SpectorEmbeddingUnavailableException("ollama", cause);
            assertThat(ex.getCause()).isEqualTo(cause);
        }

        @Test @DisplayName("SpectorEmbeddingTimeoutException")
        void timeout() {
            var ex = new SpectorEmbeddingTimeoutException(30000L);
            assertThat(ex.getMessage()).contains("30000");
        }

        @Test @DisplayName("SpectorEmbeddingTimeoutException with cause")
        void timeoutWithCause() {
            var cause = new java.util.concurrent.TimeoutException("timed out");
            var ex = new SpectorEmbeddingTimeoutException(5000L, cause);
            assertThat(ex.getCause()).isEqualTo(cause);
        }
    }
}
