package com.spectrayan.spector.commons;

import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.commons.error.SpectorValidationException;

/**
 * Configuration for the {@link TokenAwareChunker}.
 *
 * @param maxTokens     maximum token count per chunk (1 to 8192 inclusive)
 * @param overlapTokens number of overlapping tokens between consecutive chunks (0 to maxTokens - 1)
 */
public record ChunkConfig(int maxTokens, int overlapTokens) {

    /**
     * Validates the configuration parameters.
     *
     * @throws SpectorValidationException if maxTokens is not in [1, 8192] or
     *                                  overlapTokens is not in [0, maxTokens - 1]
     */
    public ChunkConfig {
        if (maxTokens <= 0 || maxTokens > 8192) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "maxTokens", 1, 8192, maxTokens);
        }
        if (overlapTokens < 0 || overlapTokens >= maxTokens) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "overlapTokens", 0, maxTokens - 1, overlapTokens);
        }
    }

    /**
     * Creates a config with no overlap.
     *
     * @param maxTokens maximum tokens per chunk
     */
    public ChunkConfig(int maxTokens) {
        this(maxTokens, 0);
    }
}
