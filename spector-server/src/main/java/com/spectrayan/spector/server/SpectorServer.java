package com.spectrayan.spector.server;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import com.spectrayan.spector.core.SimdCapability;
import com.spectrayan.spector.engine.SpectorConfig;
import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.index.ScoredResult;
import com.spectrayan.spector.query.SearchQuery;
import com.spectrayan.spector.query.SearchResponse;

import io.javalin.Javalin;
import io.javalin.http.Context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * REST API server for the Spector Search engine.
 *
 * <p>Built on Javalin, a lightweight REST framework that uses virtual threads
 * for request handling. Provides endpoints for document ingestion and
 * keyword/vector/hybrid search.</p>
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code GET  /health}          — Health check</li>
 *   <li>{@code GET  /api/v1/status}   — Engine status & SIMD info</li>
 *   <li>{@code POST /api/v1/ingest}   — Ingest a document</li>
 *   <li>{@code POST /api/v1/search}   — Search (keyword/vector/hybrid)</li>
 * </ul>
 */
public class SpectorServer {

    private static final Logger log = LoggerFactory.getLogger(SpectorServer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private final SpectorEngine engine;
    private final Javalin app;
    private final int port;

    /**
     * Creates a server with the given engine and port.
     */
    public SpectorServer(SpectorEngine engine, int port) {
        this.engine = engine;
        this.port = port;

        this.app = Javalin.create(config -> {
            config.useVirtualThreads = true;
            config.showJavalinBanner = false;
        });

        registerRoutes();
    }

    /** Creates a server with default config on port 7070. */
    public SpectorServer() {
        this(new SpectorEngine(), 7070);
    }

    /**
     * Starts the server.
     */
    public SpectorServer start() {
        app.start(port);
        log.info("SpectorServer started on port {}", port);
        return this;
    }

    /**
     * Stops the server and closes the engine.
     */
    public void stop() {
        app.stop();
        engine.close();
        log.info("SpectorServer stopped");
    }

    /** Returns the underlying Javalin app (for testing). */
    public Javalin app() {
        return app;
    }

    // ─────────────── Route Registration ───────────────

    private void registerRoutes() {
        // Health check
        app.get("/health", ctx -> ctx.json(Map.of("status", "ok")));

        // Status
        app.get("/api/v1/status", this::handleStatus);

        // Ingest
        app.post("/api/v1/ingest", this::handleIngest);

        // Search
        app.post("/api/v1/search", this::handleSearch);
    }

    // ─────────────── Handlers ───────────────

    private void handleStatus(Context ctx) {
        var status = Map.of(
                "engine", "spector-search",
                "version", "0.1.0-SNAPSHOT",
                "documents", engine.documentCount(),
                "dimensions", engine.config().dimensions(),
                "similarity", engine.config().similarityFunction().name(),
                "simd", SimdCapability.report()
        );
        ctx.json(status);
    }

    private void handleIngest(Context ctx) throws Exception {
        var request = MAPPER.readValue(ctx.body(), IngestRequest.class);

        if (request.id == null || request.id.isEmpty()) {
            ctx.status(400).json(Map.of("error", "id is required"));
            return;
        }
        if (request.content == null || request.content.isEmpty()) {
            ctx.status(400).json(Map.of("error", "content is required"));
            return;
        }
        if (request.vector == null || request.vector.length == 0) {
            ctx.status(400).json(Map.of("error", "vector is required"));
            return;
        }

        engine.ingest(request.id, request.title != null ? request.title : "", request.content, request.vector);

        ctx.status(201).json(Map.of(
                "id", request.id,
                "indexed", true
        ));
    }

    private void handleSearch(Context ctx) throws Exception {
        var request = MAPPER.readValue(ctx.body(), SearchRequest.class);

        if (request.topK <= 0) request.topK = 10;

        SearchQuery query = switch (request.resolvedMode()) {
            case KEYWORD -> SearchQuery.keyword(request.text, request.topK);
            case VECTOR -> SearchQuery.vector(request.vector, request.topK);
            case HYBRID -> SearchQuery.hybrid(request.text, request.vector, request.topK);
        };

        SearchResponse response = engine.search(query);

        var resultList = Arrays.stream(response.results())
                .map(r -> Map.of(
                        "id", (Object) r.id(),
                        "score", (Object) r.score()
                ))
                .toList();

        ctx.json(Map.of(
                "results", resultList,
                "totalHits", response.totalHits(),
                "queryTimeMs", response.queryTimeMs(),
                "mode", response.mode().name()
        ));
    }

    // ─────────────── Request DTOs ───────────────

    /** Ingest request body. */
    public static class IngestRequest {
        public String id;
        public String title;
        public String content;
        public float[] vector;
    }

    /** Search request body. */
    public static class SearchRequest {
        public String text;
        public float[] vector;
        public String mode;  // "KEYWORD", "VECTOR", "HYBRID"
        public int topK;

        SearchQuery.SearchMode resolvedMode() {
            if (mode != null) {
                try {
                    return SearchQuery.SearchMode.valueOf(mode.toUpperCase());
                } catch (IllegalArgumentException e) {
                    // fall through
                }
            }
            // Auto-detect based on what's provided
            if (text != null && vector != null) return SearchQuery.SearchMode.HYBRID;
            if (vector != null) return SearchQuery.SearchMode.VECTOR;
            return SearchQuery.SearchMode.KEYWORD;
        }
    }

    // ─────────────── Main ───────────────

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 7070;
        int dims = args.length > 1 ? Integer.parseInt(args[1]) : 384;

        var config = SpectorConfig.DEFAULT.withDimensions(dims);
        var engine = new SpectorEngine(config);
        var server = new SpectorServer(engine, port);

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        server.start();

        log.info("Spector Search ready — http://localhost:{}/health", port);
    }
}
