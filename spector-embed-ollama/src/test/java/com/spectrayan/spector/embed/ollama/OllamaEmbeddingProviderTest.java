package com.spectrayan.spector.embed.ollama;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spectrayan.spector.embed.EmbeddingConfig;
import com.spectrayan.spector.embed.EmbeddingException;

import org.junit.jupiter.api.Test;

import java.time.Duration;

/**
 * Unit tests for {@link OllamaEmbeddingProvider}.
 *
 * <p>These tests verify configuration, factory methods, and error handling
 * without requiring a running Ollama server.</p>
 */
class OllamaEmbeddingProviderTest {

    @Test
    void createWithModel() {
        var provider = OllamaEmbeddingProvider.create("all-minilm");
        assertThat(provider.modelName()).isEqualTo("all-minilm");
        assertThat(provider.config().baseUrl()).isEqualTo("http://localhost:11434");
    }

    @Test
    void createDefault() {
        var provider = OllamaEmbeddingProvider.createDefault();
        assertThat(provider.modelName()).isEqualTo("nomic-embed-text");
    }

    @Test
    void customConfig() {
        var config = new EmbeddingConfig("mxbai-embed-large", "http://gpu-server:11434",
                Duration.ofSeconds(60), 16);
        var provider = new OllamaEmbeddingProvider(config);
        assertThat(provider.modelName()).isEqualTo("mxbai-embed-large");
        assertThat(provider.config().baseUrl()).isEqualTo("http://gpu-server:11434");
        assertThat(provider.config().batchSize()).isEqualTo(16);
    }

    @Test
    void embedNullTextThrows() {
        var provider = OllamaEmbeddingProvider.create("test");
        assertThatThrownBy(() -> provider.embed(null))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void embedBlankTextThrows() {
        var provider = OllamaEmbeddingProvider.create("test");
        assertThatThrownBy(() -> provider.embed("  "))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void embedBatchEmptyReturnsEmpty() {
        var provider = OllamaEmbeddingProvider.create("test");
        assertThat(provider.embedBatch(java.util.List.of())).isEmpty();
    }

    @Test
    void embedFailsWhenServerUnavailable() {
        var config = EmbeddingConfig.ollama("test")
                .withBaseUrl("http://localhost:19999") // unlikely to be running
                .withTimeout(Duration.ofMillis(500));
        var provider = new OllamaEmbeddingProvider(config);
        assertThatThrownBy(() -> provider.embed("test text"))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("Failed");
    }
}
