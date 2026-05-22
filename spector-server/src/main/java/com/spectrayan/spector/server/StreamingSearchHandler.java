package com.spectrayan.spector.server;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.index.ScoredResult;
import com.spectrayan.spector.query.SearchQuery;
import com.spectrayan.spector.query.SearchResponse;

import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;

/**
 * Server-Sent Events handler for streaming search results.
 *
 * <p>Emits search results one-by-one as SSE events, allowing clients to display
 * results progressively as they arrive. This gives a streaming UX without
 * requiring WebFlux/Reactor — Javalin's built-in SSE support handles it natively
 * on virtual threads.</p>
 *
 * <h3>Event Format</h3>
 * <pre>
 * event: result
 * data: {"id":"doc-1","score":0.95,"rank":1}
 *
 * event: result
 * data: {"id":"doc-2","score":0.87,"rank":2}
 *
 * event: done
 * data: {"totalHits":2,"queryTimeMs":12,"mode":"HYBRID"}
 * </pre>
 *
 * <h3>Endpoint</h3>
 * {@code GET /api/v1/search/stream?text=...&topK=10&mode=HYBRID}
 */
public class StreamingSearchHandler {

    private static final Logger log = LoggerFactory.getLogger(StreamingSearchHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private final SpectorEngine engine;

    public StreamingSearchHandler(SpectorEngine engine) {
        this.engine = engine;
    }

    /**
     * Handles an SSE streaming search request.
     * Registered as: {@code app.sse("/api/v1/search/stream", handler::handle)}
     */
    public void handle(SseClient client) {
        // Parse query params from the SSE connection context
        Context ctx = client.ctx();
        String text = ctx.queryParam("text");
        String vectorParam = ctx.queryParam("vector");
        String modeParam = ctx.queryParam("mode");
        int topK = ctx.queryParamAsClass("topK", Integer.class).getOrDefault(10);

        // Build search query
        float[] vector = parseVector(vectorParam);
        SearchQuery.SearchMode mode = resolveMode(modeParam, text, vector);

        SearchQuery query = switch (mode) {
            case KEYWORD -> SearchQuery.keyword(text, topK);
            case VECTOR -> SearchQuery.vector(vector, topK);
            case HYBRID -> SearchQuery.hybrid(text, vector, topK);
        };

        try {
            // Execute search (synchronous, fast — runs on virtual thread)
            SearchResponse response = engine.search(query);

            // Stream results one by one
            ScoredResult[] results = response.results();
            for (int i = 0; i < results.length; i++) {
                ScoredResult result = results[i];
                String data = MAPPER.writeValueAsString(Map.of(
                        "id", result.id(),
                        "score", result.score(),
                        "rank", i + 1
                ));
                client.sendEvent("result", data);
            }

            // Send completion event
            String doneData = MAPPER.writeValueAsString(Map.of(
                    "totalHits", response.totalHits(),
                    "queryTimeMs", response.queryTimeMs(),
                    "mode", response.mode().name()
            ));
            client.sendEvent("done", doneData);

        } catch (Exception e) {
            log.error("Streaming search failed", e);
            try {
                client.sendEvent("error", MAPPER.writeValueAsString(
                        Map.of("error", e.getMessage() != null ? e.getMessage() : "Search failed")));
            } catch (Exception ignored) {}
        }

        client.close();
    }

    private float[] parseVector(String vectorParam) {
        if (vectorParam == null || vectorParam.isBlank()) return null;
        try {
            // Expect comma-separated floats: "0.1,0.2,0.3,..."
            String[] parts = vectorParam.split(",");
            float[] vector = new float[parts.length];
            for (int i = 0; i < parts.length; i++) {
                vector[i] = Float.parseFloat(parts[i].trim());
            }
            return vector;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private SearchQuery.SearchMode resolveMode(String mode, String text, float[] vector) {
        if (mode != null) {
            try {
                return SearchQuery.SearchMode.valueOf(mode.toUpperCase());
            } catch (IllegalArgumentException ignored) {}
        }
        if (text != null && vector != null) return SearchQuery.SearchMode.HYBRID;
        if (vector != null) return SearchQuery.SearchMode.VECTOR;
        return SearchQuery.SearchMode.KEYWORD;
    }
}
