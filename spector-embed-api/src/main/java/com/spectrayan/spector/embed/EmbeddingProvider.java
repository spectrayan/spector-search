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
package com.spectrayan.spector.embed;

import com.spectrayan.spector.commons.error.SpectorEmbeddingException;

import java.util.List;

/**
 * Service Provider Interface for text embedding (vectorization).
 *
 * <p>Implementations convert text into dense floating-point vectors suitable
 * for semantic similarity search. The engine uses this interface to auto-embed
 * documents during ingestion and queries during search.</p>
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li>{@link #embed(String)} must always return a vector of length {@link #dimensions()}</li>
 *   <li>{@link #embedBatch(List)} should be preferred for bulk operations (may be more efficient)</li>
 *   <li>Implementations must be thread-safe</li>
 * </ul>
 *
 * <h3>Built-in Implementations</h3>
 * <ul>
 *   <li>{@code OllamaEmbeddingProvider} — local Ollama server (spector-embed-ollama module)</li>
 * </ul>
 *
 * <h3>Custom Implementation Example</h3>
 * <pre>{@code
 *   public class MyProvider implements EmbeddingProvider {
 *       public EmbeddingResult embed(String text) {
 *           float[] vector = myModel.encode(text);
 *           return new EmbeddingResult(vector, text.split("\\s+").length, "my-model");
 *       }
 *       public int dimensions() { return 384; }
 *       public String modelName() { return "my-model"; }
 *   }
 * }</pre>
 */
public interface EmbeddingProvider extends AutoCloseable {

    /**
     * Embeds a single text string into a vector.
     *
     * @param text the input text
     * @return embedding result containing the vector
     * @throws SpectorEmbeddingException if embedding fails
     */
    EmbeddingResult embed(String text);

    /**
     * Embeds multiple texts in a single batch call.
     *
     * <p>Default implementation calls {@link #embed(String)} sequentially.
     * Providers that support native batching should override this for efficiency.</p>
     *
     * @param texts list of input texts
     * @return list of embedding results (same order as input)
     * @throws SpectorEmbeddingException if embedding fails
     */
    default List<EmbeddingResult> embedBatch(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }

    /**
     * Returns the dimensionality of the embedding vectors produced.
     *
     * @return vector dimensions (e.g., 384, 768, 1536)
     */
    int dimensions();

    /**
     * Returns the name of the underlying model.
     *
     * @return model identifier (e.g., "nomic-embed-text", "text-embedding-ada-002")
     */
    String modelName();

    /**
     * Returns the maximum number of tokens this model supports per input.
     *
     * @return max token count (default: 512)
     */
    default int maxTokens() {
        return 512;
    }

    /**
     * Default no-op close. Override if the provider holds resources.
     */
    @Override
    default void close() {}
}
