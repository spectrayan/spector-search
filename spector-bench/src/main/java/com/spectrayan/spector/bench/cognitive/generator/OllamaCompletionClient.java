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
package com.spectrayan.spector.bench.cognitive.generator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Chat completion client for the Ollama API with retry logic and JSON parsing.
 *
 * <p>Wraps the Ollama {@code /api/chat} endpoint to support multi-turn conversations
 * used by the dataset generator pipeline. Provides automatic retry with exponential
 * backoff (2s, 4s, 8s) when requests fail due to transient errors.</p>
 *
 * <h3>Retry Strategy</h3>
 * <ul>
 *   <li>3 attempts by default (configurable via {@link GeneratorConfig#maxRetries()})</li>
 *   <li>Exponential backoff: 2s × 2^(attempt-1) → 2s, 4s, 8s</li>
 *   <li>Retries on: connection errors, HTTP 5xx, timeout</li>
 *   <li>No retry on: HTTP 4xx (client error), interruption</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * <p>This class is thread-safe. The underlying {@link HttpClient} handles concurrent requests.</p>
 */
public final class OllamaCompletionClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OllamaCompletionClient.class);

    /** Base backoff duration for retries. */
    private static final Duration BASE_BACKOFF = Duration.ofSeconds(2);

    /** Request timeout per attempt. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final String model;
    private final String baseUrl;
    private final int maxRetries;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final URI chatUri;

    /**
     * Creates a new completion client from generator configuration.
     *
     * @param config the generator configuration containing Ollama settings
     */
    public OllamaCompletionClient(GeneratorConfig config) {
        this(config.ollamaUrl(), config.modelName(), config.maxRetries());
    }

    /**
     * Creates a new completion client with explicit parameters.
     *
     * @param baseUrl    Ollama server base URL
     * @param model      model name for chat completions
     * @param maxRetries maximum number of retry attempts
     */
    public OllamaCompletionClient(String baseUrl, String model, int maxRetries) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.maxRetries = maxRetries;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = JsonMapper.builder().build();
        this.chatUri = URI.create(baseUrl + "/api/chat");
        log.info("OllamaCompletionClient initialized: model={}, baseUrl={}, maxRetries={}",
                model, baseUrl, maxRetries);
    }

    /**
     * Sends a chat completion request to Ollama and returns the response content.
     *
     * <p>Automatically retries on transient failures with exponential backoff.</p>
     *
     * @param systemPrompt the system message setting generation context
     * @param userPrompt   the user message containing the generation request
     * @return the assistant's response text
     * @throws OllamaCompletionException if all retry attempts are exhausted
     */
    public String complete(String systemPrompt, String userPrompt) {
        return complete(systemPrompt, userPrompt, 0.7f, 2048);
    }

    /**
     * Sends a chat completion request with configurable temperature and max tokens.
     *
     * @param systemPrompt the system message setting generation context
     * @param userPrompt   the user message containing the generation request
     * @param temperature  sampling temperature (0.0–2.0)
     * @param maxTokens    maximum tokens to generate
     * @return the assistant's response text
     * @throws OllamaCompletionException if all retry attempts are exhausted
     */
    public String complete(String systemPrompt, String userPrompt, float temperature, int maxTokens) {
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                String response = doRequest(systemPrompt, userPrompt, temperature, maxTokens);
                if (attempt > 1) {
                    log.info("Ollama request succeeded on attempt {}", attempt);
                }
                return response;
            } catch (Exception e) {
                lastException = e;
                if (!isRetryable(e) || attempt == maxRetries) {
                    break;
                }
                long backoffMs = BASE_BACKOFF.toMillis() * (1L << (attempt - 1));
                log.warn("Ollama request failed (attempt {}/{}), retrying in {}ms: {}",
                        attempt, maxRetries, backoffMs, e.getMessage());
                sleep(backoffMs);
            }
        }

        throw new OllamaCompletionException(
                "Ollama completion failed after " + maxRetries + " attempts", lastException);
    }

    /**
     * Sends a chat completion request and parses the response as JSON.
     *
     * <p>Useful for structured generation where the LLM is instructed to respond
     * in JSON format. Retries with a rephrased prompt if the JSON is invalid.</p>
     *
     * @param systemPrompt the system message (should instruct JSON output)
     * @param userPrompt   the user message
     * @param typeRef      Jackson type reference for the expected JSON structure
     * @param <T>          the expected response type
     * @return parsed JSON response
     * @throws OllamaCompletionException if completion or parsing fails after retries
     */
    public <T> T completeAsJson(String systemPrompt, String userPrompt, TypeReference<T> typeRef) {
        String jsonSystem = systemPrompt + "\n\nIMPORTANT: Respond ONLY with valid JSON. No markdown, no explanation.";

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            String raw = complete(jsonSystem, userPrompt);
            try {
                // Strip potential markdown code fences
                String cleaned = stripCodeFences(raw);
                return mapper.readValue(cleaned, typeRef);
            } catch (Exception e) {
                if (attempt == maxRetries) {
                    throw new OllamaCompletionException(
                            "Failed to parse JSON response after " + maxRetries + " attempts. "
                                    + "Last response: " + truncate(raw, 200), e);
                }
                log.warn("JSON parse failed (attempt {}/{}), rephrasing prompt: {}",
                        attempt, maxRetries, e.getMessage());
                // Rephrase: add emphasis on valid JSON
                userPrompt = userPrompt + "\n\n[Previous response was not valid JSON. "
                        + "Please respond with ONLY a valid JSON object/array, no other text.]";
            }
        }

        // Unreachable, but satisfies compiler
        throw new OllamaCompletionException("Unexpected state: all attempts exhausted");
    }

    /**
     * Checks whether the Ollama server is reachable and the configured model is available.
     *
     * @return true if the server responds to a tags request
     */
    public boolean isAvailable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/tags"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            log.debug("Ollama availability check failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public void close() {
        // HttpClient doesn't require explicit close in Java 11+
        log.debug("OllamaCompletionClient closed");
    }

    // ─────────────── Internal request handling ───────────────

    private String doRequest(String systemPrompt, String userPrompt,
                             float temperature, int maxTokens) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("stream", false);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));
        body.put("options", Map.of(
                "temperature", temperature,
                "num_predict", maxTokens
        ));

        String requestBody = mapper.writeValueAsString(body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(chatUri)
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new OllamaCompletionException("Ollama returned HTTP " + response.statusCode()
                    + ": " + truncate(response.body(), 300));
        }

        return parseMessageContent(response.body());
    }

    /**
     * Parses the /api/chat response to extract the assistant message content.
     *
     * <p>Expected format: {@code {"message":{"role":"assistant","content":"..."}, ...}}</p>
     */
    private String parseMessageContent(String json) throws Exception {
        Map<String, Object> responseMap = mapper.readValue(json,
                new TypeReference<Map<String, Object>>() {});

        Object messageObj = responseMap.get("message");
        if (messageObj instanceof Map<?, ?> messageMap) {
            Object content = messageMap.get("content");
            if (content instanceof String text) {
                return text.strip();
            }
        }

        throw new OllamaCompletionException(
                "Unexpected response structure — no message.content field found");
    }

    private boolean isRetryable(Exception e) {
        if (e instanceof InterruptedException) {
            return false;
        }
        if (e instanceof OllamaCompletionException oce) {
            String msg = oce.getMessage();
            // Don't retry 4xx client errors
            if (msg != null && msg.contains("HTTP 4")) {
                return false;
            }
        }
        return true;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OllamaCompletionException("Retry interrupted", e);
        }
    }

    private static String stripCodeFences(String raw) {
        String trimmed = raw.strip();
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed.strip();
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "<null>";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
