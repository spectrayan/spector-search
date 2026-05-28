package com.spectrayan.spector.ingestion;

import com.spectrayan.spector.embed.EmbeddingProvider;

/**
 * Factory for creating {@link EmbeddingProvider} instances.
 *
 * <p>Uses reflection to instantiate the Ollama provider since
 * {@code spector-embed-ollama} is an optional runtime dependency.</p>
 */
public final class EmbeddingProviderFactory {

    private EmbeddingProviderFactory() {}

    /**
     * Creates an Ollama embedding provider.
     *
     * @param baseUrl Ollama server URL (e.g., "http://localhost:11434")
     * @param model   embedding model name (e.g., "nomic-embed-text")
     * @return configured embedding provider
     * @throws RuntimeException if spector-embed-ollama is not on the classpath
     */
    public static EmbeddingProvider create(String baseUrl, String model) {
        try {
            var configClass = Class.forName("com.spectrayan.spector.embed.EmbeddingConfig");
            var ollamaFactory = configClass.getMethod("ollama", String.class);
            Object config = ollamaFactory.invoke(null, model);
            var withBaseUrl = configClass.getMethod("withBaseUrl", String.class);
            config = withBaseUrl.invoke(config, baseUrl);

            var providerClass = Class.forName(
                    "com.spectrayan.spector.embed.ollama.OllamaEmbeddingProvider");
            var constructor = providerClass.getConstructor(configClass);
            return (EmbeddingProvider) constructor.newInstance(config);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("spector-embed-ollama not on classpath. "
                    + "Add it as a runtime dependency.", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create OllamaEmbeddingProvider", e);
        }
    }
}
