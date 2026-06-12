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

import com.spectrayan.spector.embed.EmbeddingProvider;
import com.spectrayan.spector.embed.EmbeddingResult;
import com.spectrayan.spector.embed.TokenEmbeddingResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

/**
 * Unit tests for {@link OllamaTokenEmbeddingProvider}.
 *
 * <p>Uses a fake in-memory {@link EmbeddingProvider} to test the dense-derived
 * per-token embedding logic without requiring a running Ollama server.</p>
 */
class OllamaTokenEmbeddingProviderTest {

    /** Fake embedding provider that returns deterministic vectors. */
    private static class FakeEmbeddingProvider implements EmbeddingProvider {
        private static final int DIMS = 256;

        @Override
        public EmbeddingResult embed(String text) {
            float[] vec = new float[DIMS];
            Random rng = new Random(text.hashCode());
            for (int i = 0; i < DIMS; i++) {
                vec[i] = rng.nextFloat() * 2 - 1;
            }
            return new EmbeddingResult(vec, text.split("\\s+").length, "fake");
        }

        @Override
        public List<EmbeddingResult> embedBatch(List<String> texts) {
            return texts.stream().map(this::embed).toList();
        }

        @Override
        public int dimensions() { return DIMS; }

        @Override
        public String modelName() { return "fake-embed"; }
    }

    private FakeEmbeddingProvider fakeProvider;

    @BeforeEach
    void setUp() {
        fakeProvider = new FakeEmbeddingProvider();
    }

    @Nested
    class ConstructorTests {

        @Test
        void constructsWithProvider() {
            var provider = new OllamaTokenEmbeddingProvider(fakeProvider);
            assertThat(provider.modelName()).contains("dense-derived-colbert");
            assertThat(provider.modelName()).contains("fake-embed");
        }

        @Test
        void constructsWithCustomDimensions() {
            var provider = new OllamaTokenEmbeddingProvider(fakeProvider, 64);
            assertThat(provider.tokenDimensions()).isEqualTo(64);
        }

        @Test
        void defaultDimensionsIs128() {
            var provider = new OllamaTokenEmbeddingProvider(fakeProvider);
            assertThat(provider.tokenDimensions()).isEqualTo(128);
        }

        @Test
        void embeddingProviderAccessor() {
            var provider = new OllamaTokenEmbeddingProvider(fakeProvider);
            assertThat(provider.embeddingProvider()).isSameAs(fakeProvider);
        }
    }

    @Nested
    class EncodeTests {

        @Test
        void encodeNullReturnsEmpty() {
            var provider = new OllamaTokenEmbeddingProvider(fakeProvider);
            TokenEmbeddingResult result = provider.encode(null);
            assertThat(result.embeddings()).isEmpty();
            assertThat(result.tokens()).isEmpty();
            assertThat(result.tokenCount()).isZero();
        }

        @Test
        void encodeBlankReturnsEmpty() {
            var provider = new OllamaTokenEmbeddingProvider(fakeProvider);
            TokenEmbeddingResult result = provider.encode("   ");
            assertThat(result.embeddings()).isEmpty();
            assertThat(result.tokens()).isEmpty();
        }

        @Test
        void encodeTextProducesTokenEmbeddings() {
            var provider = new OllamaTokenEmbeddingProvider(fakeProvider);
            TokenEmbeddingResult result = provider.encode("memory consolidation during sleep");
            assertThat(result.tokenCount()).isGreaterThan(0);
            assertThat(result.embeddings().length).isEqualTo(result.tokenCount());
            assertThat(result.tokens().length).isEqualTo(result.tokenCount());
        }

        @Test
        void embeddingsProjectedToTokenDims() {
            int tokenDims = 64;
            var provider = new OllamaTokenEmbeddingProvider(fakeProvider, tokenDims);
            TokenEmbeddingResult result = provider.encode("test projection dimensions");
            for (float[] tokenEmb : result.embeddings()) {
                assertThat(tokenEmb.length).isEqualTo(tokenDims);
            }
        }

        @Test
        void embeddingsProjectedToDefaultDims() {
            var provider = new OllamaTokenEmbeddingProvider(fakeProvider);
            TokenEmbeddingResult result = provider.encode("test default dims");
            for (float[] tokenEmb : result.embeddings()) {
                assertThat(tokenEmb.length).isEqualTo(128);
            }
        }

        @Test
        void singleCharTermsFiltered() {
            var provider = new OllamaTokenEmbeddingProvider(fakeProvider);
            TokenEmbeddingResult result = provider.encode("I am a test of words");
            // "I", "a" should be filtered (< 2 chars), "am", "test", "of", "words" kept
            for (String token : result.tokens()) {
                assertThat(token.length()).isGreaterThanOrEqualTo(2);
            }
        }

        @Test
        void tokensAreLowercase() {
            var provider = new OllamaTokenEmbeddingProvider(fakeProvider);
            TokenEmbeddingResult result = provider.encode("Hello World Testing");
            for (String token : result.tokens()) {
                assertThat(token).isEqualTo(token.toLowerCase());
            }
        }

        @Test
        void projectionPadsWhenSourceShorter() {
            // Use a provider with tokenDims > source dims to test zero-padding
            var smallProvider = new FakeEmbeddingProvider() {
                @Override
                public int dimensions() { return 32; }
                @Override
                public EmbeddingResult embed(String text) {
                    float[] vec = new float[32];
                    Random rng = new Random(text.hashCode());
                    for (int i = 0; i < 32; i++) {
                        vec[i] = rng.nextFloat();
                    }
                    return new EmbeddingResult(vec, 1, "small-fake");
                }
            };
            var provider = new OllamaTokenEmbeddingProvider(smallProvider, 128);
            TokenEmbeddingResult result = provider.encode("test padding");
            for (float[] tokenEmb : result.embeddings()) {
                assertThat(tokenEmb.length).isEqualTo(128);
                // Last 96 dimensions should be zero (padding)
                for (int i = 32; i < 128; i++) {
                    assertThat(tokenEmb[i]).isEqualTo(0f);
                }
            }
        }
    }

    @Nested
    class ModelNameTests {

        @Test
        void modelNameIncludesBaseModel() {
            var provider = new OllamaTokenEmbeddingProvider(fakeProvider);
            assertThat(provider.modelName()).isEqualTo("dense-derived-colbert/fake-embed");
        }
    }
}
