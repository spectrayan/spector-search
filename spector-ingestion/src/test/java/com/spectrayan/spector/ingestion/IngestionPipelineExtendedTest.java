/*
 * Copyright 2026 Spectrayan
 */
package com.spectrayan.spector.ingestion;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.spectrayan.spector.commons.TextChunker;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.embed.EmbeddingProvider;
import com.spectrayan.spector.embed.EmbeddingResult;

/**
 * Extended tests for {@link IngestionPipeline}.
 */
@DisplayName("IngestionPipeline — Extended Coverage")
class IngestionPipelineExtendedTest {

    private IngestionTarget mockTarget;
    private EmbeddingProvider mockProvider;

    @BeforeEach
    void setUp() {
        mockTarget = mock(IngestionTarget.class);
        mockProvider = mock(EmbeddingProvider.class);
    }

    private EmbeddingResult stubEmbedding(int dims) {
        return EmbeddingResult.of(new float[dims], "test-model");
    }

    // ══════════════════════════════════════════════════════════════
    // Builder
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("rejects null target")
        void rejectsNullTarget() {
            assertThatThrownBy(() -> IngestionPipeline.builder().build())
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("builds with target only (no embedder)")
        void buildsWithTargetOnly() {
            var pipeline = IngestionPipeline.builder()
                    .target(mockTarget)
                    .build();
            assertThat(pipeline.hasEmbeddingProvider()).isFalse();
            assertThat(pipeline.chunker()).isNull();
        }

        @Test
        @DisplayName("builds with all options")
        void buildsWithAllOptions() {
            var pipeline = IngestionPipeline.builder()
                    .target(mockTarget)
                    .embeddingProvider(mockProvider)
                    .chunking(new TextChunker(500, 50))
                    .chunkThreshold(500)
                    .build();
            assertThat(pipeline.hasEmbeddingProvider()).isTrue();
            assertThat(pipeline.chunker()).isNotNull();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Pre-embedded ingest
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("pre-embedded ingest")
    class PreEmbeddedTests {

        @Test
        @DisplayName("ingest with pre-computed vector succeeds")
        void preEmbeddedIngest() {
            var pipeline = IngestionPipeline.builder()
                    .target(mockTarget)
                    .build();

            float[] vector = {0.1f, 0.2f, 0.3f};
            var result = pipeline.ingest("doc-1", "Hello world", vector);

            assertThat(result.documentId()).isEqualTo("doc-1");
            assertThat(result.chunksStored()).isEqualTo(1);
            verify(mockTarget).ingest("doc-1", "Hello world", vector);
        }

        @Test
        @DisplayName("pre-embedded ingest returns timing >= 0")
        void preEmbeddedTiming() {
            var pipeline = IngestionPipeline.builder()
                    .target(mockTarget)
                    .build();

            var result = pipeline.ingest("doc-1", "text", new float[]{1f});
            assertThat(result.durationMs()).isGreaterThanOrEqualTo(0);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Direct ingest
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("direct ingest")
    class DirectIngestTests {

        @Test
        @DisplayName("short content ingests directly without chunking")
        void directIngest() {
            when(mockProvider.embed(anyString())).thenReturn(stubEmbedding(3));

            var pipeline = IngestionPipeline.builder()
                    .target(mockTarget)
                    .embeddingProvider(mockProvider)
                    .chunking(new TextChunker(500, 50))
                    .chunkThreshold(500)
                    .build();

            var result = pipeline.ingest("doc-1", "Short text");

            assertThat(result.documentId()).isEqualTo("doc-1");
            assertThat(result.chunksStored()).isEqualTo(1);
            verify(mockTarget).ingest(eq("doc-1"), eq("Short text"), any(float[].class));
            verify(mockTarget).storeParentMetadata("doc-1", 1);
        }

        @Test
        @DisplayName("ingest without embedder throws validation exception")
        void noEmbedderThrows() {
            var pipeline = IngestionPipeline.builder()
                    .target(mockTarget)
                    .build();

            assertThatThrownBy(() -> pipeline.ingest("doc-1", "text"))
                    .isInstanceOf(SpectorValidationException.class);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Chunked ingest
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("shouldChunk behavior")
    class ChunkingDecisionTests {

        @Test
        @DisplayName("long content without chunker falls through to direct path")
        void noChukerDirectPath() {
            when(mockProvider.embed(anyString())).thenReturn(stubEmbedding(3));

            // No chunker configured → always direct path regardless of length
            var pipeline = IngestionPipeline.builder()
                    .target(mockTarget)
                    .embeddingProvider(mockProvider)
                    .build();

            String longContent = "x".repeat(5000);
            var result = pipeline.ingest("doc-long", longContent);

            assertThat(result.documentId()).isEqualTo("doc-long");
            assertThat(result.chunksStored()).isEqualTo(1);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // EmbeddingProviderFactory
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("factory creates embedding provider")
    void factoryCreatesProvider() {
        // Ollama classes are on the test classpath via spector-embed-ollama.
        // The factory should create the provider successfully (even though
        // it won't connect to a real server).
        var provider = EmbeddingProviderFactory.create("http://localhost:11434", "test-model");
        assertThat(provider).isNotNull();
    }

    // ══════════════════════════════════════════════════════════════
    // IngestionTarget default methods
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("IngestionTarget defaults")
    class TargetDefaultTests {

        @Test
        @DisplayName("storeParentMetadata default is no-op")
        void storeParentMetadataNoOp() {
            IngestionTarget target = (id, text, vector) -> {};
            assertThatCode(() -> target.storeParentMetadata("doc", 5))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("onBatchComplete default is no-op")
        void onBatchCompleteNoOp() {
            IngestionTarget target = (id, text, vector) -> {};
            assertThatCode(target::onBatchComplete)
                    .doesNotThrowAnyException();
        }
    }
}
