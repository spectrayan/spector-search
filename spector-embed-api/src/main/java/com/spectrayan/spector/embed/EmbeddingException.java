package com.spectrayan.spector.embed;

/**
 * Exception thrown when an embedding operation fails.
 *
 * <p>Wraps transport errors, model errors, and timeout failures
 * from any {@link EmbeddingProvider} implementation.</p>
 */
public class EmbeddingException extends RuntimeException {

    public EmbeddingException(String message) {
        super(message);
    }

    public EmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }
}
