/*
 * Copyright 2026 Spectrayan
 */
package com.spectrayan.spector.embed;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.spectrayan.spector.commons.error.SpectorValidationException;

/**
 * Tests for embed-api records: EmbeddingResult, EmbedConfig, PipelineEmbeddingResult,
 * and ParallelEmbeddingPipeline.
 */
@DisplayName("Embed API")
class EmbedApiTest {

    // ══════════════════════════════════════════════════════════════
    // EmbeddingResult
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("EmbeddingResult")
    class EmbeddingResultTests {

        @Test
        @DisplayName("full construction")
        void fullConstruction() {
            float[] vec = {0.1f, 0.2f, 0.3f};
            var r = new EmbeddingResult(vec, 42, "test-model");
            assertThat(r.vector()).containsExactly(0.1f, 0.2f, 0.3f);
            assertThat(r.tokenCount()).isEqualTo(42);
            assertThat(r.model()).isEqualTo("test-model");
        }

        @Test
        @DisplayName("of() sets tokenCount to -1")
        void ofFactory() {
            float[] vec = {1.0f, 2.0f};
            var r = EmbeddingResult.of(vec, "nomic");
            assertThat(r.tokenCount()).isEqualTo(-1);
            assertThat(r.model()).isEqualTo("nomic");
        }

        @Test
        @DisplayName("dimensions returns vector length")
        void dimensions() {
            var r = EmbeddingResult.of(new float[384], "m");
            assertThat(r.dimensions()).isEqualTo(384);
        }

        @Test
        @DisplayName("empty vector has 0 dimensions")
        void emptyVector() {
            var r = EmbeddingResult.of(new float[0], "m");
            assertThat(r.dimensions()).isZero();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // EmbedConfig
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("EmbedConfig")
    class EmbedConfigTests {

        @Test
        @DisplayName("DEFAULT has sensible values")
        void defaultValues() {
            assertThat(EmbedConfig.DEFAULT.batchSize()).isEqualTo(32);
            assertThat(EmbedConfig.DEFAULT.maxRetries()).isEqualTo(3);
        }

        @Test
        @DisplayName("valid construction")
        void validConstruction() {
            var cfg = new EmbedConfig(64, 5);
            assertThat(cfg.batchSize()).isEqualTo(64);
            assertThat(cfg.maxRetries()).isEqualTo(5);
        }

        @Test
        @DisplayName("rejects batchSize <= 0")
        void rejectsZeroBatchSize() {
            assertThatThrownBy(() -> new EmbedConfig(0, 3))
                    .isInstanceOf(SpectorValidationException.class);
        }

        @Test
        @DisplayName("rejects negative batchSize")
        void rejectsNegativeBatchSize() {
            assertThatThrownBy(() -> new EmbedConfig(-1, 3))
                    .isInstanceOf(SpectorValidationException.class);
        }

        @Test
        @DisplayName("rejects negative maxRetries")
        void rejectsNegativeRetries() {
            assertThatThrownBy(() -> new EmbedConfig(32, -1))
                    .isInstanceOf(SpectorValidationException.class);
        }

        @Test
        @DisplayName("accepts zero maxRetries")
        void acceptsZeroRetries() {
            var cfg = new EmbedConfig(32, 0);
            assertThat(cfg.maxRetries()).isZero();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // PipelineEmbeddingResult
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("PipelineEmbeddingResult")
    class PipelineEmbeddingResultTests {

        @Test
        @DisplayName("success result has embedding and no error")
        void successResult() {
            float[] vec = {1.0f, 2.0f};
            var r = PipelineEmbeddingResult.success(0, vec);
            assertThat(r.success()).isTrue();
            assertThat(r.embedding()).containsExactly(1.0f, 2.0f);
            assertThat(r.error()).isNull();
            assertThat(r.chunkIndex()).isZero();
        }

        @Test
        @DisplayName("failure result has error and no embedding")
        void failureResult() {
            var r = PipelineEmbeddingResult.failure(5, "Connection refused");
            assertThat(r.success()).isFalse();
            assertThat(r.embedding()).isNull();
            assertThat(r.error()).isEqualTo("Connection refused");
            assertThat(r.chunkIndex()).isEqualTo(5);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ParallelEmbeddingPipeline
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ParallelEmbeddingPipeline")
    class ParallelPipelineTests {

        @Test
        @DisplayName("rejects null provider")
        void rejectsNullProvider() {
            assertThatThrownBy(() -> new ParallelEmbeddingPipeline(null))
                    .isInstanceOf(SpectorValidationException.class);
        }

        @Test
        @DisplayName("empty input returns empty output")
        void emptyInput() {
            var provider = createStubProvider(new float[]{1.0f});
            var pipeline = new ParallelEmbeddingPipeline(provider);
            var results = pipeline.embed(List.of(), EmbedConfig.DEFAULT);
            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("null input returns empty output")
        void nullInput() {
            var provider = createStubProvider(new float[]{1.0f});
            var pipeline = new ParallelEmbeddingPipeline(provider);
            var results = pipeline.embed(null, EmbedConfig.DEFAULT);
            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("embeds single text")
        void embedsSingle() {
            var provider = createStubProvider(new float[]{0.5f, 0.5f});
            var pipeline = new ParallelEmbeddingPipeline(provider);
            var results = pipeline.embed(List.of("hello"), EmbedConfig.DEFAULT);
            assertThat(results).hasSize(1);
            assertThat(results.get(0).success()).isTrue();
            assertThat(results.get(0).embedding()).containsExactly(0.5f, 0.5f);
        }

        @Test
        @DisplayName("embeds multiple texts preserving order")
        void embedsMultiple() {
            var provider = createStubProvider(new float[]{1.0f});
            var pipeline = new ParallelEmbeddingPipeline(provider);
            var results = pipeline.embed(List.of("a", "b", "c"), new EmbedConfig(2, 0));
            assertThat(results).hasSize(3);
            assertThat(results.get(0).chunkIndex()).isZero();
            assertThat(results.get(1).chunkIndex()).isEqualTo(1);
            assertThat(results.get(2).chunkIndex()).isEqualTo(2);
        }

        @Test
        @DisplayName("handles provider failure with retries exhausted")
        void handlesFailureWithRetries() {
            EmbeddingProvider failingProvider = new EmbeddingProvider() {
                @Override public EmbeddingResult embed(String text) { throw new RuntimeException("fail"); }
                @Override public List<EmbeddingResult> embedBatch(List<String> texts) { throw new RuntimeException("fail"); }
                @Override public int dimensions() { return 1; }
                @Override public String modelName() { return "test"; }
            };
            var pipeline = new ParallelEmbeddingPipeline(failingProvider);
            var results = pipeline.embed(List.of("text"), new EmbedConfig(1, 2));
            assertThat(results).hasSize(1);
            assertThat(results.get(0).success()).isFalse();
            assertThat(results.get(0).error()).contains("retries exhausted");
        }
    }

    // ── Helper ──

    private EmbeddingProvider createStubProvider(float[] vector) {
        return new EmbeddingProvider() {
            @Override public EmbeddingResult embed(String text) { return EmbeddingResult.of(vector, "stub"); }
            @Override public List<EmbeddingResult> embedBatch(List<String> texts) {
                return texts.stream().map(t -> EmbeddingResult.of(vector, "stub")).toList();
            }
            @Override public int dimensions() { return vector.length; }
            @Override public String modelName() { return "stub"; }
        };
    }
}
