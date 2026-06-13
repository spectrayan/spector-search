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
import com.spectrayan.spector.events.*;
import com.spectrayan.spector.memory.SpectorMemory;
import com.spectrayan.spector.metrics.MeteredSpectorEngine;
import com.spectrayan.spector.metrics.SpectorMetrics;
import com.spectrayan.spector.metrics.SpectorJvmMetrics;
import com.spectrayan.spector.node.api.ApiModule;
import com.spectrayan.spector.node.api.v1.*;
import com.spectrayan.spector.node.event.*;
import com.spectrayan.spector.node.service.CortexMetricsPublisher;
import com.spectrayan.spector.config.CortexTelemetryConfig;
import com.spectrayan.spector.node.service.IngestService;
import com.spectrayan.spector.node.service.IngestionTaskService;
import com.spectrayan.spector.node.service.MemoryService;
import com.spectrayan.spector.node.service.RagService;
import com.spectrayan.spector.node.service.SearchService;
import com.spectrayan.spector.runtime.SpectorRuntime;

import com.spectrayan.spector.mcp.SpectorMcpServer;
import com.spectrayan.spector.node.mcp.ArmeriaMcpTransport;

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
    private final CortexTelemetryConfig cortexConfig;
    private MemoryService memoryService; // nullable — only when memory subsystem is present
    private IngestionTaskService taskService; // nullable — only when memory subsystem is present
    private CortexMetricsPublisher cortexPublisher; // nullable
    private TelemetryBus telemetryBus; // nullable
    private SpectorRuntime runtime; // nullable — set when created from runtime
    private SpectorMcpServer mcpServer; // nullable — MCP SSE server
    private ArmeriaMcpTransport mcpTransport; // nullable
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
        this.cortexConfig = CortexTelemetryConfig.fromSystemProperties();
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

        var node = new SpectorNode(nodeConfig, engine, runtime.memory().orElse(null), prometheusRegistry, eventBus, null);
        node.runtime = runtime;
        return node;
    }

    /**
     * Starts the Armeria server.
     *
     * <p>Builds the service facades, registers versioned API modules,
     * configures gRPC, health, Prometheus, CORS, auth, and compression.
     * Blocks until the server is fully started.</p>
     */
    public void start() {
        // ── Create TelemetryBus (must be first — passed to services) ──
        telemetryBus = new TelemetryBus();
        telemetryBus.subscribe(this::convertAndPublish);

        // ── Build service facades ──
        SearchService searchService = new SearchService(engine, coordinator, eventBus, nodeConfig.nodeId(), cortexConfig, telemetryBus);
        IngestService ingestService = new IngestService(engine, coordinator, eventBus, nodeConfig.nodeId());
        RagService ragService = new RagService(engine);

        // ── Memory service (if memory subsystem is present) ──
        if (memory != null) {
            this.memoryService = new MemoryService(memory, eventBus, nodeConfig.nodeId());
        }

        // ── Assemble API v1 modules ──
        var v1Modules = new java.util.ArrayList<ApiModule>();
        v1Modules.add(new SearchEndpoint(searchService));
        v1Modules.add(new IngestEndpoint(ingestService));
        v1Modules.add(new RagEndpoint(ragService));
        v1Modules.add(new DocumentEndpoint(ingestService));
        v1Modules.add(new StatusEndpoint(engine, nodeConfig, eventBus, coordinator));

        if (memoryService != null) {
            var ingestionHandler = runtime != null ? runtime.ingestion() : null;
            this.taskService = new IngestionTaskService(eventBus, nodeConfig.nodeId());
            v1Modules.add(new MemoryEndpoint(memoryService, ingestionHandler, taskService));
        }

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

        // SSE event stream — needs unlimited timeout (long-lived connection)
        sb.annotatedService()
                .pathPrefix("/api/v1")
                .requestTimeout(java.time.Duration.ZERO)
                .build(new EventStreamEndpoint(eventBus));

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

        // ── MCP transport (same port, no extra server) ──
        if (nodeConfig.mcpEnabled() && runtime != null) {
            mcpTransport = new ArmeriaMcpTransport();
            mcpServer = new SpectorMcpServer(runtime);
            mcpServer.buildMcpServer(mcpTransport);

            // Streamable HTTP (MCP 2025-03-26) — single endpoint at /mcp
            sb.service("/mcp", mcpTransport.streamableHttpService());

            log.info("MCP Streamable HTTP enabled at /mcp (stateless mode)");
        } else if (nodeConfig.mcpEnabled()) {
            log.warn("MCP enabled but no SpectorRuntime available — MCP disabled");
        }

        // ── CORS ──
        sb.decorator(CorsService.builderForAnyOrigin()
                .allowRequestMethods(HttpMethod.GET, HttpMethod.POST, HttpMethod.DELETE, HttpMethod.OPTIONS)
                .allowRequestHeaders("Content-Type", "X-API-Key", "Authorization", "Mcp-Session-Id")
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

        // ── Start cortex metrics publisher ──
        cortexPublisher = new CortexMetricsPublisher(
                prometheusRegistry, eventBus, nodeConfig.nodeId(), cortexConfig, coordinator);
        cortexPublisher.start();
    }

    /**
     * Converts lightweight {@link TelemetryEvent} records from the telemetry bus
     * into full {@link SpectorEvent} records (adding nodeId + timestamp) and
     * publishes them to the SSE event bus.
     */
    private void convertAndPublish(TelemetryEvent event) {
        String nid = nodeConfig.nodeId();
        Instant now = Instant.now();

        switch (event) {
            case SimdKernelTelemetry e -> eventBus.publish(new SpectorCortexSimdLaneEvent(
                    nid, now, e.kernelName(), e.laneWidth(), e.vectorsProcessed(),
                    e.durationNanos() / 1_000, 0L));

            case GraphPulseTelemetry e -> eventBus.publish(new SpectorCortexGraphPulseEvent(
                    nid, now, e.nodesVisited(), e.edgesTraversed(),
                    e.maxDepth(), e.durationNanos() / 1_000));

            case GpuKernelTelemetry e -> eventBus.publish(new SpectorCortexGpuKernelEvent(
                    nid, now, e.streamIndex(), e.kernelName(),
                    e.durationNanos() / 1_000,
                    e.gridDimX(), e.gridDimY(), e.gridDimZ(),
                    e.blockDimX(), e.blockDimY(), e.blockDimZ(),
                    e.memoryTransferBytes()));

            case QueryTraceTelemetry e -> eventBus.publish(new SpectorCortexQueryTraceEvent(
                    nid, now, e.queryText(), e.cognitiveProfile(), 0,
                    e.totalRecords(), e.afterTombstone(), e.afterTagGate(),
                    e.afterValence(), e.afterDecay(), e.afterVector(),
                    e.finalTopK(), 0, 0, 0, e.latencyMicros()));

            case MemorySnapshotTelemetry e -> eventBus.publish(new SpectorCortexMemorySnapshotEvent(
                    nid, now, e.phase(), e.reflectCycleId(),
                    e.hebbianEdgeCount(), e.temporalLinkCount(),
                    e.entityNodeCount(), e.entityEdgeCount(),
                    e.offHeapBytes(), e.tombstoneCount(),
                    e.coActivationPairs(), e.stdpEdges()));

            case ReflectCycleTelemetry e -> eventBus.publish(new SpectorCortexReflectCycleEvent(
                    nid, now, e.hebbianEdgesDecayed(), e.hebbianEdgesRemoved(),
                    e.decayFactor(), e.durationMs()));

            case MemoryDiagnosticTelemetry e -> eventBus.publish(new SpectorCortexMemoryDiagnosticEvent(
                    nid, now, e.offHeapAllocated(), e.pinnedBytes(),
                    e.jvmHeapUsed(), e.jvmHeapMax(),
                    e.gpuAllocated(), e.gpuFree(),
                    e.softPageFaults(), e.hardPageFaults(),
                    e.workingCount(), e.episodicCount(), e.semanticCount(), e.proceduralCount(),
                    e.hebbianEdges(), e.temporalLinks(),
                    e.entityNodes(), e.entityEdges(),
                    e.coActivationPairs(), e.stdpEdges()));

            case ClusterTopologyTelemetry e -> {
                var nodes = e.nodes().stream()
                        .map(n -> new SpectorCortexClusterNodeInfo(
                                n.nodeId(), n.status(), n.shardCount(),
                                n.memoryUsedBytes(), n.queryRate()))
                        .toList();
                eventBus.publish(new SpectorCortexClusterTopologyEvent(
                        nid, now, nodes, e.replicationLinks()));
            }

            case EmbeddingProjectionTelemetry e -> {
                var dtos = e.points().stream()
                        .map(p -> new SpectorCortexEmbeddingProjectionEvent.ProjectedPointDto(
                                p.id(), p.x(), p.y(), p.z(),
                                p.tier(), p.importance(), p.label()))
                        .toList();
                eventBus.publish(new SpectorCortexEmbeddingProjectionEvent(
                        nid, now, dtos, e.queryProjection()));
            }
        }
    }

    /**
     * Stops the server and closes all subsystems (engine, memory, graphs).
     *
     * <h3>Shutdown Order</h3>
     * <ol>
     *   <li>Publish stopping event (notifies SSE clients)</li>
     *   <li>Stop telemetry publishers</li>
     *   <li>Stop HTTP/gRPC server (drain in-flight requests)</li>
     *   <li>Stop MCP server</li>
     *   <li>Close runtime (engine + memory + graph persistence)</li>
     * </ol>
     *
     * <p>When backed by a {@link SpectorRuntime}, the runtime handles closing
     * both the engine and memory subsystem (including flush of HebbianGraph,
     * TemporalChain, EntityGraph). When standalone (no runtime), the engine
     * is closed directly.</p>
     */
    @Override
    public void close() {
        eventBus.publish(new SpectorNodeStoppingEvent(
                nodeConfig.nodeId(), Instant.now(), "shutdown"));

        if (cortexPublisher != null) {
            cortexPublisher.close();
        }
        if (telemetryBus != null) {
            telemetryBus.close();
        }
        if (server != null) {
            server.stop().join();
        }
        if (mcpServer != null) {
            mcpServer.stop();
        }

        // Close runtime (handles engine + memory shutdown) or engine directly.
        // Runtime.close() flushes all cognitive graphs and WAL before releasing
        // off-heap memory. Doing this via runtime avoids double-closing engine.
        if (runtime != null) {
            runtime.close();
        } else {
            engine.close();
            if (memory != null) {
                memory.close();
            }
        }
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

        // Load config (spector-defaults.yml has memory.enabled=true, mode=MEMORY)
        var props = com.spectrayan.spector.config.SpectorProperties.load();

        // ── Embedding provider (Ollama) ──
        var embeddingConfig = com.spectrayan.spector.config.SpectorConfigFactory.embeddingDefaults(props);
        var embedConfig = com.spectrayan.spector.embed.EmbeddingConfig.ollama(embeddingConfig.model())
                .withBaseUrl(embeddingConfig.baseUrl())
                .withTimeout(embeddingConfig.timeout());
        var embedder = new com.spectrayan.spector.embed.ollama.OllamaEmbeddingProvider(embedConfig);
        log.info("Embedding provider: model={}, baseUrl={}", embeddingConfig.model(), embeddingConfig.baseUrl());

        // ── LLM provider for entity extraction + tag extraction ──
        var memoryConfig = com.spectrayan.spector.config.SpectorConfigFactory.memoryDefaults(props);
        com.spectrayan.spector.embed.TextGenerationProvider llmProvider = null;
        String tagModel = memoryConfig.tagExtractorModel();
        if (tagModel != null && !tagModel.isBlank()) {
            llmProvider = com.spectrayan.spector.embed.ollama.OllamaLlmProvider.create(
                    tagModel, embeddingConfig.baseUrl());
            log.info("LLM provider: model={}, baseUrl={}", tagModel, embeddingConfig.baseUrl());
        }

        SpectorRuntime runtime = SpectorRuntime.from(props, embedder, llmProvider);
        SpectorNode node = SpectorNode.create(runtime, nodeConfig);

        // ── JVM Shutdown Hook (SIGTERM from Docker, Ctrl+C, etc.) ──
        // SpectorNode.close() handles the full shutdown lifecycle:
        //   server stop → memory flush (graphs, WAL) → engine close
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("JVM shutdown hook: stopping SpectorNode...");
            node.close();
            log.info("JVM shutdown hook: SpectorNode stopped cleanly");
        }, "spector-node-shutdown"));
        node.start();

        log.info("Spector ready — http://localhost:{}/health (memory={})",
                nodeConfig.port(), runtime.hasMemory());
    }
}
