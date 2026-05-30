package com.spectrayan.spector.node;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.cors.CorsService;
import com.linecorp.armeria.server.encoding.EncodingService;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.healthcheck.HealthCheckService;
import com.linecorp.armeria.server.logging.AccessLogWriter;

import com.spectrayan.spector.cluster.ClusterCoordinator;
import com.spectrayan.spector.cluster.SpectorSearchServiceImpl;
import com.spectrayan.spector.config.SpectorConfig;
import com.spectrayan.spector.core.simd.SimdCapability;
import com.spectrayan.spector.engine.DefaultSpectorEngine;
import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.memory.SpectorMemory;
import com.spectrayan.spector.metrics.MeteredSpectorEngine;
import com.spectrayan.spector.metrics.SpectorMetrics;
import com.spectrayan.spector.metrics.SpectorJvmMetrics;
import com.spectrayan.spector.node.api.ApiModule;
import com.spectrayan.spector.node.api.v1.*;
import com.spectrayan.spector.node.event.*;
import com.spectrayan.spector.node.service.IngestService;
import com.spectrayan.spector.node.service.RagService;
import com.spectrayan.spector.node.service.SearchService;
import com.spectrayan.spector.runtime.SpectorRuntime;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

/**
 * Unified Spector node — serves HTTP REST, gRPC, and Prometheus metrics
 * on a single Armeria (Netty) port.
 *
 * <h3>Architecture</h3>
 * <p>Every Spector node is identical. In standalone mode it serves local search.
 * In clustered mode it additionally participates in cluster membership and
 * fans out queries to peer nodes via gRPC — all on the same port.</p>
 *
 * <h3>Protocols Served (single port)</h3>
 * <ul>
 *   <li><b>HTTP REST</b>: {@code /api/v1/*} — client-facing APIs (via {@link ApiModule})</li>
 *   <li><b>gRPC</b>: auto-detected via {@code application/grpc} content-type</li>
 *   <li><b>Prometheus</b>: {@code /metrics} — scrape endpoint</li>
 *   <li><b>Health</b>: {@code /health} — K8s readiness/liveness probes</li>
 *   <li><b>SSE Events</b>: {@code /api/v1/events} — live event stream</li>
 * </ul>
 *
 * <h3>Design Patterns</h3>
 * <ul>
 *   <li><b>Facade</b>: {@link SearchService}, {@link IngestService}, {@link RagService}</li>
 *   <li><b>Observer</b>: {@link SpectorEventBus} — pub/sub for all node events</li>
 *   <li><b>Factory</b>: {@link ApiModule} — pluggable endpoint registration</li>
 *   <li><b>Strategy</b>: Local vs cluster routing in service facades</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   SpectorNode node = SpectorNode.create(NodeConfig.standalone(7070, 384));
 *   node.start();
 * }</pre>
 */
public class SpectorNode implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SpectorNode.class);

    private final NodeConfig nodeConfig;
    private final SpectorEngine engine;
    private final SpectorMemory memory; // nullable
    private final PrometheusMeterRegistry prometheusRegistry;
    private final SpectorEventBus eventBus;
    private final ClusterCoordinator coordinator; // null in standalone
    private Server server;

    private SpectorNode(NodeConfig nodeConfig, SpectorEngine engine, SpectorMemory memory,
                        PrometheusMeterRegistry prometheusRegistry, SpectorEventBus eventBus,
                        ClusterCoordinator coordinator) {
        this.nodeConfig = nodeConfig;
        this.engine = engine;
        this.memory = memory;
        this.prometheusRegistry = prometheusRegistry;
        this.eventBus = eventBus;
        this.coordinator = coordinator;
    }

    /**
     * Creates a SpectorNode with default engine configuration.
     *
     * @param nodeConfig node configuration
     * @return a new SpectorNode instance (not yet started)
     */
    public static SpectorNode create(NodeConfig nodeConfig) {
        var prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        SpectorMetrics.init(prometheusRegistry);
        SpectorJvmMetrics.bind(prometheusRegistry);

        var engineConfig = SpectorConfig.DEFAULT.withDimensions(nodeConfig.dimensions());
        SpectorEngine engine = new DefaultSpectorEngine(engineConfig);
        engine = new MeteredSpectorEngine(engine, prometheusRegistry);

        var eventBus = new SpectorEventBus();

        // Cluster coordinator (null in standalone)
        ClusterCoordinator coordinator = null;
        if (nodeConfig.isClustered()) {
            // TODO: wire ClusterConfig from seed nodes
            log.info("Clustered mode enabled — seed nodes: {}", nodeConfig.seedNodes());
        }

        return new SpectorNode(nodeConfig, engine, null, prometheusRegistry, eventBus, coordinator);
    }

    /**
     * Creates a SpectorNode backed by a pre-configured runtime.
     *
     * @param runtime    the Spector runtime (engine + memory)
     * @param nodeConfig node configuration
     * @return a new SpectorNode instance (not yet started)
     */
    public static SpectorNode create(SpectorRuntime runtime, NodeConfig nodeConfig) {
        var prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        SpectorMetrics.init(prometheusRegistry);
        SpectorJvmMetrics.bind(prometheusRegistry);

        SpectorEngine engine = runtime.engine() instanceof MeteredSpectorEngine
                ? runtime.engine()
                : new MeteredSpectorEngine(runtime.engine(), prometheusRegistry);

        var eventBus = new SpectorEventBus();

        return new SpectorNode(nodeConfig, engine, runtime.memory(), prometheusRegistry, eventBus, null);
    }

    /**
     * Starts the Armeria server.
     *
     * <p>Builds the service facades, registers versioned API modules,
     * configures gRPC, health, Prometheus, CORS, auth, and compression.
     * Blocks until the server is fully started.</p>
     */
    public void start() {
        // ── Build service facades ──
        SearchService searchService = new SearchService(engine, coordinator, eventBus, nodeConfig.nodeId());
        IngestService ingestService = new IngestService(engine, coordinator, eventBus, nodeConfig.nodeId());
        RagService ragService = new RagService(engine);

        // ── Assemble API v1 modules ──
        List<ApiModule> v1Modules = List.of(
                new SearchEndpoint(searchService),
                new IngestEndpoint(ingestService),
                new RagEndpoint(ragService),
                new DocumentEndpoint(ingestService),
                new StatusEndpoint(engine, nodeConfig, eventBus, coordinator),
                new EventStreamEndpoint(eventBus)
        );

        // ── Build Armeria server ──
        ServerBuilder sb = Server.builder()
                .http(nodeConfig.port())
                .maxNumConnections(nodeConfig.maxConnections())
                .requestTimeout(nodeConfig.requestTimeout())
                .idleTimeout(nodeConfig.idleTimeout());

        // Register v1 API modules
        for (ApiModule module : v1Modules) {
            sb.annotatedService("/api/v1" + module.pathPrefix(), module);
        }

        // ── gRPC (auto-detected via content-type: application/grpc) ──
        sb.service(GrpcService.builder()
                .addService(new SpectorSearchServiceImpl(nodeConfig.nodeId(), engine))
                .build());

        // ── Health check (K8s readiness/liveness) ──
        sb.service("/health", HealthCheckService.of());

        // ── Prometheus metrics ──
        sb.service("/metrics", (ctx, req) ->
                HttpResponse.of(HttpStatus.OK,
                        MediaType.parse("text/plain; version=0.0.4; charset=utf-8"),
                        prometheusRegistry.scrape()));

        // ── API key authentication ──
        if (nodeConfig.apiKey() != null && !nodeConfig.apiKey().isBlank()) {
            sb.decorator("/api/", (delegate, ctx, req) -> {
                String provided = req.headers().get("X-API-Key");
                if (!nodeConfig.apiKey().equals(provided)) {
                    return HttpResponse.ofJson(HttpStatus.UNAUTHORIZED,
                            Map.of("error", "Invalid or missing API key"));
                }
                return delegate.serve(ctx, req);
            });
        }

        // ── CORS ──
        sb.decorator(CorsService.builderForAnyOrigin()
                .allowRequestMethods(HttpMethod.GET, HttpMethod.POST, HttpMethod.DELETE, HttpMethod.OPTIONS)
                .allowRequestHeaders("Content-Type", "X-API-Key", "Authorization")
                .newDecorator());

        // ── Response compression (configurable) ──
        if (nodeConfig.compressionEnabled()) {
            sb.decorator(EncodingService.builder()
                    .minBytesToForceChunkedEncoding(1024)
                    .newDecorator());
        }

        // ── Access logging ──
        sb.accessLogWriter(AccessLogWriter.combined(), true);

        // ── Build and start ──
        server = sb.build();
        CompletableFuture<Void> future = server.start();
        future.join();

        // Publish started event
        eventBus.publish(new SpectorNodeStartedEvent(
                nodeConfig.nodeId(), Instant.now(),
                nodeConfig.port(), nodeConfig.mode().name()));

        log.info("SpectorNode '{}' started on port {} — mode={}, dims={}, auth={}, compression={}, {}",
                nodeConfig.nodeId(),
                nodeConfig.port(),
                nodeConfig.mode(),
                engine.config().dimensions(),
                nodeConfig.apiKey() != null ? "API-key" : "none",
                nodeConfig.compressionEnabled() ? "enabled" : "disabled",
                SimdCapability.report());
    }

    /**
     * Stops the server and closes the engine.
     */
    @Override
    public void close() {
        eventBus.publish(new SpectorNodeStoppingEvent(
                nodeConfig.nodeId(), Instant.now(), "shutdown"));

        if (server != null) {
            server.stop().join();
        }
        engine.close();
        log.info("SpectorNode '{}' stopped", nodeConfig.nodeId());
    }

    /** Returns the underlying engine. */
    public SpectorEngine engine() { return engine; }

    /** Returns the node configuration. */
    public NodeConfig config() { return nodeConfig; }

    /** Returns the event bus for subscribing to node events. */
    public SpectorEventBus eventBus() { return eventBus; }

    /** Returns the Armeria server (for testing). */
    public Server server() { return server; }

    // ─────────────── Main ───────────────

    /**
     * Entry point for the Spector node.
     *
     * <p>Reads configuration from environment variables. In standalone mode,
     * starts a local search node. In clustered mode, joins the cluster.</p>
     */
    public static void main(String[] args) {
        NodeConfig nodeConfig = NodeConfig.fromEnv();
        SpectorNode node = SpectorNode.create(nodeConfig);

        Runtime.getRuntime().addShutdownHook(new Thread(node::close));
        node.start();

        log.info("Spector ready — http://localhost:{}/health", nodeConfig.port());
    }
}
