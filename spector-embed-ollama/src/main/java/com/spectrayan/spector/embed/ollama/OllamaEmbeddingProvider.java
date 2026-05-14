package com.spectrayan.spector.embed.ollama;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spectrayan.spector.embed.EmbeddingConfig;
import com.spectrayan.spector.embed.EmbeddingException;
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
            throw new EmbeddingException("Cannot embed null or blank text");
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
                throw new EmbeddingException("Ollama returned HTTP " + response.statusCode()
                        + ": " + response.body());
            }

            return parseEmbedResponse(response.body());
        } catch (EmbeddingException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbeddingException("Embedding request interrupted", e);
        } catch (Exception e) {
            throw new EmbeddingException("Failed to embed text via Ollama: " + e.getMessage(), e);
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
                throw new EmbeddingException("Ollama batch returned HTTP " + response.statusCode()
                        + ": " + response.body());
            }

            return parseBatchResponse(response.body());
        } catch (EmbeddingException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbeddingException("Batch embedding interrupted", e);
        } catch (Exception e) {
            throw new EmbeddingException("Failed to batch embed via Ollama: " + e.getMessage(), e);
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

    // ─────────────── Response parsing ───────────────

    private EmbeddingResult parseEmbedResponse(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode embeddings = root.get("embeddings");

            if (embeddings == null || !embeddings.isArray() || embeddings.isEmpty()) {
                throw new EmbeddingException("No embeddings in Ollama response: " + json);
            }

            float[] vector = parseVector(embeddings.get(0));
            cachedDimensions = vector.length;

            return new EmbeddingResult(vector, -1, config.model());
        } catch (EmbeddingException e) {
            throw e;
        } catch (Exception e) {
            throw new EmbeddingException("Failed to parse Ollama response: " + e.getMessage(), e);
        }
    }

    private List<EmbeddingResult> parseBatchResponse(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode embeddings = root.get("embeddings");

            if (embeddings == null || !embeddings.isArray()) {
                throw new EmbeddingException("No embeddings array in Ollama batch response");
            }

            List<EmbeddingResult> results = new ArrayList<>();
            for (JsonNode node : embeddings) {
                float[] vector = parseVector(node);
                results.add(new EmbeddingResult(vector, -1, config.model()));
            }

            if (!results.isEmpty()) {
                cachedDimensions = results.getFirst().dimensions();
            }
            return results;
        } catch (EmbeddingException e) {
            throw e;
        } catch (Exception e) {
            throw new EmbeddingException("Failed to parse Ollama batch response: " + e.getMessage(), e);
        }
    }

    private static float[] parseVector(JsonNode arrayNode) {
        float[] vector = new float[arrayNode.size()];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) arrayNode.get(i).asDouble();
        }
        return vector;
    }
}
