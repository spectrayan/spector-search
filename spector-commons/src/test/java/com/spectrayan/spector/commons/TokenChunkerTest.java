package com.spectrayan.spector.commons;

import com.spectrayan.spector.commons.error.SpectorValidationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Tests for {@link TokenChunker}.
 */
class TokenChunkerTest {

    @Test
    void shortDocumentNotChunked() {
        var chunker = new TokenChunker(100, 10);
        List<TextChunker.Chunk> chunks = chunker.chunk("doc", "Hello world.");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().chunkId()).isEqualTo("doc#chunk-0");
    }

    @Test
    void longDocumentChunked() {
        var chunker = new TokenChunker(20, 5); // 20 tokens per chunk
        // Generate ~100 tokens
        String text = "The quick brown fox jumps over the lazy dog. " .repeat(12);
        List<TextChunker.Chunk> chunks = chunker.chunk("doc", text);

        assertThat(chunks).hasSizeGreaterThan(1);
        for (var chunk : chunks) {
            int tokenCount = WordTokenizer.countTokens(chunk.text());
            // Chunk should not massively exceed the token limit
            assertThat(tokenCount).as("chunk '%s' should have ≤ ~25 tokens", chunk.chunkId())
                    .isLessThanOrEqualTo(30); // some tolerance for sentence boundary
        }
    }

    @Test
    void chunkIdsAreSequential() {
        var chunker = new TokenChunker(10, 2);
        String text = "Word one two three four five six seven eight nine ten. " .repeat(10);
        List<TextChunker.Chunk> chunks = chunker.chunk("myDoc", text);

        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).index()).isEqualTo(i);
            assertThat(chunks.get(i).chunkId()).isEqualTo("myDoc#chunk-" + i);
        }
    }

    @Test
    void emptyInputReturnsEmptyList() {
        var chunker = new TokenChunker();
        assertThat(chunker.chunk("doc", "")).isEmpty();
        assertThat(chunker.chunk("doc", null)).isEmpty();
        assertThat(chunker.chunk("doc", "   ")).isEmpty();
    }

    @Test
    void defaultConfig() {
        var chunker = new TokenChunker();
        assertThat(chunker.maxTokens()).isEqualTo(128);
        assertThat(chunker.overlapTokens()).isEqualTo(16);
    }

    @Test
    void invalidConfigThrows() {
        assertThatThrownBy(() -> new TokenChunker(0, 0))
                .isInstanceOf(SpectorValidationException.class);
        assertThatThrownBy(() -> new TokenChunker(10, 10))
                .isInstanceOf(SpectorValidationException.class);
        assertThatThrownBy(() -> new TokenChunker(10, -1))
                .isInstanceOf(SpectorValidationException.class);
    }

    @Test
    void singleVeryLongSentence() {
        var chunker = new TokenChunker(10, 2);
        // One sentence with many words
        String text = "word ".repeat(50) + "end.";
        List<TextChunker.Chunk> chunks = chunker.chunk("doc", text);
        // Should still produce multiple chunks
        assertThat(chunks).hasSizeGreaterThan(1);
    }
}
