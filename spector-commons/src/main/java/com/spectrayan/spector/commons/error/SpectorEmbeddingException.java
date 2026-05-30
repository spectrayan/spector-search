package com.spectrayan.spector.commons.error;

/**
 * Exception for embedding provider errors ({@code SPE-300-xxx}).
 *
 * <p>Thrown when an embedding provider (e.g. Ollama) is unreachable, returns an error,
 * times out, or returns vectors with unexpected dimensions.</p>
 *
 * @see ErrorCode#EMBEDDING_UNAVAILABLE
 * @see ErrorCode#EMBEDDING_TIMEOUT
 */
public class SpectorEmbeddingException extends SpectorException {

    public SpectorEmbeddingException(ErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }

    public SpectorEmbeddingException(ErrorCode errorCode, String preformattedMessage, boolean isPreformatted) {
        super(errorCode, preformattedMessage, isPreformatted);
    }

    public SpectorEmbeddingException(ErrorCode errorCode, Throwable cause, Object... args) {
        super(errorCode, cause, args);
    }
}
