package com.spectrayan.spector.engine.rag;

import com.spectrayan.spector.commons.TextChunk;

/**
 * A text chunk annotated with a relevance score from search.
 *
 * @param chunk the text chunk
 * @param score relevance score (higher is more relevant)
 */
public record ScoredChunk(TextChunk chunk, float score) {

    public ScoredChunk {
        if (chunk == null) {
            throw new IllegalArgumentException("chunk must not be null");
        }
        if (Float.isNaN(score)) {
            throw new IllegalArgumentException("score must not be NaN");
        }
    }
}
