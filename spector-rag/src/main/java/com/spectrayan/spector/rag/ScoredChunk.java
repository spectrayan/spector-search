package com.spectrayan.spector.rag;

import com.spectrayan.spector.commons.TextChunk;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * A text chunk annotated with a relevance score from search.
 *
 * @param chunk the text chunk
 * @param score relevance score (higher is more relevant)
 */
public record ScoredChunk(TextChunk chunk, float score) {

    public ScoredChunk {
        if (chunk == null) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "chunk");
        }
        if (Float.isNaN(score)) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "score", "NaN");
        }
    }
}
