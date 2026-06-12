package com.spectrayan.spector.node.event;

import java.time.Instant;

/**
 * Sealed base interface for all Spector node events.
 *
 * <p>Follows Spring/Redis naming convention: {@code Spector[Domain][Action]Event}.
 * Events are published via {@link SpectorEventBus} and consumed by subscribers
 * (SSE clients, metrics collectors, audit loggers, etc.).</p>
 *
 * <h3>Event Categories</h3>
 * <ul>
 *   <li><b>Lifecycle</b>: Node start, stop, health changes</li>
 *   <li><b>Search</b>: Query completed, query failed</li>
 *   <li><b>Document</b>: Ingested, deleted, bulk completed</li>
 *   <li><b>Cluster</b>: Node joined, left, shard rebalanced, replica synced</li>
 *   <li><b>MCP</b>: Client connected, disconnected, tool executed</li>
 *   <li><b>Engine</b>: Index rebuilt, embedding provider changed</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   eventBus.publish(new SpectorSearchCompletedEvent("node-1", 5, 12L, "HYBRID"));
 *   eventBus.subscribe(event -> {
 *       switch (event) {
 *           case SpectorSearchCompletedEvent e -> log.info("Search: {} results in {}ms", e.resultCount(), e.latencyMs());
 *           case SpectorDocumentIngestedEvent e -> log.info("Ingested: {}", e.documentId());
 *           default -> {}
 *       }
 *   });
 * }</pre>
 */
public sealed interface SpectorEvent permits
        // ── Lifecycle ──
        SpectorNodeStartedEvent,
        SpectorNodeStoppingEvent,
        SpectorNodeHealthChangedEvent,
        // ── Search ──
        SpectorSearchCompletedEvent,
        SpectorSearchFailedEvent,
        // ── Document ──
        SpectorDocumentIngestedEvent,
        SpectorDocumentDeletedEvent,
        SpectorBulkIngestCompletedEvent,
        // ── Cluster ──
        SpectorNodeJoinedEvent,
        SpectorNodeLeftEvent,
        SpectorShardRebalancedEvent,
        SpectorReplicaSyncCompletedEvent,
        // ── MCP ──
        SpectorMcpClientConnectedEvent,
        SpectorMcpClientDisconnectedEvent,
        SpectorMcpToolExecutedEvent,
        // ── Engine ──
        SpectorIndexRebuiltEvent,
        SpectorEmbeddingProviderChangedEvent,
        // ── Ingestion Tasks ──
        SpectorIngestionProgressEvent,
        SpectorIngestionCompletedEvent,
        // ── Cortex Dashboard ──
        SpectorCortexQueryTraceEvent,
        SpectorCortexSimdLaneEvent,
        SpectorCortexMemoryDiagnosticEvent,
        SpectorCortexGraphPulseEvent,
        SpectorCortexReflectCycleEvent,
        SpectorCortexMemorySnapshotEvent,
        SpectorCortexGpuKernelEvent,
        SpectorCortexClusterTopologyEvent,
        SpectorCortexEmbeddingProjectionEvent {

    /** Timestamp when the event occurred. */
    Instant timestamp();

    /** Node ID that originated the event. */
    String nodeId();

    /** Event type name (e.g., "search.completed"). Used in SSE {@code event:} field. */
    String eventType();
}
