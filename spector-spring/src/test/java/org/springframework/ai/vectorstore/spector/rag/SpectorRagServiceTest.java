package org.springframework.ai.vectorstore.spector.rag;

import com.spectrayan.spector.commons.error.SpectorValidationException;

import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.rag.ContextBuilder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.spector.SpectorVectorStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SpectorRagService}.
 */
class SpectorRagServiceTest {

    private SpectorEngine engine;
    private SpectorVectorStore vectorStore;
    private ContextBuilder contextBuilder;
    private SpectorRagService ragService;
    private static final int DIMS = 4;

    @BeforeEach
    void setUp() {
        engine = SpectorEngine.builder()
                .dimensions(DIMS)
                .capacity(100)
                .build();
        vectorStore = new SpectorVectorStore(engine);
        contextBuilder = new ContextBuilder();
        ragService = new SpectorRagService(vectorStore, contextBuilder);
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    void retrieve_withMatchingDocuments_returnsScoredResults() {
        // Ingest documents with known embeddings
        addDocument("doc-1", "The quick brown fox", new float[]{1.0f, 0.0f, 0.0f, 0.0f});
        addDocument("doc-2", "Lazy dog sleeps", new float[]{0.0f, 1.0f, 0.0f, 0.0f});
        addDocument("doc-3", "Fox and dog together", new float[]{0.7f, 0.7f, 0.0f, 0.0f});

        // Query close to doc-1
        float[] query = {1.0f, 0.0f, 0.0f, 0.0f};
        RagConfig config = new RagConfig(5, 0.0f, 4096);

        RetrievalResult result = ragService.retrieve(query, config);

        assertThat(result).isNotNull();
        assertThat(result.isEmpty()).isFalse();
        assertThat(result.documents()).isNotEmpty();

        // All scores should be in [0.0, 1.0]
        for (ScoredDocument doc : result.documents()) {
            assertThat(doc.score()).isBetween(0.0f, 1.0f);
            assertThat(doc.documentId()).isNotBlank();
        }
    }

    @Test
    void retrieve_withHighThreshold_returnsEmptyWhenNoMatch() {
        addDocument("doc-1", "The quick brown fox", new float[]{1.0f, 0.0f, 0.0f, 0.0f});

        // Query orthogonal to doc-1, with high threshold
        float[] query = {0.0f, 0.0f, 0.0f, 1.0f};
        RagConfig config = new RagConfig(5, 0.99f, 4096);

        RetrievalResult result = ragService.retrieve(query, config);

        assertThat(result).isNotNull();
        assertThat(result.isEmpty()).isTrue();
        assertThat(result.documents()).isEmpty();
        assertThat(result.contextText()).isEmpty();
    }

    @Test
    void retrieve_withNoDocumentsIngested_returnsEmpty() {
        float[] query = {1.0f, 0.0f, 0.0f, 0.0f};
        RagConfig config = RagConfig.defaults();

        RetrievalResult result = ragService.retrieve(query, config);

        assertThat(result).isNotNull();
        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void retrieve_scoresAreClamped() {
        addDocument("doc-1", "Test content", new float[]{1.0f, 0.0f, 0.0f, 0.0f});

        float[] query = {1.0f, 0.0f, 0.0f, 0.0f};
        RagConfig config = new RagConfig(5, 0.0f, 4096);

        RetrievalResult result = ragService.retrieve(query, config);

        for (ScoredDocument doc : result.documents()) {
            assertThat(doc.score()).isGreaterThanOrEqualTo(0.0f);
            assertThat(doc.score()).isLessThanOrEqualTo(1.0f);
        }
    }

    @Test
    void retrieve_withNullQuery_throwsException() {
        RagConfig config = RagConfig.defaults();

        assertThatThrownBy(() -> ragService.retrieve(null, config))
                .isInstanceOf(SpectorValidationException.class)
                .hasMessageContaining("queryEmbedding");
    }

    @Test
    void retrieve_withEmptyQuery_throwsException() {
        RagConfig config = RagConfig.defaults();

        assertThatThrownBy(() -> ragService.retrieve(new float[0], config))
                .isInstanceOf(SpectorValidationException.class)
                .hasMessageContaining("queryEmbedding");
    }

    @Test
    void retrieve_withNullConfig_throwsException() {
        float[] query = {1.0f, 0.0f, 0.0f, 0.0f};

        assertThatThrownBy(() -> ragService.retrieve(query, null))
                .isInstanceOf(SpectorValidationException.class)
                .hasMessageContaining("config");
    }

    @Test
    void constructor_withNullVectorStore_throwsException() {
        assertThatThrownBy(() -> new SpectorRagService(null, contextBuilder))
                .isInstanceOf(SpectorValidationException.class)
                .hasMessageContaining("vectorStore");
    }

    @Test
    void constructor_withNullContextBuilder_throwsException() {
        assertThatThrownBy(() -> new SpectorRagService(vectorStore, null))
                .isInstanceOf(SpectorValidationException.class)
                .hasMessageContaining("contextBuilder");
    }

    @Test
    void ragConfig_defaults() {
        RagConfig config = RagConfig.defaults();

        assertThat(config.topK()).isEqualTo(5);
        assertThat(config.similarityThreshold()).isEqualTo(0.7f);
        assertThat(config.tokenLimit()).isEqualTo(4096);
    }

    @Test
    void ragConfig_invalidTopK_throwsException() {
        assertThatThrownBy(() -> new RagConfig(0, 0.5f, 4096))
                .isInstanceOf(SpectorValidationException.class);
        assertThatThrownBy(() -> new RagConfig(101, 0.5f, 4096))
                .isInstanceOf(SpectorValidationException.class);
    }

    @Test
    void ragConfig_invalidThreshold_throwsException() {
        assertThatThrownBy(() -> new RagConfig(5, -0.1f, 4096))
                .isInstanceOf(SpectorValidationException.class);
        assertThatThrownBy(() -> new RagConfig(5, 1.1f, 4096))
                .isInstanceOf(SpectorValidationException.class);
    }

    @Test
    void ragConfig_invalidTokenLimit_throwsException() {
        assertThatThrownBy(() -> new RagConfig(5, 0.5f, 0))
                .isInstanceOf(SpectorValidationException.class);
        assertThatThrownBy(() -> new RagConfig(5, 0.5f, 8193))
                .isInstanceOf(SpectorValidationException.class);
    }

    // ─── Helpers ───

    private void addDocument(String id, String content, float[] embedding) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", "test");
        metadata.put(SpectorVectorStore.EMBEDDING_METADATA_KEY, embedding);
        Document doc = new Document(id, content, metadata);
        vectorStore.add(List.of(doc));
    }
}
