/*
 * Copyright 2026 Spectrayan
 */
package com.spectrayan.spector.client;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.spectrayan.spector.client.model.*;

/**
 * Tests for all spector-client DTOs — request/response classes.
 */
@DisplayName("Spector Client DTOs")
class ClientDtoExtendedTest {

    @Nested
    @DisplayName("SearchRequest")
    class SearchRequestTests {
        @Test void keyword() {
            var req = SearchRequest.keyword("hello", 10);
            assertThat(req.getText()).isEqualTo("hello");
            assertThat(req.getTopK()).isEqualTo(10);
        }

        @Test void defaultConstructor() {
            var req = new SearchRequest();
            assertThat(req.getText()).isNull();
        }
    }

    @Nested
    @DisplayName("IngestRequest")
    class IngestRequestTests {
        @Test void withVector() {
            var req = new IngestRequest("doc-1", "content", new float[]{1f, 2f});
            assertThat(req.getId()).isEqualTo("doc-1");
            assertThat(req.getContent()).isEqualTo("content");
            assertThat(req.getVector()).hasSize(2);
        }

        @Test void defaultConstructor() {
            var req = new IngestRequest();
            assertThat(req.getId()).isNull();
        }
    }

    @Nested
    @DisplayName("IngestResponse")
    class IngestResponseTests {
        @Test void defaultConstructor() {
            var resp = new IngestResponse();
            assertThat(resp.getId()).isNull();
        }
    }

    @Nested
    @DisplayName("DeleteResponse")
    class DeleteResponseTests {
        @Test void defaultConstructor() {
            var resp = new DeleteResponse();
            assertThat(resp.getId()).isNull();
        }
    }

    @Nested
    @DisplayName("StatusResponse")
    class StatusResponseTests {
        @Test void defaultConstructor() {
            var resp = new StatusResponse();
            assertThat(resp.getEngine()).isNull();
        }
    }

    @Nested
    @DisplayName("MetricsResponse")
    class MetricsResponseTests {
        @Test void defaultConstructor() {
            var resp = new MetricsResponse();
            assertThat(resp.getUptimeMs()).isZero();
        }
    }

    @Nested
    @DisplayName("BulkIngestRequest")
    class BulkIngestRequestTests {
        @Test void withDocuments() {
            var doc = new IngestRequest("doc-1", "text", null);
            var req = new BulkIngestRequest(List.of(doc));
            assertThat(req.getDocuments()).hasSize(1);
        }

        @Test void defaultConstructor() {
            var req = new BulkIngestRequest();
            assertThat(req.getDocuments()).isNull();
        }
    }

    @Nested
    @DisplayName("Exception classes")
    class ExceptionTests {
        @Test void clientException() {
            var ex = new SpectorClientException("oops");
            assertThat(ex.getMessage()).isEqualTo("oops");
        }

        @Test void clientExceptionWithCause() {
            var cause = new RuntimeException("root");
            var ex = new SpectorClientException("oops", cause);
            assertThat(ex.getCause()).isEqualTo(cause);
        }

        @Test void connectionException() {
            var ex = new SpectorConnectionException("localhost", 8080, new java.io.IOException("refused"));
            assertThat(ex.getMessage()).contains("localhost");
        }

        @Test void httpException() {
            var ex = new SpectorHttpException(404, "Not Found", "/api/v1/search");
            assertThat(ex.statusCode()).isEqualTo(404);
            assertThat(ex.errorMessage()).isEqualTo("Not Found");
            assertThat(ex.requestUrl()).isEqualTo("/api/v1/search");
        }
    }
}
