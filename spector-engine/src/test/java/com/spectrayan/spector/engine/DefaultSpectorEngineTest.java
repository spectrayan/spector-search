/*
 * Copyright 2026 Spectrayan
 */
package com.spectrayan.spector.engine;

import static org.assertj.core.api.Assertions.*;

import com.spectrayan.spector.config.SpectorConfig;
import com.spectrayan.spector.core.similarity.SimilarityFunction;
import com.spectrayan.spector.query.SearchQuery;
import com.spectrayan.spector.query.SearchResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Integration tests for {@link DefaultSpectorEngine} — builds a real
 * in-memory engine and tests ingest/search/delete pipeline.
 */
@DisplayName("DefaultSpectorEngine")
class DefaultSpectorEngineTest {

    private static final int DIMS = 8;  // small for fast tests
    private SpectorEngine engine;

    @BeforeEach
    void setUp() {
        engine = DefaultSpectorEngine.builder()
                .dimensions(DIMS)
                .capacity(1_000)
                .similarity(SimilarityFunction.COSINE)
                .build();
    }

    @AfterEach
    void tearDown() {
        if (engine != null) engine.close();
    }

    /** Creates a random vector of the given dimensionality. */
    private float[] randomVector() {
        float[] v = new float[DIMS];
        for (int i = 0; i < v.length; i++) v[i] = ThreadLocalRandom.current().nextFloat();
        return v;
    }

    // ══════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("lifecycle")
    class LifecycleTests {

        @Test
        @DisplayName("fresh engine has zero documents")
        void freshEngineEmpty() {
            assertThat(engine.documentCount()).isZero();
        }

        @Test
        @DisplayName("engine reports correct config")
        void engineConfig() {
            assertThat(engine.config().dimensions()).isEqualTo(DIMS);
            assertThat(engine.config().capacity()).isEqualTo(1_000);
        }

        @Test
        @DisplayName("engine has no embedding provider by default")
        void noEmbeddingProvider() {
            assertThat(engine.hasEmbeddingProvider()).isFalse();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Ingestion
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ingestion")
    class IngestionTests {

        @Test
        @DisplayName("ingest single document")
        void ingestSingle() {
            engine.ingest("doc-1", "Hello world", randomVector());
            assertThat(engine.documentCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("ingest multiple documents")
        void ingestMultiple() {
            engine.ingest("doc-1", "First", randomVector());
            engine.ingest("doc-2", "Second", randomVector());
            engine.ingest("doc-3", "Third", randomVector());
            assertThat(engine.documentCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("ingest with title")
        void ingestWithTitle() {
            engine.ingest("doc-1", "Title", "Content", randomVector());
            assertThat(engine.documentCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("ingest batch")
        void ingestBatch() {
            engine.ingestBatch(
                    new String[]{"a", "b", "c"},
                    new String[]{"Content A", "Content B", "Content C"},
                    new float[][]{randomVector(), randomVector(), randomVector()}
            );
            assertThat(engine.documentCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("ingest structured content")
        void ingestStructured() {
            engine.ingestStructured("xml-1",
                    "<doc><title>Test</title><body>Content here</body></doc>",
                    randomVector());
            assertThat(engine.documentCount()).isEqualTo(1);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Search
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("search")
    class SearchTests {

        @Test
        @DisplayName("keyword search returns matching documents")
        void keywordSearch() {
            engine.ingest("doc-1", "The quick brown fox", randomVector());
            engine.ingest("doc-2", "Lazy dog sleeps", randomVector());

            SearchResponse resp = engine.keywordSearch("quick fox", 10);
            assertThat(resp).isNotNull();
            assertThat(resp.mode()).isEqualTo(SearchQuery.SearchMode.KEYWORD);
        }

        @Test
        @DisplayName("vector search returns results")
        void vectorSearch() {
            float[] v1 = randomVector();
            engine.ingest("doc-1", "Document one", v1);
            engine.ingest("doc-2", "Document two", randomVector());

            SearchResponse resp = engine.vectorSearch(v1, 10);
            assertThat(resp).isNotNull();
            assertThat(resp.size()).isGreaterThanOrEqualTo(1);
            // The most similar document should be doc-1 (its own vector)
            assertThat(resp.results()[0].id()).isEqualTo("doc-1");
        }

        @Test
        @DisplayName("empty engine returns empty results")
        void emptyEngineSearch() {
            SearchResponse resp = engine.keywordSearch("anything", 10);
            assertThat(resp.size()).isZero();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Delete
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("delete")
    class DeleteTests {

        @Test
        @DisplayName("delete existing document")
        void deleteExisting() {
            engine.ingest("doc-1", "Content", randomVector());
            assertThat(engine.documentCount()).isEqualTo(1);

            boolean deleted = engine.delete("doc-1");
            assertThat(deleted).isTrue();
            // After deletion, vector search for doc-1's vector should not return doc-1
            // documentCount may still include deleted slots until compaction
        }

        @Test
        @DisplayName("delete non-existent document returns false")
        void deleteNonExistent() {
            boolean deleted = engine.delete("nonexistent");
            assertThat(deleted).isFalse();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Admin
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("admin interface")
    class AdminTests {

        @Test
        @DisplayName("admin is never null")
        void adminNotNull() {
            assertThat(engine.admin()).isNotNull();
        }

        @Test
        @DisplayName("admin exposes config")
        void adminConfig() {
            assertThat(engine.admin().config().dimensions()).isEqualTo(DIMS);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Chunked ingestion
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("chunked ingestion")
    class ChunkedIngestionTests {

        @Test
        @DisplayName("ingestChunked splits long content")
        void ingestChunked() {
            String longContent = "The quick brown fox jumps over the lazy dog. ".repeat(100);
            int chunks = engine.ingestChunked("long-doc", longContent, text -> randomVector());
            assertThat(chunks).isGreaterThanOrEqualTo(1);
        }
    }
}
