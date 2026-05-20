package com.spectrayan.spector.commons;

/**
 * Represents a chunk of text produced by the chunking engine.
 *
 * @param text        the chunk text content
 * @param tokenCount  number of tokens in this chunk
 * @param startOffset character start offset in the original text (inclusive)
 * @param endOffset   character end offset in the original text (exclusive)
 * @param sourceDocId the source document identifier (may be null if not applicable)
 */
public record TextChunk(String text, int tokenCount, int startOffset, int endOffset, String sourceDocId) {

    /**
     * Creates a TextChunk without a source document ID.
     */
    public TextChunk(String text, int tokenCount, int startOffset, int endOffset) {
        this(text, tokenCount, startOffset, endOffset, null);
    }
}
