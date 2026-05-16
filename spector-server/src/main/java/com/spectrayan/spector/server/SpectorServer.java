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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * REST API server for the Spector Search engine.
 *
 * <p>Built on Javalin, a lightweight REST framework that uses virtual threads
 * for request handling. Provides endpoints for document ingestion, search,
 * deletion, bulk operations, and metrics.</p>
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code GET  /health}              — Health check</li>
 *   <li>{@code GET  /api/v1/status}       — Engine status &amp; SIMD info</li>
 *   <li>{@code POST /api/v1/ingest}       — Ingest a document (vector required)</li>
 *   <li>{@code POST /api/v1/ingest/auto}  — Ingest with auto-embedding (text only)</li>
 *   <li>{@code POST /api/v1/ingest/bulk}  — Bulk ingest multiple documents</li>
 *   <li>{@code POST /api/v1/search}       — Search (keyword/vector/hybrid)</li>
 *   <li>{@code DELETE /api/v1/documents/{id}} — Delete a document</li>
 *   <li>{@code GET  /api/v1/metrics}      — Request metrics</li>
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
    private final String apiKey; // nullable — when set, requires X-API-Key header

    // ── Metrics ──
    private final LongAdder totalRequests = new LongAdder();
    private final LongAdder totalSearches = new LongAdder();
    private final LongAdder totalIngestions = new LongAdder();
    private final LongAdder totalErrors = new LongAdder();
    private final AtomicLong startTime = new AtomicLong();

    /**
     * Creates a server with the given engine, port, and optional API key.
     */
    public SpectorServer(SpectorEngine engine, int port, String apiKey) {
        this.engine = engine;
        this.port = port;
        this.apiKey = apiKey;

        this.app = Javalin.create(config -> {
            config.useVirtualThreads = true;
            config.showJavalinBanner = false;

            // ── CORS support ──
            config.bundledPlugins.enableCors(cors -> {
                cors.addRule(rule -> {
                    rule.anyHost();
                    rule.allowCredentials = false;
                });
            });
        });

        registerRoutes();
    }

    /**
     * Creates a server with the given engine and port (no API key).
     */
    public SpectorServer(SpectorEngine engine, int port) {
        this(engine, port, null);
    }

    /** Creates a server with default config on port 7070. */
    public SpectorServer() {
        this(new SpectorEngine(), 7070, null);
    }

    /**
     * Starts the server.
     */
    public SpectorServer start() {
        startTime.set(System.currentTimeMillis());
        app.start(port);
        log.info("SpectorServer started on port {} (CORS=enabled, auth={})",
                port, apiKey != null ? "API-key" : "none");
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
        // ── Authentication (before handler) ──
        if (apiKey != null && !apiKey.isBlank()) {
            app.before("/api/*", ctx -> {
                String provided = ctx.header("X-API-Key");
                if (!apiKey.equals(provided)) {
                    ctx.status(401).json(Map.of("error", "Invalid or missing API key"));
                    ctx.skipRemainingHandlers();
                }
            });
        }

        // ── Request counting (before handler) ──
        app.before(ctx -> totalRequests.increment());

        // ── Error handlers ──
        app.exception(IllegalArgumentException.class, (e, ctx) -> {
            totalErrors.increment();
            ctx.status(400).json(Map.of("error", e.getMessage()));
        });
        app.exception(IllegalStateException.class, (e, ctx) -> {
            totalErrors.increment();
            ctx.status(409).json(Map.of("error", e.getMessage()));
        });
        app.exception(Exception.class, (e, ctx) -> {
            totalErrors.increment();
            log.error("Unhandled exception", e);
            ctx.status(500).json(Map.of("error", "Internal server error"));
        });

        // ── Routes ──
        // Health check
        app.get("/health", ctx -> ctx.json(Map.of("status", "ok")));

        // Status
        app.get("/api/v1/status", this::handleStatus);

        // Ingest (with vector)
        app.post("/api/v1/ingest", this::handleIngest);

        // Ingest with auto-embedding (text only)
        app.post("/api/v1/ingest/auto", this::handleAutoIngest);

        // Bulk ingest
        app.post("/api/v1/ingest/bulk", this::handleBulkIngest);

        // Search
        app.post("/api/v1/search", this::handleSearch);

        // Delete
        app.delete("/api/v1/documents/{id}", this::handleDelete);

        // Metrics
        app.get("/api/v1/metrics", this::handleMetrics);
    }

    // ─────────────── Handlers ───────────────

    private void handleStatus(Context ctx) {
        var status = Map.of(
                "engine", "spector-search",
                "version", "0.1.0-SNAPSHOT",
                "documents", engine.documentCount(),
                "dimensions", engine.config().dimensions(),
                "similarity", engine.config().similarityFunction().name(),
                "indexType", engine.config().indexType().name(),
                "gpu", engine.isGpuActive() ? "active" : "inactive",
                "reranker", engine.isRerankerActive() ? engine.reranker().modelName() : "disabled",
                "embedding", engine.hasEmbeddingProvider() ? "configured" : "none",
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
            ctx.status(400).json(Map.of("error", "vector is required (use /api/v1/ingest/auto for auto-embedding)"));
            return;
        }
        if (request.vector.length != engine.config().dimensions()) {
            ctx.status(400).json(Map.of("error",
                    "vector dimension mismatch: expected " + engine.config().dimensions()
                            + ", got " + request.vector.length));
            return;
        }

        engine.ingest(request.id, request.title != null ? request.title : "", request.content, request.vector);
        totalIngestions.increment();

        ctx.status(201).json(Map.of(
                "id", request.id,
                "indexed", true
        ));
    }

    private void handleAutoIngest(Context ctx) throws Exception {
        var request = MAPPER.readValue(ctx.body(), AutoIngestRequest.class);

        if (request.id == null || request.id.isEmpty()) {
            ctx.status(400).json(Map.of("error", "id is required"));
            return;
        }
        if (request.content == null || request.content.isEmpty()) {
            ctx.status(400).json(Map.of("error", "content is required"));
            return;
        }
        if (!engine.hasEmbeddingProvider()) {
            ctx.status(409).json(Map.of("error",
                    "Auto-embed requires an EmbeddingProvider. Configure the engine with an embedding provider."));
            return;
        }

        if (request.title != null && !request.title.isEmpty()) {
            engine.ingest(request.id, request.title, request.content);
        } else {
            engine.ingest(request.id, request.content);
        }
        totalIngestions.increment();

        ctx.status(201).json(Map.of(
                "id", request.id,
                "indexed", true,
                "autoEmbedded", true
        ));
    }

    private void handleBulkIngest(Context ctx) throws Exception {
        var request = MAPPER.readValue(ctx.body(), BulkIngestRequest.class);

        if (request.documents == null || request.documents.isEmpty()) {
            ctx.status(400).json(Map.of("error", "documents array is required"));
            return;
        }

        int success = 0;
        int failed = 0;
        for (var doc : request.documents) {
            try {
                if (doc.id == null || doc.content == null) {
                    failed++;
                    continue;
                }
                if (doc.vector != null && doc.vector.length > 0) {
                    engine.ingest(doc.id,
                            doc.title != null ? doc.title : "",
                            doc.content, doc.vector);
                } else if (engine.hasEmbeddingProvider()) {
                    engine.ingest(doc.id, doc.content);
                } else {
                    failed++;
                    continue;
                }
                success++;
            } catch (Exception e) {
                failed++;
                log.warn("Bulk ingest failed for doc '{}': {}", doc.id, e.getMessage());
            }
        }
        totalIngestions.add(success);

        ctx.status(201).json(Map.of(
                "total", request.documents.size(),
                "success", success,
                "failed", failed
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
        totalSearches.increment();

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

    private void handleDelete(Context ctx) {
        String id = ctx.pathParam("id");
        boolean deleted = engine.delete(id);

        if (deleted) {
            ctx.json(Map.of("id", id, "deleted", true));
        } else {
            ctx.status(404).json(Map.of("error", "Document not found: " + id));
        }
    }

    private void handleMetrics(Context ctx) {
        long uptimeMs = System.currentTimeMillis() - startTime.get();
        ctx.json(Map.of(
                "uptimeMs", uptimeMs,
                "totalRequests", totalRequests.sum(),
                "totalSearches", totalSearches.sum(),
                "totalIngestions", totalIngestions.sum(),
                "totalErrors", totalErrors.sum(),
                "documents", engine.documentCount(),
                "gpu", engine.isGpuActive(),
                "reranker", engine.isRerankerActive()
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

    /** Auto-embed ingest request body (no vector needed). */
    public static class AutoIngestRequest {
        public String id;
        public String title;
        public String content;
    }

    /** Bulk ingest request body. */
    public static class BulkIngestRequest {
        public List<IngestRequest> documents;
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
        String apiKey = args.length > 2 ? args[2] : null;

        var config = SpectorConfig.DEFAULT.withDimensions(dims);
        var engine = new SpectorEngine(config);
        var server = new SpectorServer(engine, port, apiKey);

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        server.start();

        log.info("Spector Search ready — http://localhost:{}/health", port);
    }
}
