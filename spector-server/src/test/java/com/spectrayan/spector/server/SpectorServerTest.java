package com.spectrayan.spector.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.spectrayan.spector.core.similarity.SimilarityFunction;
import com.spectrayan.spector.config.SpectorConfig;
import com.spectrayan.spector.engine.SpectorEngine;

import io.javalin.testtools.JavalinTest;

import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * Integration tests for {@link SpectorServer} REST endpoints.
 */
class SpectorServerTest {

    private static final int DIM = 4;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SpectorEngine createEngine() {
        return new SpectorEngine(SpectorConfig.DEFAULT.withDimensions(DIM).withCapacity(100));
    }

    @Test
    void healthEndpoint() {
        var engine = createEngine();
        var server = new SpectorServer(engine, 0);

        JavalinTest.test(server.app(), (srv, client) -> {
            var response = client.get("/health");
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string()).contains("ok");
        });
        engine.close();
    }

    @Test
    void statusEndpoint() {
        var engine = createEngine();
        var server = new SpectorServer(engine, 0);

        JavalinTest.test(server.app(), (srv, client) -> {
            var response = client.get("/api/v1/status");
            assertThat(response.code()).isEqualTo(200);
            String body = response.body().string();
            assertThat(body).contains("spector-search");
            assertThat(body).contains("dimensions");
        });
        engine.close();
    }

    @Test
    void ingestAndSearch() {
        var engine = createEngine();
        var server = new SpectorServer(engine, 0);

        JavalinTest.test(server.app(), (srv, client) -> {
            // Ingest
            String ingestBody = MAPPER.writeValueAsString(Map.of(
                    "id", "doc-1",
                    "content", "java search engine",
                    "vector", new float[]{0.5f, 0.3f, 0.1f, 0.2f}
            ));

            var ingestResponse = client.post("/api/v1/ingest", ingestBody);
            assertThat(ingestResponse.code()).isEqualTo(201);
            assertThat(ingestResponse.body().string()).contains("indexed");

            // Search keyword
            String searchBody = MAPPER.writeValueAsString(Map.of(
                    "text", "java",
                    "topK", 10
            ));
            var searchResponse = client.post("/api/v1/search", searchBody);
            assertThat(searchResponse.code()).isEqualTo(200);
            String searchResult = searchResponse.body().string();
            assertThat(searchResult).contains("doc-1");
        });
        engine.close();
    }

    @Test
    void ingestValidationMissingId() {
        var engine = createEngine();
        var server = new SpectorServer(engine, 0);

        JavalinTest.test(server.app(), (srv, client) -> {
            String body = MAPPER.writeValueAsString(Map.of(
                    "content", "test",
                    "vector", new float[]{1, 0, 0, 0}
            ));
            var response = client.post("/api/v1/ingest", body);
            assertThat(response.code()).isEqualTo(400);
            assertThat(response.body().string()).contains("error");
        });
        engine.close();
    }

    @Test
    void ingestValidationMissingContent() {
        var engine = createEngine();
        var server = new SpectorServer(engine, 0);

        JavalinTest.test(server.app(), (srv, client) -> {
            String body = MAPPER.writeValueAsString(Map.of(
                    "id", "doc-1",
                    "vector", new float[]{1, 0, 0, 0}
            ));
            var response = client.post("/api/v1/ingest", body);
            assertThat(response.code()).isEqualTo(400);
        });
        engine.close();
    }

    @Test
    void searchEmptyIndexReturnsEmptyResults() {
        var engine = createEngine();
        var server = new SpectorServer(engine, 0);

        JavalinTest.test(server.app(), (srv, client) -> {
            String body = MAPPER.writeValueAsString(Map.of("text", "nothing", "topK", 10));
            var response = client.post("/api/v1/search", body);
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string()).contains("\"results\":[]");
        });
        engine.close();
    }
}
