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
package com.spectrayan.spector.commons;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link TokenAwareChunker} and {@link ChunkConfig}.
 */
class TokenAwareChunkerTest {

    private final TokenAwareChunker chunker = new TokenAwareChunker();

    // ─────────────── ChunkConfig validation ───────────────

    @Test
    void configRejectsZeroMaxTokens() {
        assertThatThrownBy(() -> new ChunkConfig(0, 0))
                .isInstanceOf(com.spectrayan.spector.commons.error.SpectorValidationException.class)
                .hasMessageContaining("maxTokens");
    }

    @Test
    void configRejectsNegativeMaxTokens() {
        assertThatThrownBy(() -> new ChunkConfig(-1, 0))
                .isInstanceOf(com.spectrayan.spector.commons.error.SpectorValidationException.class);
    }

    @Test
    void configRejectsMaxTokensAbove8192() {
        assertThatThrownBy(() -> new ChunkConfig(8193, 0))
                .isInstanceOf(com.spectrayan.spector.commons.error.SpectorValidationException.class)
                .hasMessageContaining("8192");
    }

    @Test
    void configRejectsOverlapEqualToMaxTokens() {
        assertThatThrownBy(() -> new ChunkConfig(100, 100))
                .isInstanceOf(com.spectrayan.spector.commons.error.SpectorValidationException.class)
                .hasMessageContaining("overlap");
    }

    @Test
    void configRejectsNegativeOverlap() {
        assertThatThrownBy(() -> new ChunkConfig(100, -1))
                .isInstanceOf(com.spectrayan.spector.commons.error.SpectorValidationException.class);
    }

    @Test
    void configAcceptsValidBoundaryValues() {
        var config = new ChunkConfig(1, 0);
        assertThat(config.maxTokens()).isEqualTo(1);
        assertThat(config.overlapTokens()).isEqualTo(0);

        var config2 = new ChunkConfig(8192, 8191);
        assertThat(config2.maxTokens()).isEqualTo(8192);
        assertThat(config2.overlapTokens()).isEqualTo(8191);
    }

    @Test
    void configSingleArgConstructor() {
        var config = new ChunkConfig(256);
        assertThat(config.maxTokens()).isEqualTo(256);
        assertThat(config.overlapTokens()).isEqualTo(0);
    }

    // ─────────────── Null/whitespace input ───────────────

    @Test
    void nullInputReturnsEmptyList() {
        var config = new ChunkConfig(100, 10);
        assertThat(chunker.chunk(null, config)).isEmpty();
    }

    @Test
    void emptyStringReturnsEmptyList() {
        var config = new ChunkConfig(100, 10);
        assertThat(chunker.chunk("", config)).isEmpty();
    }

    @Test
    void whitespaceOnlyReturnsEmptyList() {
        var config = new ChunkConfig(100, 10);
        assertThat(chunker.chunk("   \t\n  ", config)).isEmpty();
    }

    // ─────────────── Short text single chunk ───────────────

    @Test
    void shortTextReturnsSingleChunk() {
        var config = new ChunkConfig(100, 10);
        String text = "Hello world, this is a short sentence.";
        List<TextChunk> chunks = chunker.chunk(text, config);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().text()).isEqualTo(text);
        assertThat(chunks.getFirst().startOffset()).isEqualTo(0);
        assertThat(chunks.getFirst().endOffset()).isEqualTo(text.length());
        assertThat(chunks.getFirst().tokenCount()).isEqualTo(WordTokenizer.countTokens(text));
    }

    @Test
    void textExactlyAtLimitReturnsSingleChunk() {
        // Build a text with exactly maxTokens tokens
        var config = new ChunkConfig(5, 0);
        String text = "one two three four five";
        int tokenCount = WordTokenizer.countTokens(text);
        assertThat(tokenCount).isEqualTo(5);

        List<TextChunk> chunks = chunker.chunk(text, config);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().text()).isEqualTo(text);
    }

    // ─────────────── Multi-chunk splitting ───────────────

    @Test
    void longTextProducesMultipleChunks() {
        var config = new ChunkConfig(10, 0);
        // Each sentence has ~9 tokens
        String text = "The quick brown fox jumps over the lazy dog. " +
                "Another sentence with several words in it. " +
                "Yet another sentence to make the text longer.";

        List<TextChunk> chunks = chunker.chunk(text, config);
        assertThat(chunks).hasSizeGreaterThan(1);

        // Every chunk must respect token limit
        for (TextChunk chunk : chunks) {
            assertThat(chunk.tokenCount()).isLessThanOrEqualTo(config.maxTokens());
        }
    }

    @Test
    void chunksDoNotExceedMaxTokens() {
        var config = new ChunkConfig(20, 5);
        String text = "The quick brown fox jumps over the lazy dog. ".repeat(20);

        List<TextChunk> chunks = chunker.chunk(text, config);
        for (TextChunk chunk : chunks) {
            int actualTokens = WordTokenizer.countTokens(chunk.text());
            assertThat(actualTokens).isLessThanOrEqualTo(config.maxTokens());
        }
    }

    // ─────────────── Oversized sentence splitting ───────────────

    @Test
    void oversizedSentenceSplitAtWordBoundaries() {
        var config = new ChunkConfig(5, 0);
        // A single sentence with many words (no period until the end)
        String text = "one two three four five six seven eight nine ten end.";

        List<TextChunk> chunks = chunker.chunk(text, config);
        assertThat(chunks).hasSizeGreaterThan(1);

        for (TextChunk chunk : chunks) {
            int tokenCount = WordTokenizer.countTokens(chunk.text());
            assertThat(tokenCount).isLessThanOrEqualTo(config.maxTokens());
        }
    }

    @Test
    void oversizedSentenceWithOverlap() {
        var config = new ChunkConfig(5, 2);
        String text = "one two three four five six seven eight nine ten end.";

        List<TextChunk> chunks = chunker.chunk(text, config);
        assertThat(chunks).hasSizeGreaterThan(1);

        for (TextChunk chunk : chunks) {
            int tokenCount = WordTokenizer.countTokens(chunk.text());
            assertThat(tokenCount).isLessThanOrEqualTo(config.maxTokens());
        }
    }

    // ─────────────── Overlap behavior ───────────────

    @Test
    void chunksWithOverlapShareContent() {
        var config = new ChunkConfig(10, 3);
        String text = "Sentence one has words. Sentence two has words. " +
                "Sentence three has words. Sentence four has words.";

        List<TextChunk> chunks = chunker.chunk(text, config);
        if (chunks.size() >= 2) {
            // Verify overlap: some content from end of chunk N appears at start of chunk N+1
            TextChunk first = chunks.get(0);
            TextChunk second = chunks.get(1);
            // Overlap means second chunk starts before first chunk ends (in the original text)
            assertThat(second.startOffset()).isLessThan(first.endOffset());
        }
    }

    @Test
    void zeroOverlapProducesNonOverlappingChunks() {
        var config = new ChunkConfig(10, 0);
        String text = "Sentence one is here. Sentence two is here. " +
                "Sentence three is here. Sentence four is here. " +
                "Sentence five is here.";

        List<TextChunk> chunks = chunker.chunk(text, config);
        for (int i = 1; i < chunks.size(); i++) {
            assertThat(chunks.get(i).startOffset()).isGreaterThanOrEqualTo(chunks.get(i - 1).endOffset());
        }
    }

    // ─────────────── Offset consistency ───────────────

    @Test
    void chunkOffsetsReferToOriginalText() {
        var config = new ChunkConfig(10, 0);
        String text = "The quick brown fox jumps over the lazy dog. " +
                "Another sentence with several words inside. " +
                "Third sentence is present here.";

        List<TextChunk> chunks = chunker.chunk(text, config);
        for (TextChunk chunk : chunks) {
            String extracted = text.substring(chunk.startOffset(), chunk.endOffset());
            assertThat(extracted).isEqualTo(chunk.text());
        }
    }
}
