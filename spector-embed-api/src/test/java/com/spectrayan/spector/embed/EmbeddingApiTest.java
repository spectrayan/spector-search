package com.spectrayan.spector.embed;

import com.spectrayan.spector.commons.error.SpectorEmbeddingException;
import com.spectrayan.spector.commons.error.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for the embed API contracts.
 */
class EmbeddingApiTest {

    @Test
    void embeddingResultOf() {
        float[] vec = {0.1f, 0.2f, 0.3f};
        EmbeddingResult result = EmbeddingResult.of(vec, "test-model");
        assertThat(result.vector()).isEqualTo(vec);
        assertThat(result.tokenCount()).isEqualTo(-1);
        assertThat(result.model()).isEqualTo("test-model");
        assertThat(result.dimensions()).isEqualTo(3);
    }

    @Test
    void embeddingResultWithTokenCount() {
        float[] vec = new float[384];
        EmbeddingResult result = new EmbeddingResult(vec, 42, "model-v2");
        assertThat(result.tokenCount()).isEqualTo(42);
        assertThat(result.dimensions()).isEqualTo(384);
    }

    @Test
    void embeddingConfigDefaults() {
        EmbeddingConfig config = EmbeddingConfig.OLLAMA_DEFAULT;
        assertThat(config.model()).isEqualTo("nomic-embed-text");
        assertThat(config.baseUrl()).isEqualTo("http://localhost:11434");
        assertThat(config.batchSize()).isEqualTo(32);
    }

    @Test
    void embeddingConfigOllamaFactory() {
        EmbeddingConfig config = EmbeddingConfig.ollama("all-minilm");
        assertThat(config.model()).isEqualTo("all-minilm");
        assertThat(config.baseUrl()).isEqualTo("http://localhost:11434");
    }

    @Test
    void embeddingConfigWithMethods() {
        EmbeddingConfig config = EmbeddingConfig.OLLAMA_DEFAULT
                .withBaseUrl("http://remote:11434")
                .withBatchSize(64);
        assertThat(config.baseUrl()).isEqualTo("http://remote:11434");
        assertThat(config.batchSize()).isEqualTo(64);
        assertThat(config.model()).isEqualTo("nomic-embed-text");
    }

    @Test
    void embeddingExceptionMessage() {
        var ex = new SpectorEmbeddingException(ErrorCode.EMBEDDING_REQUEST_FAILED, "test error");
        assertThat(ex.getMessage()).contains("test error").contains("SPE-300-002");
    }

    @Test
    void embeddingExceptionWithCause() {
        var cause = new RuntimeException("root");
        var ex = new SpectorEmbeddingException(ErrorCode.EMBEDDING_REQUEST_FAILED, cause, "wrapper");
        assertThat(ex.getCause()).isEqualTo(cause);
    }

    @Test
    void defaultMaxTokens() {
        EmbeddingProvider provider = new StubProvider();
        assertThat(provider.maxTokens()).isEqualTo(512);
    }

    @Test
    void defaultEmbedBatchDelegatesToEmbed() {
        var provider = new StubProvider();
        var results = provider.embedBatch(java.util.List.of("a", "b", "c"));
        assertThat(results).hasSize(3);
        assertThat(results.get(0).dimensions()).isEqualTo(4);
    }

    /** Minimal stub for testing default methods. */
    private static class StubProvider implements EmbeddingProvider {
        @Override
        public EmbeddingResult embed(String text) {
            return new EmbeddingResult(new float[]{1, 2, 3, 4}, text.length(), "stub");
        }

        @Override
        public int dimensions() { return 4; }

        @Override
        public String modelName() { return "stub"; }
    }
}
