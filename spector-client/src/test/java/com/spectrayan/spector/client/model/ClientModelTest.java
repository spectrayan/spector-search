/*
 * Copyright 2026 Spectrayan
 */
package com.spectrayan.spector.client.model;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for client model classes — SearchRequest, IngestRequest, and related DTOs.
 */
@DisplayName("Client Model")
class ClientModelTest {

    // ══════════════════════════════════════════════════════════════
    // SearchRequest
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("SearchRequest")
    class SearchRequestTests {

        @Test
        @DisplayName("keyword search factory")
        void keywordFactory() {
            var req = SearchRequest.keyword("test query", 5);
            assertThat(req.getText()).isEqualTo("test query");
            assertThat(req.getMode()).isEqualTo("KEYWORD");
            assertThat(req.getTopK()).isEqualTo(5);
            assertThat(req.getVector()).isNull();
        }

        @Test
        @DisplayName("vector search factory")
        void vectorFactory() {
            float[] vec = {0.1f, 0.2f, 0.3f};
            var req = SearchRequest.vector(vec, 10);
            assertThat(req.getVector()).containsExactly(0.1f, 0.2f, 0.3f);
            assertThat(req.getMode()).isEqualTo("VECTOR");
            assertThat(req.getTopK()).isEqualTo(10);
            assertThat(req.getText()).isNull();
        }

        @Test
        @DisplayName("hybrid search factory")
        void hybridFactory() {
            float[] vec = {1.0f};
            var req = SearchRequest.hybrid("query", vec, 20);
            assertThat(req.getText()).isEqualTo("query");
            assertThat(req.getVector()).containsExactly(1.0f);
            assertThat(req.getMode()).isEqualTo("HYBRID");
            assertThat(req.getTopK()).isEqualTo(20);
        }

        @Test
        @DisplayName("default constructor defaults topK to 10")
        void defaultTopK() {
            var req = new SearchRequest();
            assertThat(req.getTopK()).isEqualTo(10);
        }

        @Test
        @DisplayName("setters work correctly")
        void setters() {
            var req = new SearchRequest();
            req.setText("text");
            req.setMode("VECTOR");
            req.setTopK(50);
            req.setVector(new float[]{1.0f});
            assertThat(req.getText()).isEqualTo("text");
            assertThat(req.getMode()).isEqualTo("VECTOR");
            assertThat(req.getTopK()).isEqualTo(50);
            assertThat(req.getVector()).hasSize(1);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // IngestRequest
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("IngestRequest")
    class IngestRequestTests {

        @Test
        @DisplayName("3-arg constructor sets id, content, vector")
        void threeArgConstructor() {
            float[] vec = {0.5f};
            var req = new IngestRequest("doc-1", "Some content", vec);
            assertThat(req.getId()).isEqualTo("doc-1");
            assertThat(req.getContent()).isEqualTo("Some content");
            assertThat(req.getVector()).containsExactly(0.5f);
            assertThat(req.getTitle()).isNull();
        }

        @Test
        @DisplayName("4-arg constructor includes title")
        void fourArgConstructor() {
            var req = new IngestRequest("doc-2", "My Title", "Content", new float[]{1.0f});
            assertThat(req.getId()).isEqualTo("doc-2");
            assertThat(req.getTitle()).isEqualTo("My Title");
            assertThat(req.getContent()).isEqualTo("Content");
        }

        @Test
        @DisplayName("default constructor creates empty request")
        void defaultConstructor() {
            var req = new IngestRequest();
            assertThat(req.getId()).isNull();
            assertThat(req.getTitle()).isNull();
            assertThat(req.getContent()).isNull();
            assertThat(req.getVector()).isNull();
        }

        @Test
        @DisplayName("setters work correctly")
        void setters() {
            var req = new IngestRequest();
            req.setId("id");
            req.setTitle("title");
            req.setContent("content");
            req.setVector(new float[]{2.0f});
            assertThat(req.getId()).isEqualTo("id");
            assertThat(req.getTitle()).isEqualTo("title");
            assertThat(req.getContent()).isEqualTo("content");
        }
    }
}
