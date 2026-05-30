package com.spectrayan.spector.client;

import com.spectrayan.spector.commons.error.SpectorValidationException;

import com.spectrayan.spector.client.model.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for SpectorClient builder, model serialization, and error handling.
 */
class SpectorClientTest {

    @Test
    void builderCreatesClientWithDefaults() {
        try (var client = SpectorClient.builder().build()) {
            assertThat(client).isNotNull();
        }
    }

    @Test
    void builderAcceptsCustomConfiguration() {
        try (var client = SpectorClient.builder()
                .host("example.com")
                .port(8080)
                .apiKey("secret")
                .maxConnections(20)
                .connectTimeout(Duration.ofSeconds(10))
                .requestTimeout(Duration.ofSeconds(60))
                .build()) {
            assertThat(client).isNotNull();
        }
    }

    @Test
    void builderRejectsNullHost() {
        assertThatThrownBy(() -> SpectorClient.builder().host(null))
                .isInstanceOf(SpectorValidationException.class)
                .hasMessageContaining("host");
    }

    @Test
    void builderRejectsBlankHost() {
        assertThatThrownBy(() -> SpectorClient.builder().host("  "))
                .isInstanceOf(SpectorValidationException.class)
                .hasMessageContaining("host");
    }

    @Test
    void builderRejectsInvalidPort() {
        assertThatThrownBy(() -> SpectorClient.builder().port(0))
                .isInstanceOf(SpectorValidationException.class)
                .hasMessageContaining("port");

        assertThatThrownBy(() -> SpectorClient.builder().port(70000))
                .isInstanceOf(SpectorValidationException.class)
                .hasMessageContaining("port");
    }

    @Test
    void builderRejectsNegativeMaxConnections() {
        assertThatThrownBy(() -> SpectorClient.builder().maxConnections(0))
                .isInstanceOf(SpectorValidationException.class)
                .hasMessageContaining("maxConnections");
    }

    @Test
    void builderRejectsNullTimeout() {
        assertThatThrownBy(() -> SpectorClient.builder().connectTimeout(null))
                .isInstanceOf(SpectorValidationException.class);

        assertThatThrownBy(() -> SpectorClient.builder().requestTimeout(null))
                .isInstanceOf(SpectorValidationException.class);
    }

    @Test
    void connectionExceptionToUnreachableHost() {
        try (var client = SpectorClient.builder()
                .host("localhost")
                .port(19999) // unlikely to be running
                .connectTimeout(Duration.ofSeconds(2))
                .requestTimeout(Duration.ofSeconds(2))
                .build()) {
            assertThatThrownBy(client::status)
                    .isInstanceOf(SpectorConnectionException.class)
                    .satisfies(e -> {
                        var connEx = (SpectorConnectionException) e;
                        assertThat(connEx.host()).isEqualTo("localhost");
                        assertThat(connEx.port()).isEqualTo(19999);
                    });
        }
    }

    @Test
    void searchRequestFactoryMethods() {
        var keyword = SearchRequest.keyword("hello", 5);
        assertThat(keyword.getText()).isEqualTo("hello");
        assertThat(keyword.getMode()).isEqualTo("KEYWORD");
        assertThat(keyword.getTopK()).isEqualTo(5);

        var vector = SearchRequest.vector(new float[]{1.0f, 2.0f}, 10);
        assertThat(vector.getVector()).containsExactly(1.0f, 2.0f);
        assertThat(vector.getMode()).isEqualTo("VECTOR");
        assertThat(vector.getTopK()).isEqualTo(10);

        var hybrid = SearchRequest.hybrid("text", new float[]{3.0f}, 20);
        assertThat(hybrid.getText()).isEqualTo("text");
        assertThat(hybrid.getVector()).containsExactly(3.0f);
        assertThat(hybrid.getMode()).isEqualTo("HYBRID");
        assertThat(hybrid.getTopK()).isEqualTo(20);
    }

    @Test
    void ingestRequestConstructors() {
        var req1 = new IngestRequest("id1", "content", new float[]{1.0f});
        assertThat(req1.getId()).isEqualTo("id1");
        assertThat(req1.getContent()).isEqualTo("content");
        assertThat(req1.getTitle()).isNull();

        var req2 = new IngestRequest("id2", "title", "content", new float[]{2.0f});
        assertThat(req2.getTitle()).isEqualTo("title");
    }

    @Test
    void httpExceptionContainsDetails() {
        var ex = new SpectorHttpException(404, "Document not found", "http://localhost:7070/api/v1/documents/abc");
        assertThat(ex.statusCode()).isEqualTo(404);
        assertThat(ex.errorMessage()).isEqualTo("Document not found");
        assertThat(ex.requestUrl()).contains("/api/v1/documents/abc");
        assertThat(ex.getMessage()).contains("404");
    }

    @Test
    void clientIsThreadSafe() throws InterruptedException {
        // Verify that building and closing the client from multiple threads doesn't throw
        try (var client = SpectorClient.builder().build()) {
            var threads = new Thread[10];
            var errors = new java.util.concurrent.atomic.AtomicInteger(0);
            for (int i = 0; i < threads.length; i++) {
                threads[i] = Thread.ofVirtual().start(() -> {
                    try {
                        // Attempting to call status on unreachable server
                        // just verifying no ConcurrentModificationException etc.
                        client.status();
                    } catch (SpectorConnectionException e) {
                        // Expected - server not running
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                });
            }
            for (var t : threads) t.join(5000);
            assertThat(errors.get()).isEqualTo(0);
        }
    }
}
