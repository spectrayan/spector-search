package com.spectrayan.spector.embed.error;

import com.spectrayan.spector.commons.error.*;

/**
 * Exception thrown when the embedding provider or model is not reachable.
 *
 * @see SpectorEmbeddingException
 */
public class SpectorEmbeddingUnavailableException extends SpectorEmbeddingException {

    private final String provider;

    public SpectorEmbeddingUnavailableException(String provider) {
        super(ErrorCode.EMBEDDING_UNAVAILABLE, provider);
        this.provider = provider;
    }

    public SpectorEmbeddingUnavailableException(String provider, Throwable cause) {
        super(ErrorCode.EMBEDDING_UNAVAILABLE, cause, provider);
        this.provider = provider;
    }

    public SpectorEmbeddingUnavailableException(ErrorCode errorCode, String provider) {
        super(errorCode, provider);
        this.provider = provider;
    }

    public SpectorEmbeddingUnavailableException(ErrorCode errorCode, Throwable cause, String provider) {
        super(errorCode, cause, provider);
        this.provider = provider;
    }

    /** Returns the name or URL of the embedding provider that is unavailable. */
    public String getProvider() {
        return provider;
    }
}
