package com.spectrayan.spector.embed.ollama;

import tools.jackson.databind.ObjectMapper;
import com.spectrayan.spector.embed.EmbeddingConfig;
import com.spectrayan.spector.commons.error.SpectorEmbeddingException;
import com.spectrayan.spector.commons.error.SpectorEmbeddingUnavailableException;
import com.spectrayan.spector.commons.error.SpectorEmbeddingTimeoutException;
import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.embed.EmbeddingProvider;
import com.spectrayan.spector.embed.EmbeddingResult;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Embedding provider backed by a local <a href="https://ollama.com">Ollama</a> server.
 *
 * <p>Calls the {@code /api/embed} endpoint to generate embeddings using any
 * model pulled into Ollama (e.g., {@code nomic-embed-text}, {@code all-minilm},
 * {@code mxbai-embed-large}).</p>
 *
 * <h3>Prerequisites</h3>
 * <ol>
 *   <li>Install Ollama: <a href="https://ollama.com/download">ollama.com/download</a></li>
 *   <li>Pull an embedding model: {@code ollama pull nomic-embed-text}</li>
 *   <li>Ensure the server is running (default: {@code http://localhost:11434})</li>
 * </ol>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   var provider = OllamaEmbeddingProvider.create("nomic-embed-text");
 *   EmbeddingResult result = provider.embed("Hello, world!");
 *   float[] vector = result.vector(); // 768-dim for nomic-embed-text
 * }</pre>
 *
 * <h3>Thread Safety</h3>
 * <p>This class is thread-safe. The underlying {@link HttpClient} handles
 * concurrent requests efficiently.</p>
 */
public class OllamaEmbeddingProvider implements EmbeddingProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EmbeddingConfig config;
    private final HttpClient httpClient;
    private final URI embedUri;
    private volatile int cachedDimensions = -1;

    /**
     * Creates a provider with the given configuration.
     *
     * @param config embedding configuration
     */
    public OllamaEmbeddingProvider(EmbeddingConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(config.timeout())
                .build();
        this.embedUri = URI.create(config.baseUrl() + "/api/embed");
    }

    /**
     * Creates a provider for the given model with default Ollama settings.
     *
     * @param model the Ollama model name (e.g., "nomic-embed-text")
     * @return configured provider
     */
    public static OllamaEmbeddingProvider create(String model) {
        return new OllamaEmbeddingProvider(EmbeddingConfig.ollama(model));
    }

    /**
     * Creates a provider with full default settings (nomic-embed-text on localhost:11434).
     *
     * @return configured provider
     */
    public static OllamaEmbeddingProvider createDefault() {
        return new OllamaEmbeddingProvider(EmbeddingConfig.OLLAMA_DEFAULT);
    }

    @Override
    public EmbeddingResult embed(String text) {
        if (text == null || text.isBlank()) {
            throw new SpectorEmbeddingException(ErrorCode.EMBEDDING_REQUEST_FAILED, "text must not be null or blank");
        }

        try {
            String requestBody = MAPPER.writeValueAsString(Map.of(
                    "model", config.model(),
                    "input", text
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(embedUri)
                    .header("Content-Type", "application/json")
                    .timeout(config.timeout())
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new SpectorEmbeddingException(ErrorCode.EMBEDDING_REQUEST_FAILED, "Ollama returned HTTP " + response.statusCode()
                        + ": " + response.body());
            }

            return parseEmbedResponse(response.body());
        } catch (SpectorEmbeddingException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            throw new SpectorEmbeddingTimeoutException(config.timeout().toMillis(), e);
        } catch (java.net.ConnectException | java.net.UnknownHostException e) {
            throw new SpectorEmbeddingUnavailableException(config.baseUrl(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SpectorEmbeddingException(ErrorCode.EMBEDDING_REQUEST_FAILED, e, "Embedding request interrupted");
        } catch (Exception e) {
            throw new SpectorEmbeddingException(ErrorCode.EMBEDDING_REQUEST_FAILED, e, "Ollama: " + e.getMessage());
        }
    }

    @Override
    public List<EmbeddingResult> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();

        // Ollama /api/embed supports array input natively
        try {
            String requestBody = MAPPER.writeValueAsString(Map.of(
                    "model", config.model(),
                    "input", texts
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(embedUri)
                    .header("Content-Type", "application/json")
                    .timeout(config.timeout())
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new SpectorEmbeddingException(ErrorCode.EMBEDDING_REQUEST_FAILED, "Ollama batch returned HTTP " + response.statusCode()
                        + ": " + response.body());
            }

            return parseBatchResponse(response.body());
        } catch (SpectorEmbeddingException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            throw new SpectorEmbeddingTimeoutException(config.timeout().toMillis(), e);
        } catch (java.net.ConnectException | java.net.UnknownHostException e) {
            throw new SpectorEmbeddingUnavailableException(config.baseUrl(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SpectorEmbeddingException(ErrorCode.EMBEDDING_REQUEST_FAILED, e, "batch embedding interrupted");
        } catch (Exception e) {
            throw new SpectorEmbeddingException(ErrorCode.EMBEDDING_REQUEST_FAILED, e, "Ollama batch: " + e.getMessage());
        }
    }

    @Override
    public int dimensions() {
        if (cachedDimensions > 0) return cachedDimensions;
        // Probe by embedding a short text
        EmbeddingResult probe = embed("dimension probe");
        cachedDimensions = probe.dimensions();
        return cachedDimensions;
    }

    @Override
    public String modelName() {
        return config.model();
    }

    /**
     * Returns the underlying configuration.
     */
    public EmbeddingConfig config() {
        return config;
    }

    // ─────────────── Response parsing (streaming — avoids DOM tree overhead) ───────────────

    private EmbeddingResult parseEmbedResponse(String json) {
        try (var parser = MAPPER.createParser(json)) {
            float[] vector = parseFirstEmbedding(parser);
            if (vector == null) {
                throw new SpectorEmbeddingException(ErrorCode.EMBEDDING_REQUEST_FAILED, "no embeddings in Ollama response");
            }
            cachedDimensions = vector.length;
            return new EmbeddingResult(vector, -1, config.model());
        } catch (SpectorEmbeddingException e) {
            throw e;
        } catch (Exception e) {
            throw new SpectorEmbeddingException(ErrorCode.EMBEDDING_REQUEST_FAILED, e, "failed to parse Ollama response");
        }
    }

    private List<EmbeddingResult> parseBatchResponse(String json) {
        try (var parser = MAPPER.createParser(json)) {
            List<EmbeddingResult> results = new ArrayList<>();
            // Navigate to "embeddings" array
            if (!advanceToEmbeddingsArray(parser)) {
                throw new SpectorEmbeddingException(ErrorCode.EMBEDDING_REQUEST_FAILED, "no embeddings array in Ollama batch response");
            }
            // Each element in the "embeddings" array is itself an array of floats
            while (parser.nextToken() == tools.jackson.core.JsonToken.START_ARRAY) {
                float[] vector = parseFloatArray(parser);
                results.add(new EmbeddingResult(vector, -1, config.model()));
            }
            if (!results.isEmpty()) {
                cachedDimensions = results.getFirst().dimensions();
            }
            return results;
        } catch (SpectorEmbeddingException e) {
            throw e;
        } catch (Exception e) {
            throw new SpectorEmbeddingException(ErrorCode.EMBEDDING_REQUEST_FAILED, e, "failed to parse Ollama batch response");
        }
    }

    /**
     * Streaming parse: navigates to the first embedding vector and reads it as float[].
     * Avoids building a full JsonNode tree — O(dims) heap instead of O(dims × node_overhead).
     */
    private float[] parseFirstEmbedding(tools.jackson.core.JsonParser parser) throws java.io.IOException {
        if (!advanceToEmbeddingsArray(parser)) return null;
        // First element in "embeddings" array should be an array of floats
        if (parser.nextToken() != tools.jackson.core.JsonToken.START_ARRAY) return null;
        return parseFloatArray(parser);
    }

    /**
     * Advances the parser to the start of the "embeddings" array.
     * Returns true if found, false otherwise.
     */
    private boolean advanceToEmbeddingsArray(tools.jackson.core.JsonParser parser) throws java.io.IOException {
        while (parser.nextToken() != null) {
            if (parser.currentToken() == tools.jackson.core.JsonToken.PROPERTY_NAME
                    && "embeddings".equals(parser.currentName())) {
                // Next token should be START_ARRAY
                return parser.nextToken() == tools.jackson.core.JsonToken.START_ARRAY;
            }
        }
        return false;
    }

    /**
     * Reads a JSON array of numbers into a float[].
     * Assumes the parser is positioned right after START_ARRAY.
     * Uses a growable list to handle unknown dimensions, then converts to float[].
     */
    private float[] parseFloatArray(tools.jackson.core.JsonParser parser) throws java.io.IOException {
        // Use cached dimensions as initial capacity hint if known
        int hint = cachedDimensions > 0 ? cachedDimensions : 768;
        float[] buf = new float[hint];
        int idx = 0;

        while (parser.nextToken() != tools.jackson.core.JsonToken.END_ARRAY) {
            if (idx >= buf.length) {
                buf = java.util.Arrays.copyOf(buf, buf.length * 2);
            }
            buf[idx++] = parser.getFloatValue();
        }

        // Trim to exact size
        return idx == buf.length ? buf : java.util.Arrays.copyOf(buf, idx);
    }
}
