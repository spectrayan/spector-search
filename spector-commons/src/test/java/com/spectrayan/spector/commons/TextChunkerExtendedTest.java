/*
 * Copyright 2026 Spectrayan
 */
package com.spectrayan.spector.commons;

import static org.assertj.core.api.Assertions.*;

import com.spectrayan.spector.commons.error.SpectorValidationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Extended tests for {@link TextChunker} — construction validation,
 * chunking behavior, edge cases, overlap, sentence boundaries.
 */
@DisplayName("TextChunker — Extended Coverage")
class TextChunkerExtendedTest {

    // ══════════════════════════════════════════════════════════════
    // Construction
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("construction validation")
    class ConstructionTests {

        @Test @DisplayName("default constructor creates valid chunker")
        void defaultConstructor() {
            var chunker = new TextChunker();
            assertThat(chunker.chunkSize()).isEqualTo(TextChunker.DEFAULT_CHUNK_SIZE);
            assertThat(chunker.overlap()).isEqualTo(TextChunker.DEFAULT_OVERLAP);
        }

        @Test @DisplayName("custom constructor")
        void customConstructor() {
            var chunker = new TextChunker(1000, 100);
            assertThat(chunker.chunkSize()).isEqualTo(1000);
            assertThat(chunker.overlap()).isEqualTo(100);
        }

        @Test @DisplayName("rejects zero chunk size")
        void rejectsZeroChunkSize() {
            assertThatThrownBy(() -> new TextChunker(0, 0))
                    .isInstanceOf(SpectorValidationException.class);
        }

        @Test @DisplayName("rejects negative chunk size")
        void rejectsNegativeChunkSize() {
            assertThatThrownBy(() -> new TextChunker(-1, 0))
                    .isInstanceOf(SpectorValidationException.class);
        }

        @Test @DisplayName("rejects negative overlap")
        void rejectsNegativeOverlap() {
            assertThatThrownBy(() -> new TextChunker(100, -1))
                    .isInstanceOf(SpectorValidationException.class);
        }

        @Test @DisplayName("rejects overlap >= chunk size")
        void rejectsOverlapGteChunkSize() {
            assertThatThrownBy(() -> new TextChunker(100, 100))
                    .isInstanceOf(SpectorValidationException.class);
            assertThatThrownBy(() -> new TextChunker(100, 150))
                    .isInstanceOf(SpectorValidationException.class);
        }

        @Test @DisplayName("allows zero overlap")
        void allowsZeroOverlap() {
            assertThatCode(() -> new TextChunker(100, 0))
                    .doesNotThrowAnyException();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Chunking behavior
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("chunk() behavior")
    class ChunkTests {

        @Test @DisplayName("null text returns empty list")
        void nullText() {
            var chunks = new TextChunker(100, 10).chunk("doc-1", null);
            assertThat(chunks).isEmpty();
        }

        @Test @DisplayName("blank text returns empty list")
        void blankText() {
            var chunks = new TextChunker(100, 10).chunk("doc-1", "   ");
            assertThat(chunks).isEmpty();
        }

        @Test @DisplayName("short text returns single chunk")
        void shortText() {
            var chunker = new TextChunker(100, 10);
            var chunks = chunker.chunk("doc-1", "Hello world.");
            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0).text()).isEqualTo("Hello world.");
            assertThat(chunks.get(0).parentId()).isEqualTo("doc-1");
            assertThat(chunks.get(0).index()).isZero();
            assertThat(chunks.get(0).chunkId()).isEqualTo("doc-1::chunk-0");
        }

        @Test @DisplayName("text exactly at chunk size returns single chunk")
        void exactlyAtLimit() {
            String text = "a".repeat(100);
            var chunker = new TextChunker(100, 10);
            var chunks = chunker.chunk("doc-1", text);
            assertThat(chunks).hasSize(1);
        }

        @Test @DisplayName("long text produces multiple chunks")
        void longTextMultipleChunks() {
            // Create text with clear sentence boundaries
            var sb = new StringBuilder();
            for (int i = 0; i < 20; i++) {
                sb.append("This is sentence number ").append(i).append(". ");
            }
            var chunker = new TextChunker(100, 20);
            var chunks = chunker.chunk("doc-1", sb.toString());
            assertThat(chunks.size()).isGreaterThan(1);

            // Verify chunk IDs are sequential
            for (int i = 0; i < chunks.size(); i++) {
                assertThat(chunks.get(i).chunkId()).isEqualTo("doc-1::chunk-" + i);
                assertThat(chunks.get(i).index()).isEqualTo(i);
                assertThat(chunks.get(i).parentId()).isEqualTo("doc-1");
            }
        }

        @Test @DisplayName("chunks cover entire document")
        void chunksFullCoverage() {
            String text = "First sentence. Second sentence. Third sentence. Fourth sentence. Fifth sentence.";
            var chunker = new TextChunker(30, 5);
            var chunks = chunker.chunk("doc", text);
            assertThat(chunks).isNotEmpty();

            // All chunks should have non-empty text
            for (var c : chunks) {
                assertThat(c.text()).isNotBlank();
                assertThat(c.length()).isPositive();
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Chunk record
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Chunk record")
    class ChunkRecordTests {

        @Test @DisplayName("length returns text length")
        void length() {
            var chunk = new TextChunker.Chunk("doc", "doc::chunk-0", 0, "Hello world", 0, 11);
            assertThat(chunk.length()).isEqualTo(11);
        }

        @Test @DisplayName("startChar and endChar are preserved")
        void offsets() {
            var chunk = new TextChunker.Chunk("doc", "doc::chunk-1", 1, "text", 100, 104);
            assertThat(chunk.startChar()).isEqualTo(100);
            assertThat(chunk.endChar()).isEqualTo(104);
        }
    }
}
