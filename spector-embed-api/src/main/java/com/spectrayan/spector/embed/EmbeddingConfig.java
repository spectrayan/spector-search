package com.spectrayan.spector.embed;

import java.time.Duration;

/**
 * Configuration for an embedding provider.
 *
 * @param model      the embedding model name (e.g., "nomic-embed-text")
 * @param baseUrl    the API base URL (e.g., "http://localhost:11434")
 * @param timeout    HTTP request timeout
 * @param batchSize  maximum texts per batch request
 */
public record EmbeddingConfig(
        String model,
        String baseUrl,
        Duration timeout,
        int batchSize
) {
    /** Default Ollama configuration. */
    public static final EmbeddingConfig OLLAMA_DEFAULT = new EmbeddingConfig(
            "nomic-embed-text",
            "http://localhost:11434",
            Duration.ofSeconds(30),
            32
    );

    /**
     * Creates a config with the given model and default Ollama settings.
     */
    public static EmbeddingConfig ollama(String model) {
        return new EmbeddingConfig(model, OLLAMA_DEFAULT.baseUrl, OLLAMA_DEFAULT.timeout, OLLAMA_DEFAULT.batchSize);
    }

    /**
     * Returns a new config with a different base URL.
     */
    public EmbeddingConfig withBaseUrl(String baseUrl) {
        return new EmbeddingConfig(model, baseUrl, timeout, batchSize);
    }

    /**
     * Returns a new config with a different timeout.
     */
    public EmbeddingConfig withTimeout(Duration timeout) {
        return new EmbeddingConfig(model, baseUrl, timeout, batchSize);
    }

    /**
     * Returns a new config with a different batch size.
     */
    public EmbeddingConfig withBatchSize(int batchSize) {
        return new EmbeddingConfig(model, baseUrl, timeout, batchSize);
    }
}
