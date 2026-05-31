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

/**
 * Service Provider Interface for text generation (LLM inference).
 *
 * <p>Implementations send prompts to a language model and return generated text.
 * This is separate from {@link EmbeddingProvider} because embedding and generation
 * are fundamentally different operations — many providers support one but not both.</p>
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li>{@link #generate(String)} must return a non-null response</li>
 *   <li>Implementations must be thread-safe</li>
 *   <li>Timeouts are implementation-specific (recommended: 30s default)</li>
 * </ul>
 *
 * <h3>Primary Use Case: Sleep Consolidation</h3>
 * <p>The {@code ReflectDaemon} uses this during REM Sleep to synthesize
 * semantic facts from episodic memory clusters:
 * <em>"Summarize these N memories into a factual rule."</em></p>
 *
 * <h3>Built-in Implementations</h3>
 * <ul>
 *   <li>Future: {@code OllamaGenerationProvider} — local Ollama server</li>
 *   <li>Future: {@code OpenAiGenerationProvider} — OpenAI API</li>
 * </ul>
 *
 * @see EmbeddingProvider
 */
public interface TextGenerationProvider extends AutoCloseable {

    /**
     * Generates text from a prompt.
     *
     * @param prompt the input prompt
     * @return generated text response
     * @throws GenerationException if generation fails
     */
    String generate(String prompt);

    /**
     * Generates text with configurable options.
     *
     * @param prompt  the input prompt
     * @param options generation configuration (temperature, max tokens, etc.)
     * @return generated text response
     * @throws GenerationException if generation fails
     */
    default String generate(String prompt, GenerationOptions options) {
        return generate(prompt); // default ignores options
    }

    /**
     * Returns the name of the underlying model.
     *
     * @return model identifier (e.g., "qwen3:8b", "gpt-4o")
     */
    String modelName();

    /**
     * Returns whether this provider is available and ready.
     *
     * @return true if the provider can accept generation requests
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Default no-op close. Override if the provider holds resources.
     */
    @Override
    default void close() {}

    /**
     * Exception thrown when text generation fails.
     */
    class GenerationException extends RuntimeException {
        public GenerationException(String message) {
            super(message);
        }

        public GenerationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
