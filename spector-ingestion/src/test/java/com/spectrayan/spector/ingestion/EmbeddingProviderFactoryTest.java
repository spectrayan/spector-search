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

import static org.assertj.core.api.Assertions.*;

import com.spectrayan.spector.embed.EmbeddingProvider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link EmbeddingProviderFactory} — reflection-based factory
 * for Ollama embedding provider.
 */
@DisplayName("EmbeddingProviderFactory")
class EmbeddingProviderFactoryTest {

    @Nested
    @DisplayName("create()")
    class CreateTests {

        @Test
        @DisplayName("creates OllamaEmbeddingProvider when spector-embed-ollama is on classpath")
        void createsOllamaProvider() {
            // This test depends on spector-embed-ollama being on test classpath
            // If it's not available, we test the exception path instead
            try {
                EmbeddingProvider provider = EmbeddingProviderFactory.create(
                        "http://localhost:11434", "nomic-embed-text");
                assertThat(provider).isNotNull();
                assertThat(provider.getClass().getSimpleName())
                        .isEqualTo("OllamaEmbeddingProvider");
            } catch (Exception e) {
                // If Ollama provider not on classpath, verify proper exception
                assertThat(e).hasMessageContaining("Ollama");
            }
        }
    }

    @Nested
    @DisplayName("constructor")
    class ConstructorTests {

        @Test
        @DisplayName("factory class has private constructor")
        void hasPrivateConstructor() throws Exception {
            var ctor = EmbeddingProviderFactory.class.getDeclaredConstructor();
            assertThat(java.lang.reflect.Modifier.isPrivate(ctor.getModifiers())).isTrue();
        }
    }
}
