package com.spectrayan.spector.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.spectrayan.spector.client.model.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.commons.error.SpectorException;

/**
 * Thread-safe Java client SDK for Spector Search REST API.
 *
 * <p>Uses Java HttpClient with connection pooling. All methods are safe
 * for concurrent invocations from multiple threads.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * try (var client = SpectorClient.builder()
 *         .host("localhost")
 *         .port(7070)
 *         .apiKey("my-key")
 *         .build()) {
 *     StatusResponse status = client.status();
 *     System.out.println("Documents: " + status.getDocuments());
 * }
 * }</pre>
 */
public class SpectorClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SpectorClient.class);

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration requestTimeout;

    private SpectorClient(Builder builder) {
        this.baseUrl = "http://" + builder.host + ":" + builder.port;
        this.apiKey = builder.apiKey;
        this.requestTimeout = builder.requestTimeout;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(builder.connectTimeout)
                .build();

        this.objectMapper = JsonMapper.builder()
                .changeDefaultPropertyInclusion(incl -> incl
                        .withValueInclusion(JsonInclude.Include.NON_NULL))
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /**
     * Creates a new builder for SpectorClient.
     */
    public static Builder builder() {
        return new Builder();
    }

    // ─────────────── Public API Methods ───────────────

    /**
     * Ingests a single document with a pre-computed vector.
     *
     * @param request the ingest request containing document id, content, and vector
     * @return the ingest response confirming indexing
     * @throws SpectorHttpException if the server returns an HTTP error
     * @throws SpectorConnectionException if the server is unreachable
     */
    public IngestResponse ingest(IngestRequest request) {
        return post("/api/v1/ingest", request, IngestResponse.class);
    }

    /**
     * Bulk ingests multiple documents in a single request.
     *
     * @param requests the list of ingest requests
     * @return the ingest response with total/success/failed counts
     * @throws SpectorHttpException if the server returns an HTTP error
     * @throws SpectorConnectionException if the server is unreachable
     */
    public IngestResponse bulkIngest(List<IngestRequest> requests) {
        var bulkRequest = new BulkIngestRequest(requests);
        return post("/api/v1/ingest/bulk", bulkRequest, IngestResponse.class);
    }

    /**
     * Performs a search against the Spector Search engine.
     *
     * @param request the search request (keyword, vector, or hybrid)
     * @return the search response containing results and metadata
     * @throws SpectorHttpException if the server returns an HTTP error
     * @throws SpectorConnectionException if the server is unreachable
     */
    public SearchResponse search(SearchRequest request) {
        return post("/api/v1/search", request, SearchResponse.class);
    }

    /**
     * Deletes a document by its ID.
     *
     * @param documentId the ID of the document to delete
     * @return the delete response
     * @throws SpectorHttpException if the server returns an HTTP error (e.g., 404 if not found)
     * @throws SpectorConnectionException if the server is unreachable
     */
    public DeleteResponse delete(String documentId) {
        String path = "/api/v1/documents/" + documentId;
        return executeRequest(buildRequest("DELETE", path, null), path, DeleteResponse.class);
    }

    /**
     * Retrieves the current server status.
     *
     * @return the status response
     * @throws SpectorHttpException if the server returns an HTTP error
     * @throws SpectorConnectionException if the server is unreachable
     */
    public StatusResponse status() {
        return get("/api/v1/status", StatusResponse.class);
    }

    /**
     * Retrieves server metrics.
     *
     * @return the metrics response
     * @throws SpectorHttpException if the server returns an HTTP error
     * @throws SpectorConnectionException if the server is unreachable
     */
    public MetricsResponse metrics() {
        return get("/api/v1/metrics", MetricsResponse.class);
    }

    @Override
    public void close() {
        // HttpClient does not require explicit close in Java 21+
        log.debug("SpectorClient closed for {}", baseUrl);
    }

    // ─────────────── Internal HTTP Methods ───────────────

    private <T> T get(String path, Class<T> responseType) {
        return executeRequest(buildRequest("GET", path, null), path, responseType);
    }

    private <T> T post(String path, Object body, Class<T> responseType) {
        return executeRequest(buildRequest("POST", path, body), path, responseType);
    }

    private HttpRequest buildRequest(String method, String path, Object body) {
        var uri = URI.create(baseUrl + path);
        var builder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");

        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("X-API-Key", apiKey);
        }

        if (body != null) {
            try {
                byte[] jsonBytes = objectMapper.writeValueAsBytes(body);
                builder.method(method, HttpRequest.BodyPublishers.ofByteArray(jsonBytes));
            } catch (Exception e) {
                throw new SpectorClientException("Failed to serialize request body: " + e.getMessage(), e);
            }
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        return builder.build();
    }

    private <T> T executeRequest(HttpRequest request, String path, Class<T> responseType) {
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            int statusCode = response.statusCode();
            if (statusCode >= 400) {
                String errorMessage = extractErrorMessage(response.body());
                throw new SpectorHttpException(statusCode, errorMessage, baseUrl + path);
            }

            return objectMapper.readValue(response.body(), responseType);
        } catch (SpectorClientException e) {
            throw e;
        } catch (ConnectException e) {
            throw new SpectorConnectionException(extractHost(), extractPort(), e);
        } catch (IOException e) {
            if (e.getCause() instanceof ConnectException ce) {
                throw new SpectorConnectionException(extractHost(), extractPort(), ce);
            }
            throw new SpectorConnectionException(extractHost(), extractPort(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SpectorClientException("Request interrupted: " + path, e);
        }
    }

    private String extractErrorMessage(byte[] body) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> errorMap = objectMapper.readValue(body, Map.class);
            Object error = errorMap.get("error");
            return error != null ? error.toString() : new String(body);
        } catch (Exception e) {
            return body != null ? new String(body) : "Unknown error";
        }
    }

    private String extractHost() {
        // Parse host from baseUrl: "http://host:port"
        String withoutScheme = baseUrl.substring("http://".length());
        int colonIdx = withoutScheme.lastIndexOf(':');
        return colonIdx > 0 ? withoutScheme.substring(0, colonIdx) : withoutScheme;
    }

    private int extractPort() {
        String withoutScheme = baseUrl.substring("http://".length());
        int colonIdx = withoutScheme.lastIndexOf(':');
        if (colonIdx > 0) {
            try {
                return Integer.parseInt(withoutScheme.substring(colonIdx + 1));
            } catch (NumberFormatException e) {
                return 80;
            }
        }
        return 80;
    }

    // ─────────────── Builder ───────────────

    /**
     * Builder for configuring and creating a SpectorClient instance.
     */
    public static class Builder {
        private String host = "localhost";
        private int port = 7070;
        private String apiKey;
        private int maxConnections = 10;
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration requestTimeout = Duration.ofSeconds(30);

        private Builder() {}

        /** Sets the server host (default: localhost). */
        public Builder host(String host) {
            if (host == null || host.isBlank()) {
                throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "host");
            }
            this.host = host;
            return this;
        }

        /** Sets the server port (default: 7070). */
        public Builder port(int port) {
            if (port <= 0 || port > 65535) {
                throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "port", 1, 65535, 0);
            }
            this.port = port;
            return this;
        }

        /** Sets the API key for authentication (optional). */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /** Sets the maximum connection pool size (default: 10). */
        public Builder maxConnections(int maxConnections) {
            if (maxConnections <= 0) {
                throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "maxConnections", 1, Integer.MAX_VALUE, 0);
            }
            this.maxConnections = maxConnections;
            return this;
        }

        /** Sets the connection timeout (default: 5 seconds). */
        public Builder connectTimeout(Duration connectTimeout) {
            if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()) {
                throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "connectTimeout", "must be a positive duration");
            }
            this.connectTimeout = connectTimeout;
            return this;
        }

        /** Sets the per-request timeout (default: 30 seconds). */
        public Builder requestTimeout(Duration requestTimeout) {
            if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()) {
                throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "requestTimeout", "must be a positive duration");
            }
            this.requestTimeout = requestTimeout;
            return this;
        }

        /**
         * Builds the SpectorClient instance.
         */
        public SpectorClient build() {
            return new SpectorClient(this);
        }
    }
}
