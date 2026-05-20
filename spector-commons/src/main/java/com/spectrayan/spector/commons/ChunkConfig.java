package com.spectrayan.spector.commons;

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
     * @throws IllegalArgumentException if maxTokens is not in [1, 8192] or
     *                                  overlapTokens is not in [0, maxTokens - 1]
     */
    public ChunkConfig {
        if (maxTokens <= 0 || maxTokens > 8192) {
            throw new IllegalArgumentException(
                    "maxTokens must be greater than 0 and at most 8192, got: " + maxTokens);
        }
        if (overlapTokens < 0 || overlapTokens >= maxTokens) {
            throw new IllegalArgumentException(
                    "overlap must be >= 0 and less than maxTokens (" + maxTokens + "), got: " + overlapTokens);
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
