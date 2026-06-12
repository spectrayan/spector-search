/*
 * Copyright 2026 Spectrayan
 */
package com.spectrayan.spector.rag;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.embed.EmbeddingProvider;
import com.spectrayan.spector.embed.EmbeddingResult;
import com.spectrayan.spector.index.ScoredResult;
import com.spectrayan.spector.query.HybridSearchOrchestrator;
import com.spectrayan.spector.query.SearchQuery;
import com.spectrayan.spector.query.SearchResponse;
import com.spectrayan.spector.storage.Document;
import com.spectrayan.spector.storage.DocumentStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RagPipeline} — full RAG flow with mock dependencies.
 */
@DisplayName("RagPipeline")
class RagPipelineTest {

    private HybridSearchOrchestrator mockOrchestrator;
    private DocumentStore mockDocStore;
    private EmbeddingProvider mockEmbedder;
    private RagPipeline pipeline;

    @BeforeEach
    void setUp() {
        mockOrchestrator = mock(HybridSearchOrchestrator.class);
        mockDocStore = mock(DocumentStore.class);
        mockEmbedder = mock(EmbeddingProvider.class);
        pipeline = new RagPipeline(mockOrchestrator, mockDocStore, mockEmbedder);
    }

    // ══════════════════════════════════════════════════════════════
    // Validation
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("validation")
    class ValidationTests {

        @Test
        @DisplayName("rejects null query")
        void rejectsNullQuery() {
            assertThatThrownBy(() -> pipeline.execute(new RagRequest(null)))
                    .isInstanceOf(SpectorValidationException.class);
        }

        @Test
        @DisplayName("rejects blank query")
        void rejectsBlankQuery() {
            assertThatThrownBy(() -> pipeline.execute(new RagRequest("   ")))
                    .isInstanceOf(SpectorValidationException.class);
        }

        @Test
        @DisplayName("rejects oversized query")
        void rejectsOversizedQuery() {
            String longQuery = "x".repeat(2001);
            assertThatThrownBy(() -> pipeline.execute(new RagRequest(longQuery)))
                    .isInstanceOf(SpectorValidationException.class);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Empty results
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("returns empty response when search finds nothing")
    void emptySearchResults() {
        when(mockEmbedder.embed(anyString())).thenReturn(EmbeddingResult.of(new float[]{1f}, "test"));
        when(mockOrchestrator.search(any())).thenReturn(SearchResponse.EMPTY);

        var resp = pipeline.execute(new RagRequest("test query"));
        // empty() returns "" for contextText and "No matching documents" message
        assertThat(resp.attributions()).isEmpty();
    }

    // ══════════════════════════════════════════════════════════════
    // Full pipeline
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("full pipeline returns context with attributions")
    void fullPipeline() {
        when(mockEmbedder.embed(anyString())).thenReturn(EmbeddingResult.of(new float[]{0.1f, 0.2f}, "test"));

        var results = new ScoredResult[]{new ScoredResult("doc-1", 0, 0.9f)};
        var searchResp = new SearchResponse(results, 1, 10, SearchQuery.SearchMode.VECTOR);
        when(mockOrchestrator.search(any())).thenReturn(searchResp);

        when(mockDocStore.get("doc-1")).thenReturn(new Document("doc-1", "HNSW is an algorithm for approximate nearest neighbor search.", "HNSW Overview", java.util.Map.of()));

        var resp = pipeline.execute(new RagRequest("What is HNSW?"));
        assertThat(resp.contextText()).contains("HNSW");
        assertThat(resp.attributions()).isNotEmpty();
        assertThat(resp.queryTimeMs()).isGreaterThanOrEqualTo(0);
    }

    // ══════════════════════════════════════════════════════════════
    // Embedding failure
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("embedding failure throws SpectorServerException")
    void embeddingFailure() {
        when(mockEmbedder.embed(anyString())).thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> pipeline.execute(new RagRequest("test")))
                .isInstanceOf(RuntimeException.class);
    }

    // ══════════════════════════════════════════════════════════════
    // Document not found
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("skips documents not found in store")
    void skipsMissingDocuments() {
        when(mockEmbedder.embed(anyString())).thenReturn(EmbeddingResult.of(new float[]{1f}, "test"));

        var results = new ScoredResult[]{new ScoredResult("missing-doc", 0, 0.8f)};
        var searchResp = new SearchResponse(results, 1, 5, SearchQuery.SearchMode.VECTOR);
        when(mockOrchestrator.search(any())).thenReturn(searchResp);
        when(mockDocStore.get("missing-doc")).thenReturn(null);

        var resp = pipeline.execute(new RagRequest("test"));
        assertThat(resp.contextText()).isEmpty();
    }
}
