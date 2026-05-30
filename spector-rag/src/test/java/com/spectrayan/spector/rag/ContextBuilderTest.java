package com.spectrayan.spector.rag;

import com.spectrayan.spector.commons.error.SpectorValidationException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.spectrayan.spector.commons.TextChunk;

class ContextBuilderTest {

    private ContextBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new ContextBuilder();
    }

    @Test
    void emptyChunkListReturnsEmptyResult() {
        ContextResult result = builder.build(List.of(), 1024);
        assertThat(result.isEmpty()).isTrue();
        assertThat(result.contextText()).isEmpty();
        assertThat(result.attributions()).isEmpty();
    }

    @Test
    void nullChunkListReturnsEmptyResult() {
        ContextResult result = builder.build(null, 1024);
        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void singleChunkWithinLimitIsIncluded() {
        TextChunk chunk = new TextChunk("Hello world this is a test", 6, 0, 26, "doc-1");
        ScoredChunk sc = new ScoredChunk(chunk, 0.95f);

        ContextResult result = builder.build(List.of(sc), 256);

        assertThat(result.isEmpty()).isFalse();
        assertThat(result.contextText()).isEqualTo("Hello world this is a test");
        assertThat(result.attributions()).hasSize(1);
        assertThat(result.attributions().getFirst().documentId()).isEqualTo("doc-1");
        assertThat(result.attributions().getFirst().chunkOffset()).isEqualTo(0);
    }

    @Test
    void chunksOrderedByDescendingScore() {
        TextChunk c1 = new TextChunk("low score chunk", 3, 0, 15, "doc-1");
        TextChunk c2 = new TextChunk("high score chunk", 3, 0, 16, "doc-2");
        TextChunk c3 = new TextChunk("medium score chunk", 3, 0, 18, "doc-3");

        List<ScoredChunk> chunks = List.of(
                new ScoredChunk(c1, 0.3f),
                new ScoredChunk(c2, 0.9f),
                new ScoredChunk(c3, 0.6f)
        );

        ContextResult result = builder.build(chunks, 4096);

        assertThat(result.isEmpty()).isFalse();
        // High score chunk should appear first
        assertThat(result.contextText()).startsWith("high score chunk");
        // Attributions should match order
        assertThat(result.attributions().get(0).documentId()).isEqualTo("doc-2");
        assertThat(result.attributions().get(1).documentId()).isEqualTo("doc-3");
        assertThat(result.attributions().get(2).documentId()).isEqualTo("doc-1");
    }

    @Test
    void chunksExceedingLimitAreRemoved() {
        // Create chunks that together exceed a small token limit
        // Each word counts as ~1 token with WordTokenizer
        String longText = "word ".repeat(200).trim(); // ~200 tokens
        String shortText = "tiny chunk"; // ~2 tokens

        TextChunk bigChunk = new TextChunk(longText, 200, 0, longText.length(), "doc-big");
        TextChunk smallChunk = new TextChunk(shortText, 2, 0, shortText.length(), "doc-small");

        List<ScoredChunk> chunks = List.of(
                new ScoredChunk(bigChunk, 0.5f),
                new ScoredChunk(smallChunk, 0.9f)
        );

        // Token limit that fits the small chunk but not the big one together
        ContextResult result = builder.build(chunks, 256);

        assertThat(result.isEmpty()).isFalse();
        // Both should fit in 256 tokens (200 + 2 = 202)
        assertThat(result.attributions()).hasSize(2);
    }

    @Test
    void lowestScoredChunkRemovedWhenExceedingLimit() {
        // ~100 tokens each
        String text100 = "word ".repeat(100).trim();

        TextChunk c1 = new TextChunk(text100, 100, 0, text100.length(), "doc-high");
        TextChunk c2 = new TextChunk(text100, 100, 0, text100.length(), "doc-mid");
        TextChunk c3 = new TextChunk(text100, 100, 0, text100.length(), "doc-low");

        List<ScoredChunk> chunks = List.of(
                new ScoredChunk(c1, 0.9f),
                new ScoredChunk(c2, 0.6f),
                new ScoredChunk(c3, 0.3f)
        );

        // Limit to ~256 tokens: fits 2 chunks but not 3 (3 * 100 = 300)
        ContextResult result = builder.build(chunks, 256);

        assertThat(result.isEmpty()).isFalse();
        assertThat(result.attributions()).hasSize(2);
        // The lowest scored (doc-low) should be excluded
        assertThat(result.attributions()).extracting(ChunkAttribution::documentId)
                .containsExactly("doc-high", "doc-mid");
    }

    @Test
    void noChunksFitReturnsEmptyResult() {
        // Create a chunk that exceeds the minimum token limit
        String hugeText = "word ".repeat(300).trim(); // ~300 tokens
        TextChunk chunk = new TextChunk(hugeText, 300, 0, hugeText.length(), "doc-1");

        ContextResult result = builder.build(List.of(new ScoredChunk(chunk, 0.9f)), 256);

        assertThat(result.isEmpty()).isTrue();
        assertThat(result.contextText()).isEmpty();
        assertThat(result.attributions()).isEmpty();
    }

    @Test
    void tokenLimitBelowMinimumThrowsException() {
        assertThatThrownBy(() -> builder.build(List.of(), 100))
                .isInstanceOf(SpectorValidationException.class)
                .hasMessageContaining("256");
    }

    @Test
    void tokenLimitAboveMaximumThrowsException() {
        assertThatThrownBy(() -> builder.build(List.of(), 200_000))
                .isInstanceOf(SpectorValidationException.class)
                .hasMessageContaining("131072");
    }

    @Test
    void nullDocIdDefaultsToUnknown() {
        TextChunk chunk = new TextChunk("hello world", 2, 0, 11); // no sourceDocId
        ScoredChunk sc = new ScoredChunk(chunk, 0.5f);

        ContextResult result = builder.build(List.of(sc), 256);

        assertThat(result.attributions().getFirst().documentId()).isEqualTo("unknown");
    }

    @Test
    void equalScoresPreserveOriginalOrder() {
        TextChunk c1 = new TextChunk("first chunk", 2, 0, 11, "doc-1");
        TextChunk c2 = new TextChunk("second chunk", 2, 0, 12, "doc-2");
        TextChunk c3 = new TextChunk("third chunk", 2, 0, 11, "doc-3");

        List<ScoredChunk> chunks = List.of(
                new ScoredChunk(c1, 0.8f),
                new ScoredChunk(c2, 0.8f),
                new ScoredChunk(c3, 0.8f)
        );

        ContextResult result = builder.build(chunks, 4096);

        // With equal scores, stable sort should preserve original order
        assertThat(result.attributions()).extracting(ChunkAttribution::documentId)
                .containsExactly("doc-1", "doc-2", "doc-3");
    }

    @Test
    void contextResultEmptyFactoryMethod() {
        ContextResult empty = ContextResult.empty();
        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.contextText()).isEmpty();
        assertThat(empty.attributions()).isEmpty();
    }

    @Test
    void chunkAttributionRejectsInvalidInput() {
        assertThatThrownBy(() -> new ChunkAttribution(null, 0))
                .isInstanceOf(SpectorValidationException.class);
        assertThatThrownBy(() -> new ChunkAttribution("", 0))
                .isInstanceOf(SpectorValidationException.class);
        assertThatThrownBy(() -> new ChunkAttribution("doc", -1))
                .isInstanceOf(SpectorValidationException.class);
    }

    @Test
    void scoredChunkRejectsNullChunk() {
        assertThatThrownBy(() -> new ScoredChunk(null, 0.5f))
                .isInstanceOf(SpectorValidationException.class);
    }

    @Test
    void scoredChunkRejectsNanScore() {
        TextChunk chunk = new TextChunk("hello", 1, 0, 5, "doc");
        assertThatThrownBy(() -> new ScoredChunk(chunk, Float.NaN))
                .isInstanceOf(SpectorValidationException.class);
    }
}
