package com.spectrayan.spector.commons;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Tests for {@link TextChunker}.
 */
class TextChunkerTest {

    @Test
    void shortDocumentNotChunked() {
        var chunker = new TextChunker(512, 64);
        List<TextChunker.Chunk> chunks = chunker.chunk("doc-1", "Short text.");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().parentId()).isEqualTo("doc-1");
        assertThat(chunks.getFirst().chunkId()).isEqualTo("doc-1#chunk-0");
        assertThat(chunks.getFirst().index()).isEqualTo(0);
    }

    @Test
    void longDocumentChunked() {
        var chunker = new TextChunker(100, 20);
        String longText = "The quick brown fox jumps over the lazy dog. " .repeat(20); // ~900 chars
        List<TextChunker.Chunk> chunks = chunker.chunk("doc-1", longText);

        assertThat(chunks).hasSizeGreaterThan(1);
        // All chunks should be under or near chunkSize
        for (TextChunker.Chunk c : chunks) {
            assertThat(c.text().length()).isLessThanOrEqualTo(150); // some tolerance for sentence boundary
            assertThat(c.parentId()).isEqualTo("doc-1");
            assertThat(c.chunkId()).startsWith("doc-1#chunk-");
        }
    }

    @Test
    void chunksOverlap() {
        var chunker = new TextChunker(100, 20);
        String text = "Sentence one is here. Sentence two is here. Sentence three is here. " +
                "Sentence four is here. Sentence five is here. Sentence six is here. " +
                "Sentence seven is here. Sentence eight is here.";
        List<TextChunker.Chunk> chunks = chunker.chunk("doc-1", text);

        if (chunks.size() >= 2) {
            // Verify overlapping region exists
            String chunk0 = chunks.get(0).text();
            String chunk1 = chunks.get(1).text();
            // chunk1 should start before where chunk0 ends (overlap)
            assertThat(chunks.get(1).startChar()).isLessThan(chunks.get(0).endChar());
        }
    }

    @Test
    void chunkIdsAreSequential() {
        var chunker = new TextChunker(50, 10);
        String text = "Word. " .repeat(100); // long enough to chunk
        List<TextChunker.Chunk> chunks = chunker.chunk("myDoc", text);

        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).index()).isEqualTo(i);
            assertThat(chunks.get(i).chunkId()).isEqualTo("myDoc#chunk-" + i);
        }
    }

    @Test
    void emptyInputReturnsEmptyList() {
        var chunker = new TextChunker();
        assertThat(chunker.chunk("doc", "")).isEmpty();
        assertThat(chunker.chunk("doc", null)).isEmpty();
        assertThat(chunker.chunk("doc", "   ")).isEmpty();
    }

    @Test
    void chunkStructuredXml() {
        var chunker = new TextChunker(50, 10);
        String xml = "<doc><title>Java Search</title><body>" +
                "SIMD accelerated vector search engine for modern JVM applications. " +
                "Uses Panama memory segments for zero copy storage. " +
                "Virtual threads handle concurrent requests efficiently.</body></doc>";
        List<TextChunker.Chunk> chunks = chunker.chunkStructured("xml-doc", xml);
        assertThat(chunks).isNotEmpty();
        // Verify no XML tags in chunks
        for (TextChunker.Chunk c : chunks) {
            assertThat(c.text()).doesNotContain("<", ">");
        }
    }

    @Test
    void chunkStructuredJson() {
        var chunker = new TextChunker(60, 10);
        String json = """
                {"title": "Long Article", "body": "This is a very long article about search engines. It covers many topics including indexing and retrieval."}
                """;
        List<TextChunker.Chunk> chunks = chunker.chunkStructured("json-doc", json);
        assertThat(chunks).isNotEmpty();
    }

    @Test
    void defaultChunkSize() {
        var chunker = new TextChunker();
        assertThat(chunker.chunkSize()).isEqualTo(512);
        assertThat(chunker.overlap()).isEqualTo(64);
    }

    @Test
    void invalidConfigThrows() {
        assertThatThrownBy(() -> new TextChunker(0, 0))
                .isInstanceOf(com.spectrayan.spector.commons.error.SpectorValidationException.class);
        assertThatThrownBy(() -> new TextChunker(100, 100))
                .isInstanceOf(com.spectrayan.spector.commons.error.SpectorValidationException.class);
        assertThatThrownBy(() -> new TextChunker(100, -1))
                .isInstanceOf(com.spectrayan.spector.commons.error.SpectorValidationException.class);
    }

    @Test
    void chunkLengthMethod() {
        var chunk = new TextChunker.Chunk("doc", "doc#chunk-0", 0, "hello world", 0, 11);
        assertThat(chunk.length()).isEqualTo(11);
    }
}
