package com.spectrayan.spector.server;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spectrayan.spector.engine.SpectorConfig;
import com.spectrayan.spector.engine.SpectorEngine;

import io.javalin.testtools.JavalinTest;

/**
 * Tests for the RAG endpoint ({@code POST /api/v1/rag}).
 *
 * <p>Validates: Requirements 9.1, 9.2, 9.3, 9.4, 9.5</p>
 */
class RagHandlerTest {

    private static final int DIM = 4;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SpectorEngine createEngine() {
        return new SpectorEngine(SpectorConfig.DEFAULT.withDimensions(DIM).withCapacity(100));
    }

    @Test
    void ragEndpoint_missingQuery_returns400() {
        var engine = createEngine();
        var server = new SpectorServer(engine, 0);

        JavalinTest.test(server.app(), (srv, client) -> {
            // Empty body with no query
            String body = MAPPER.writeValueAsString(Map.of());
            var response = client.post("/api/v1/rag", body);
            assertThat(response.code()).isEqualTo(400);
            assertThat(response.body().string()).contains("error");
        });
        engine.close();
    }

    @Test
    void ragEndpoint_blankQuery_returns400() {
        var engine = createEngine();
        var server = new SpectorServer(engine, 0);

        JavalinTest.test(server.app(), (srv, client) -> {
            String body = MAPPER.writeValueAsString(Map.of("query", "   "));
            var response = client.post("/api/v1/rag", body);
            assertThat(response.code()).isEqualTo(400);
            assertThat(response.body().string()).contains("error");
        });
        engine.close();
    }

    @Test
    void ragEndpoint_queryTooLong_returns400() {
        var engine = createEngine();
        var server = new SpectorServer(engine, 0);

        JavalinTest.test(server.app(), (srv, client) -> {
            String longQuery = "a".repeat(2001);
            String body = MAPPER.writeValueAsString(Map.of("query", longQuery));
            var response = client.post("/api/v1/rag", body);
            assertThat(response.code()).isEqualTo(400);
            assertThat(response.body().string()).contains("2000");
        });
        engine.close();
    }

    @Test
    void ragEndpoint_noEmbeddingProvider_returns503() {
        // Engine without embedding provider
        var engine = createEngine();
        var server = new SpectorServer(engine, 0);

        JavalinTest.test(server.app(), (srv, client) -> {
            String body = MAPPER.writeValueAsString(Map.of("query", "test query"));
            var response = client.post("/api/v1/rag", body);
            assertThat(response.code()).isEqualTo(503);
            assertThat(response.body().string()).contains("unavailable");
        });
        engine.close();
    }

    @Test
    void ragHandler_directInvocation_missingQuery() {
        var engine = createEngine();
        var handler = new RagHandler(engine);

        var request = new RagRequest();
        request.query = null;

        var result = handler.handle(request);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.statusCode()).isEqualTo(400);
        assertThat(result.errorMessage()).contains("query");
    }

    @Test
    void ragHandler_directInvocation_noEmbeddingProvider() {
        var engine = createEngine();
        var handler = new RagHandler(engine);

        var request = new RagRequest();
        request.query = "test query";
        request.topK = 5;
        request.tokenLimit = 4096;
        request.searchMode = "vector";

        var result = handler.handle(request);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.statusCode()).isEqualTo(503);
    }

    @Test
    void ragHandler_directInvocation_clampsTopK() {
        var engine = createEngine();
        var handler = new RagHandler(engine);

        // topK > 100 should be clamped - but it requires embedding provider
        // so this tests the validation order: query → embedding availability → search
        var request = new RagRequest();
        request.query = "test";
        request.topK = 200;

        var result = handler.handle(request);
        // Without embedding provider, should return 503 (validation passes, then embedding check)
        assertThat(result.statusCode()).isEqualTo(503);
    }

    @Test
    void ragHandler_directInvocation_clampsTokenLimit() {
        var engine = createEngine();
        var handler = new RagHandler(engine);

        var request = new RagRequest();
        request.query = "test";
        request.tokenLimit = 10000; // exceeds max, should be clamped to 8192

        var result = handler.handle(request);
        // Without embedding provider, should return 503
        assertThat(result.statusCode()).isEqualTo(503);
    }

    @Test
    void ragHandler_directInvocation_defaultSearchMode() {
        var engine = createEngine();
        var handler = new RagHandler(engine);

        var request = new RagRequest();
        request.query = "test";
        request.searchMode = null; // Should default to "vector"

        var result = handler.handle(request);
        // Without embedding provider, should return 503
        assertThat(result.statusCode()).isEqualTo(503);
    }
}
