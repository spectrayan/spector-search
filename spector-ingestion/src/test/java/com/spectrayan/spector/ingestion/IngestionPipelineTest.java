/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.spectrayan.spector.ingestion;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.spectrayan.spector.commons.TextChunker;
import com.spectrayan.spector.embed.EmbeddingProvider;
import com.spectrayan.spector.embed.EmbeddingResult;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.SpectorInternalException;

/**
 * Tests for {@link IngestionPipeline} — builder, direct ingest, chunked ingest,
 * pre-embedded ingest, and negative scenarios.
 */
@DisplayName("IngestionPipeline")
@ExtendWith(MockitoExtension.class)
class IngestionPipelineTest {

    @Mock
    private IngestionTarget mockTarget;

    @Mock
    private EmbeddingProvider mockEmbedder;

    private float[] sampleVector = {0.1f, 0.2f, 0.3f, 0.4f};

    @BeforeEach
    void setUp() {
        // Default: embedder returns sample vector for any text
        lenient().when(mockEmbedder.embed(anyString())).thenReturn(EmbeddingResult.of(sampleVector, "test-model"));
        // ParallelEmbeddingPipeline calls embedBatch() — Mockito doesn't invoke default methods
        lenient().when(mockEmbedder.embedBatch(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            return texts.stream().map(t -> EmbeddingResult.of(sampleVector, "test-model")).toList();
        });
    }

    // ══════════════════════════════════════════════════════════════
    // Builder
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("builder")
    class BuilderTests {

        @Test
        @DisplayName("builds pipeline with minimal config (target only)")
        void minimalConfig() {
            var pipeline = IngestionPipeline.builder()
                    .target(mockTarget)
                    .build();

            assertThat(pipeline).isNotNull();
            assertThat(pipeline.hasEmbeddingProvider()).isFalse();
            assertThat(pipeline.chunker()).isNull();
        }

        @Test
        @DisplayName("builds pipeline with full config")
        void fullConfig() {
            var pipeline = IngestionPipeline.builder()
                    .target(mockTarget)
                    .embeddingProvider(mockEmbedder)
                    .chunking(new TextChunker(500, 50))
                    .chunkThreshold(500)
                    .build();

            assertThat(pipeline.hasEmbeddingProvider()).isTrue();
            assertThat(pipeline.chunker()).isNotNull();
        }

        @Test
        @DisplayName("throws when target is null")
        void throwsOnNullTarget() {
            assertThatThrownBy(() -> IngestionPipeline.builder().build())
                    .isInstanceOf(SpectorInternalException.class);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Direct Ingest (text)
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("direct ingest")
    class DirectIngest {

        @Test
        @DisplayName("ingests short content directly without chunking")
        void ingestsShortContentDirectly() {
            var pipeline = IngestionPipeline.builder()
                    .target(mockTarget)
                    .embeddingProvider(mockEmbedder)
                    .chunking(new TextChunker(500, 50))
                    .chunkThreshold(500)
                    .build();

            var result = pipeline.ingest("doc-1", "short text");

            assertThat(result.documentId()).isEqualTo("doc-1");
            assertThat(result.chunksStored()).isEqualTo(1);
            assertThat(result.isFullSuccess()).isTrue();

            verify(mockEmbedder).embed("short text");
            verify(mockTarget).ingest(eq("doc-1"), eq("short text"), any(float[].class));
            verify(mockTarget).storeParentMetadata("doc-1", 1);
        }

        @Test
        @DisplayName("ingests without chunker regardless of length")
        void ingestsWithoutChunker() {
            var pipeline = IngestionPipeline.builder()
                    .target(mockTarget)
                    .embeddingProvider(mockEmbedder)
                    .build(); // no chunker!

            String longContent = "x".repeat(10_000);
            var result = pipeline.ingest("doc-2", longContent);

            assertThat(result.chunksStored()).isEqualTo(1);
            verify(mockEmbedder).embed(longContent);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Pre-embedded Ingest
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("pre-embedded ingest")
    class PreEmbeddedIngest {

        @Test
        @DisplayName("ingests with pre-computed vector, skipping embedding")
        void ingestsPreEmbedded() {
            var pipeline = IngestionPipeline.builder()
                    .target(mockTarget)
                    .build(); // no embedder needed!

            float[] vec = {1.0f, 2.0f, 3.0f};
            var result = pipeline.ingest("doc-pre", "some text", vec);

            assertThat(result.documentId()).isEqualTo("doc-pre");
            assertThat(result.chunksStored()).isEqualTo(1);
            assertThat(result.isFullSuccess()).isTrue();

            verify(mockTarget).ingest("doc-pre", "some text", vec);
            verifyNoInteractions(mockEmbedder);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Negative Scenarios
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("negative scenarios")
    class NegativeScenarios {

        @Test
        @DisplayName("throws when embedding provider not set and text ingest called")
        void throwsWithoutEmbedder() {
            var pipeline = IngestionPipeline.builder()
                    .target(mockTarget)
                    .build();

            assertThatThrownBy(() -> pipeline.ingest("doc-1", "text"))
                    .isInstanceOf(SpectorValidationException.class)
                    .hasMessageContaining("EmbeddingProvider");
        }

        @Test
        @DisplayName("handles embedding failure gracefully in chunked mode")
        void embeddingFailureInChunkedMode() {
            // Override the default lenient stub with a failing sequence
            lenient().when(mockEmbedder.embed(anyString()))
                    .thenReturn(EmbeddingResult.of(sampleVector, "test-model"))
                    .thenThrow(new RuntimeException("Ollama down"));

            var pipeline = IngestionPipeline.builder()
                    .target(mockTarget)
                    .embeddingProvider(mockEmbedder)
                    .chunking(new TextChunker(10, 2))
                    .chunkThreshold(10)
                    .build();

            // Content long enough to trigger chunking
            String content = "This is a long enough content that should trigger the text chunker to split it.";
            var result = pipeline.ingest("doc-fail", content);

            // At least some chunks should be stored, some may fail
            assertThat(result.documentId()).isEqualTo("doc-fail");
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Chunked Ingest
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("chunked ingest")
    class ChunkedIngest {

        @Test
        @DisplayName("chunks and ingests long content")
        void chunksLongContent() {
            var pipeline = IngestionPipeline.builder()
                    .target(mockTarget)
                    .embeddingProvider(mockEmbedder)
                    .chunking(new TextChunker(20, 5))
                    .chunkThreshold(20)
                    .build();

            // Build content clearly > 20 chars to trigger chunking
            // TextChunker operates on chars, so we need plenty
            String content = "The quick brown fox jumps over the lazy dog. ".repeat(10);
            var result = pipeline.ingest("doc-chunked", content);

            assertThat(result.documentId()).isEqualTo("doc-chunked");
            assertThat(result.chunksStored()).isGreaterThanOrEqualTo(1);
            verify(mockTarget, atLeastOnce()).ingest(anyString(), anyString(), any(float[].class));
        }
    }
}
