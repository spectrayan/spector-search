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
package com.spectrayan.spector.embed.ollama;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spectrayan.spector.embed.GenerationOptions;
import com.spectrayan.spector.embed.TextGenerationProvider;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

/**
 * Unit tests for {@link OllamaLlmProvider}.
 *
 * <p>Tests cover factory methods, configuration, input validation, and error
 * handling without requiring a running Ollama server.</p>
 */
class OllamaLlmProviderTest {

    @Nested
    class FactoryMethodTests {

        @Test
        void createWithModel() {
            var provider = OllamaLlmProvider.create("qwen3:0.6b");
            assertThat(provider.modelName()).isEqualTo("qwen3:0.6b");
        }

        @Test
        void createWithModelAndBaseUrl() {
            var provider = OllamaLlmProvider.create("llama3.1:8b", "http://gpu-server:11434");
            assertThat(provider.modelName()).isEqualTo("llama3.1:8b");
        }

        @Test
        void createDefault() {
            var provider = OllamaLlmProvider.createDefault();
            assertThat(provider.modelName()).isEqualTo("qwen3:0.6b");
        }

        @Test
        void constructorStoresFields() {
            var provider = new OllamaLlmProvider("test-model",
                    "http://localhost:11434", Duration.ofSeconds(30));
            assertThat(provider.modelName()).isEqualTo("test-model");
        }
    }

    @Nested
    class InputValidationTests {

        @Test
        void generateNullPromptThrows() {
            var provider = OllamaLlmProvider.create("test");
            assertThatThrownBy(() -> provider.generate(null))
                    .isInstanceOf(TextGenerationProvider.GenerationException.class)
                    .hasMessageContaining("blank");
        }

        @Test
        void generateBlankPromptThrows() {
            var provider = OllamaLlmProvider.create("test");
            assertThatThrownBy(() -> provider.generate("   "))
                    .isInstanceOf(TextGenerationProvider.GenerationException.class)
                    .hasMessageContaining("blank");
        }

        @Test
        void generateEmptyPromptThrows() {
            var provider = OllamaLlmProvider.create("test");
            assertThatThrownBy(() -> provider.generate(""))
                    .isInstanceOf(TextGenerationProvider.GenerationException.class)
                    .hasMessageContaining("blank");
        }

        @Test
        void generateWithOptionsNullPromptThrows() {
            var provider = OllamaLlmProvider.create("test");
            assertThatThrownBy(() -> provider.generate(null, GenerationOptions.DEFAULT))
                    .isInstanceOf(TextGenerationProvider.GenerationException.class)
                    .hasMessageContaining("blank");
        }
    }

    @Nested
    class ErrorHandlingTests {

        @Test
        void generateFailsWhenServerUnavailable() {
            var provider = new OllamaLlmProvider("test",
                    "http://localhost:19999", Duration.ofMillis(500));
            assertThatThrownBy(() -> provider.generate("test prompt"))
                    .isInstanceOf(TextGenerationProvider.GenerationException.class)
                    .hasMessageContaining("unavailable");
        }

        @Test
        void generateWithOptionsFailsWhenServerUnavailable() {
            var provider = new OllamaLlmProvider("test",
                    "http://localhost:19999", Duration.ofMillis(500));
            var options = GenerationOptions.builder()
                    .temperature(0.5f)
                    .maxTokens(256)
                    .build();
            assertThatThrownBy(() -> provider.generate("test prompt", options))
                    .isInstanceOf(TextGenerationProvider.GenerationException.class);
        }

        @Test
        void isAvailableReturnsFalseWhenServerDown() {
            var provider = new OllamaLlmProvider("test",
                    "http://localhost:19999", Duration.ofMillis(500));
            assertThat(provider.isAvailable()).isFalse();
        }
    }

    @Nested
    class GenerationOptionsTests {

        @Test
        void defaultOptionsUsed() {
            var provider = OllamaLlmProvider.create("test");
            // generate(prompt) delegates to generate(prompt, DEFAULT)
            // just verify it throws the connection error, not a config error
            assertThatThrownBy(() -> provider.generate("test"))
                    .isInstanceOf(TextGenerationProvider.GenerationException.class);
        }

        @Test
        void customOptionsWithStopSequences() {
            var provider = new OllamaLlmProvider("test",
                    "http://localhost:19999", Duration.ofMillis(500));
            var options = GenerationOptions.builder()
                    .temperature(0.7f)
                    .maxTokens(100)
                    .topP(0.95f)
                    .stopSequences("---", "\n\n")
                    .build();
            assertThatThrownBy(() -> provider.generate("test prompt", options))
                    .isInstanceOf(TextGenerationProvider.GenerationException.class);
        }

        @Test
        void nullOptionsHandled() {
            var provider = new OllamaLlmProvider("test",
                    "http://localhost:19999", Duration.ofMillis(500));
            // null options should not cause NPE in buildRequestBody
            assertThatThrownBy(() -> provider.generate("test prompt", null))
                    .isInstanceOf(TextGenerationProvider.GenerationException.class);
        }

        @Test
        void zeroMaxTokensOmitsNumPredict() {
            var provider = new OllamaLlmProvider("test",
                    "http://localhost:19999", Duration.ofMillis(500));
            var options = GenerationOptions.builder()
                    .maxTokens(0)
                    .build();
            // Should not fail on building the request body — fails on connection
            assertThatThrownBy(() -> provider.generate("test prompt", options))
                    .isInstanceOf(TextGenerationProvider.GenerationException.class);
        }
    }
}
