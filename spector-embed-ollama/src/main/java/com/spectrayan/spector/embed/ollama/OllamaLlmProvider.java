/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.spectrayan.spector.embed.ollama;

import com.spectrayan.spector.embed.GenerationOptions;
import com.spectrayan.spector.embed.TextGenerationProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.ObjectMapper;


import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * Text generation provider backed by a local <a href="https://ollama.com">Ollama</a> server.
 *
 * <p>Calls the {@code /api/generate} endpoint to perform LLM inference using any
 * model pulled into Ollama (e.g., {@code qwen3:0.6b}, {@code llama3.1:8b},
 * {@code gemma3:4b}).</p>
 *
 * <h3>Prerequisites</h3>
 * <ol>
 *   <li>Install Ollama: <a href="https://ollama.com/download">ollama.com/download</a></li>
 *   <li>Pull a generation model: {@code ollama pull qwen3:0.6b}</li>
 *   <li>Ensure the server is running (default: {@code http://localhost:11434})</li>
 * </ol>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   var llm = OllamaLlmProvider.create("qwen3:0.6b");
 *   String response = llm.generate("Explain connection pooling in one paragraph.");
 * }</pre>
 *
 * <h3>Thread Safety</h3>
 * <p>This class is thread-safe. The underlying {@link HttpClient} handles
 * concurrent requests efficiently.</p>
 *
 * @see TextGenerationProvider
 * @see OllamaEmbeddingProvider
 */
public class OllamaLlmProvider implements TextGenerationProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaLlmProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Default Ollama base URL. */
    private static final String DEFAULT_BASE_URL = "http://localhost:11434";

    /** Default generation timeout (60s — generation is slower than embedding). */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    /** Default model for text generation. */
    private static final String DEFAULT_MODEL = "qwen3:0.6b";

    private final String model;
    private final String baseUrl;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final URI generateUri;
    private final URI tagsUri;

    /**
     * Serializes concurrent LLM generation calls. Ollama can only run one GPU
     * inference at a time; additional concurrent requests queue internally and
     * often timeout under bulk ingestion. This semaphore ensures orderly
     * processing: at most 1 generate() call is in-flight at a time.
     */
    private final Semaphore llmGate = new Semaphore(1, true);

    /**
     * Creates a provider with full configuration.
     *
     * @param model   the Ollama model name (e.g., "qwen3:0.6b")
     * @param baseUrl the Ollama server base URL (e.g., "http://localhost:11434")
     * @param timeout HTTP request timeout
     */
    public OllamaLlmProvider(String model, String baseUrl, Duration timeout) {
        this.model = model;
        this.baseUrl = baseUrl;
        this.timeout = timeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
        this.generateUri = URI.create(baseUrl + "/api/generate");
        this.tagsUri = URI.create(baseUrl + "/api/tags");
        log.info("OllamaLlmProvider initialized: model={}, baseUrl={}, timeout={}s",
                model, baseUrl, timeout.toSeconds());
    }

    /**
     * Creates a provider for the given model with default Ollama settings.
     *
     * @param model the Ollama model name (e.g., "qwen3:0.6b")
     * @return configured provider
     */
    public static OllamaLlmProvider create(String model) {
        return new OllamaLlmProvider(model, DEFAULT_BASE_URL, DEFAULT_TIMEOUT);
    }

    /**
     * Creates a provider for the given model and base URL.
     *
     * @param model   the Ollama model name
     * @param baseUrl the Ollama server base URL
     * @return configured provider
     */
    public static OllamaLlmProvider create(String model, String baseUrl) {
        return new OllamaLlmProvider(model, baseUrl, DEFAULT_TIMEOUT);
    }

    /**
     * Creates a provider with full default settings (qwen3:0.6b on localhost:11434).
     *
     * @return configured provider
     */
    public static OllamaLlmProvider createDefault() {
        return new OllamaLlmProvider(DEFAULT_MODEL, DEFAULT_BASE_URL, DEFAULT_TIMEOUT);
    }

    @Override
    public String generate(String prompt) {
        return generate(prompt, GenerationOptions.DEFAULT);
    }

    @Override
    public String generate(String prompt, GenerationOptions options) {
        if (prompt == null || prompt.isBlank()) {
            throw new GenerationException("prompt must not be null or blank");
        }

        long startNanos = System.nanoTime();

        // Acquire the semaphore — block until it's our turn.
        // Use timeout.multipliedBy(2) as the acquire deadline so we don't
        // wait forever if other calls are pathologically slow.
        boolean acquired;
        try {
            acquired = llmGate.tryAcquire(timeout.toMillis() * 2,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GenerationException("Interrupted while waiting for LLM gate", e);
        }
        if (!acquired) {
            throw new GenerationException(
                    "LLM gate acquire timed out — Ollama is likely overloaded (queue depth: "
                    + llmGate.getQueueLength() + ")");
        }

        try {
            Map<String, Object> requestMap = buildRequestBody(prompt, options);
            String requestBody = MAPPER.writeValueAsString(requestMap);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(generateUri)
                    .header("Content-Type", "application/json")
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new GenerationException("Ollama returned HTTP " + response.statusCode()
                        + ": " + truncateBody(response.body(), 500));
            }

            String generatedText = parseGenerateResponse(response.body());
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;

            log.debug("OllamaLlmProvider.generate: model={}, latency={}ms, promptLen={}, responseLen={}",
                    model, latencyMs, prompt.length(), generatedText.length());

            return generatedText;

        } catch (GenerationException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            throw new GenerationException("Ollama generation timed out after " + timeout.toSeconds() + "s", e);
        } catch (java.net.ConnectException | java.net.UnknownHostException e) {
            throw new GenerationException("Ollama server unavailable at " + baseUrl, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GenerationException("Generation request interrupted", e);
        } catch (Exception e) {
            throw new GenerationException("Ollama generation failed: " + e.getMessage(), e);
        } finally {
            llmGate.release();
        }
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public boolean isAvailable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(tagsUri)
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            log.debug("Ollama availability check failed: {}", e.getMessage());
            return false;
        }
    }

    // ─────────────── Request building ───────────────

    private Map<String, Object> buildRequestBody(String prompt, GenerationOptions options) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("stream", false);

        // Ollama options mapping
        Map<String, Object> ollamaOptions = new LinkedHashMap<>();
        if (options != null) {
            ollamaOptions.put("temperature", options.temperature());
            if (options.maxTokens() > 0) {
                ollamaOptions.put("num_predict", options.maxTokens());
            }
            ollamaOptions.put("top_p", options.topP());

            if (options.stopSequences() != null && options.stopSequences().length > 0) {
                body.put("stop", options.stopSequences());
            }
        }
        if (!ollamaOptions.isEmpty()) {
            body.put("options", ollamaOptions);
        }

        return body;
    }

    // ─────────────── Response parsing (streaming parser — no DOM overhead) ───────────────

    /**
     * Parses the Ollama /api/generate response to extract the "response" field.
     *
     * <p>Response format: {@code {"model":"...","response":"generated text","done":true,...}}</p>
     */
    private String parseGenerateResponse(String json) {
        try (var parser = MAPPER.createParser(json)) {
            while (parser.nextToken() != null) {
                if (parser.currentToken() == tools.jackson.core.JsonToken.PROPERTY_NAME
                        && "response".equals(parser.currentName())) {
                    parser.nextToken(); // move to value
                    String response = parser.getText();
                    return response != null ? response.strip() : "";
                }
            }
            throw new GenerationException("No 'response' field in Ollama generate response");
        } catch (GenerationException e) {
            throw e;
        } catch (Exception e) {
            throw new GenerationException("Failed to parse Ollama generate response", e);
        }
    }

    private static String truncateBody(String body, int maxLen) {
        if (body == null) return "<null>";
        return body.length() <= maxLen ? body : body.substring(0, maxLen) + "...";
    }
}
