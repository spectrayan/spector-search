package com.spectrayan.spector.commons.error;

/**
 * Exception thrown when an embedding request exceeds the configured timeout limits.
 *
 * @see SpectorEmbeddingException
 */
public class SpectorEmbeddingTimeoutException extends SpectorEmbeddingException {

    private final long timeoutMs;

    public SpectorEmbeddingTimeoutException(long timeoutMs) {
        super(ErrorCode.EMBEDDING_TIMEOUT, timeoutMs);
        this.timeoutMs = timeoutMs;
    }

    public SpectorEmbeddingTimeoutException(long timeoutMs, Throwable cause) {
        super(ErrorCode.EMBEDDING_TIMEOUT, cause, timeoutMs);
        this.timeoutMs = timeoutMs;
    }

    /** Returns the timeout duration in milliseconds that was exceeded. */
    public long getTimeoutMs() {
        return timeoutMs;
    }
}
