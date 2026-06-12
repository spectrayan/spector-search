/*
 * Copyright 2026 Spectrayan
 */
package com.spectrayan.spector.node.api.dto;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.query.SearchQuery;

/**
 * Tests for API request/response DTOs — IngestRequest, SearchRequest,
 * BulkIngestRequest validation and conversion methods.
 */
@DisplayName("Node API Request DTOs")
class NodeRequestDtoTest {

    // ══════════════════════════════════════════════════════════════
    // IngestRequest
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("IngestRequest")
    class IngestRequestTests {

        @Test @DisplayName("titleOrEmpty returns title when present")
        void titlePresent() {
            var req = new IngestRequest();
            req.title = "My Title";
            assertThat(req.titleOrEmpty()).isEqualTo("My Title");
        }

        @Test @DisplayName("titleOrEmpty returns empty when null")
        void titleNull() {
            var req = new IngestRequest();
            assertThat(req.titleOrEmpty()).isEmpty();
        }

        @Test @DisplayName("validateForIngest passes with valid inputs")
        void validForIngest() {
            var req = new IngestRequest();
            req.id = "doc-1";
            req.content = "Hello";
            req.vector = new float[384];
            assertThatCode(() -> req.validateForIngest(384)).doesNotThrowAnyException();
        }

        @Test @DisplayName("validateForIngest rejects null id")
        void nullId() {
            var req = new IngestRequest();
            req.content = "text";
            req.vector = new float[384];
            assertThatThrownBy(() -> req.validateForIngest(384))
                    .isInstanceOf(SpectorValidationException.class);
        }

        @Test @DisplayName("validateForIngest rejects empty id")
        void emptyId() {
            var req = new IngestRequest();
            req.id = "";
            req.content = "text";
            req.vector = new float[384];
            assertThatThrownBy(() -> req.validateForIngest(384))
                    .isInstanceOf(SpectorValidationException.class);
        }

        @Test @DisplayName("validateForIngest rejects null content")
        void nullContent() {
            var req = new IngestRequest();
            req.id = "doc-1";
            req.vector = new float[384];
            assertThatThrownBy(() -> req.validateForIngest(384))
                    .isInstanceOf(SpectorValidationException.class);
        }

        @Test @DisplayName("validateForIngest rejects null vector")
        void nullVector() {
            var req = new IngestRequest();
            req.id = "doc-1";
            req.content = "text";
            assertThatThrownBy(() -> req.validateForIngest(384))
                    .isInstanceOf(SpectorValidationException.class);
        }

        @Test @DisplayName("validateForIngest rejects wrong dimension")
        void wrongDimension() {
            var req = new IngestRequest();
            req.id = "doc-1";
            req.content = "text";
            req.vector = new float[768];
            assertThatThrownBy(() -> req.validateForIngest(384))
                    .isInstanceOf(SpectorValidationException.class);
        }

        @Test @DisplayName("validateForAutoIngest passes with id and content")
        void validAutoIngest() {
            var req = new IngestRequest();
            req.id = "doc-1";
            req.content = "text";
            assertThatCode(req::validateForAutoIngest).doesNotThrowAnyException();
        }

        @Test @DisplayName("validateForAutoIngest rejects missing id")
        void autoIngestNoId() {
            var req = new IngestRequest();
            req.content = "text";
            assertThatThrownBy(req::validateForAutoIngest)
                    .isInstanceOf(SpectorValidationException.class);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // SearchRequest
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("SearchRequest")
    class SearchRequestTests {

        @Test @DisplayName("resolvedMode auto-detects KEYWORD")
        void keywordMode() {
            var req = new SearchRequest();
            req.text = "hello";
            assertThat(req.resolvedMode()).isEqualTo(SearchQuery.SearchMode.KEYWORD);
        }

        @Test @DisplayName("resolvedMode auto-detects VECTOR")
        void vectorMode() {
            var req = new SearchRequest();
            req.vector = new float[]{1f, 2f};
            assertThat(req.resolvedMode()).isEqualTo(SearchQuery.SearchMode.VECTOR);
        }

        @Test @DisplayName("resolvedMode auto-detects HYBRID")
        void hybridMode() {
            var req = new SearchRequest();
            req.text = "hello";
            req.vector = new float[]{1f, 2f};
            assertThat(req.resolvedMode()).isEqualTo(SearchQuery.SearchMode.HYBRID);
        }

        @Test @DisplayName("resolvedMode honors explicit mode")
        void explicitMode() {
            var req = new SearchRequest();
            req.mode = "VECTOR";
            req.vector = new float[]{1f};
            assertThat(req.resolvedMode()).isEqualTo(SearchQuery.SearchMode.VECTOR);
        }

        @Test @DisplayName("resolvedMode ignores invalid mode and auto-detects")
        void invalidModeFallback() {
            var req = new SearchRequest();
            req.mode = "NONSENSE";
            req.text = "hello";
            assertThat(req.resolvedMode()).isEqualTo(SearchQuery.SearchMode.KEYWORD);
        }

        @Test @DisplayName("toQuery creates keyword query")
        void toQueryKeyword() {
            var req = new SearchRequest();
            req.text = "hello";
            req.topK = 5;
            var query = req.toQuery();
            assertThat(query.mode()).isEqualTo(SearchQuery.SearchMode.KEYWORD);
            assertThat(query.text()).isEqualTo("hello");
            assertThat(query.topK()).isEqualTo(5);
        }

        @Test @DisplayName("toQuery defaults topK to 10")
        void defaultTopK() {
            var req = new SearchRequest();
            req.text = "hello";
            var query = req.toQuery();
            assertThat(query.topK()).isEqualTo(10);
        }

        @Test @DisplayName("toQuery rejects keyword with no text")
        void keywordNoText() {
            var req = new SearchRequest();
            req.mode = "KEYWORD";
            assertThatThrownBy(req::toQuery)
                    .isInstanceOf(SpectorValidationException.class);
        }

        @Test @DisplayName("toQuery rejects vector with no vector")
        void vectorNoVector() {
            var req = new SearchRequest();
            req.mode = "VECTOR";
            assertThatThrownBy(req::toQuery)
                    .isInstanceOf(SpectorValidationException.class);
        }

        @Test @DisplayName("toQuery creates hybrid query")
        void toQueryHybrid() {
            var req = new SearchRequest();
            req.text = "hello";
            req.vector = new float[]{1f, 2f};
            req.topK = 3;
            var query = req.toQuery();
            assertThat(query.mode()).isEqualTo(SearchQuery.SearchMode.HYBRID);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // BulkIngestRequest
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("BulkIngestRequest")
    class BulkIngestRequestTests {

        @Test @DisplayName("validate passes with documents")
        void validBulk() {
            var doc = new IngestRequest();
            doc.id = "d1"; doc.content = "c1"; doc.vector = new float[384];
            var bulk = new BulkIngestRequest();
            bulk.documents = List.of(doc);
            assertThatCode(bulk::validate).doesNotThrowAnyException();
        }

        @Test @DisplayName("validate rejects null documents")
        void nullDocuments() {
            var bulk = new BulkIngestRequest();
            assertThatThrownBy(bulk::validate)
                    .isInstanceOf(SpectorValidationException.class);
        }

        @Test @DisplayName("validate rejects empty documents")
        void emptyDocuments() {
            var bulk = new BulkIngestRequest();
            bulk.documents = List.of();
            assertThatThrownBy(bulk::validate)
                    .isInstanceOf(SpectorValidationException.class);
        }
    }
}
