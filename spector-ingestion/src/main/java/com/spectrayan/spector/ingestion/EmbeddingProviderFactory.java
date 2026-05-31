/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.spectrayan.spector.ingestion;

import com.spectrayan.spector.embed.EmbeddingProvider;
import com.spectrayan.spector.commons.error.SpectorEmbeddingException;
import com.spectrayan.spector.embed.error.SpectorEmbeddingUnavailableException;
import com.spectrayan.spector.commons.error.SpectorServerException;
import com.spectrayan.spector.commons.error.ErrorCode;

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
     * @throws SpectorEmbeddingException if spector-embed-ollama is not on the classpath
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
            throw new SpectorEmbeddingUnavailableException("Ollama (spector-embed-ollama not on classpath)", e);
        } catch (Exception e) {
            throw new SpectorServerException(ErrorCode.INTERNAL_ERROR, e, "Failed to create OllamaEmbeddingProvider");
        }
    }
}
