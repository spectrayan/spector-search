/*
 * Copyright 2026 Spectrayan
 */
package com.spectrayan.spector.node;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.cluster.*;
import com.spectrayan.spector.node.api.dto.*;
import com.spectrayan.spector.node.event.*;

/**
 * Comprehensive tests for node DTOs, events, enums, and EventBus.
 */
@DisplayName("Node Module — DTOs, Events, Enums")
class NodeDtoEventTest {

    private static final Instant NOW = Instant.now();

    // ══════════════════════════════════════════════════════════════
    // ProblemDetail (RFC 9457)
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ProblemDetail")
    class ProblemDetailTests {

        @Test @DisplayName("direct construction")
        void directConstruction() {
            var pd = new ProblemDetail(
                    URI.create("https://docs.spectrayan.com/errors/SPE-100-002"),
                    "Validation Error", 400, "bad dims", "/api/v1/ingest",
                    "SPE-100-002", "Validation", NOW.toString());
            assertThat(pd.status()).isEqualTo(400);
            assertThat(pd.title()).isEqualTo("Validation Error");
            assertThat(pd.errorCode()).isEqualTo("SPE-100-002");
        }

        @Test @DisplayName("fromException factory")
        void fromException() {
            var ex = new SpectorValidationException(ErrorCode.DIMENSIONS_MISMATCH, 384, 768);
            var pd = ProblemDetail.fromException(ex, 400, "/api/v1/ingest");
            assertThat(pd.status()).isEqualTo(400);
            assertThat(pd.detail()).contains("384");
            assertThat(pd.errorCode()).startsWith("SPE-");
            assertThat(pd.category()).isEqualTo("Validation");
            assertThat(pd.instance()).isEqualTo("/api/v1/ingest");
        }

        @Test @DisplayName("of() factory for generic errors")
        void ofFactory() {
            var pd = ProblemDetail.of(500, "Internal Server Error", "oops", "/api/v1/search");
            assertThat(pd.type()).isEqualTo(URI.create("about:blank"));
            assertThat(pd.status()).isEqualTo(500);
            assertThat(pd.errorCode()).isNull();
            assertThat(pd.category()).isNull();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Enums
    // ══════════════════════════════════════════════════════════════

    @Test @DisplayName("NodeStatus has expected values")
    void nodeStatusValues() {
        assertThat(NodeStatus.values()).hasSize(3);
        assertThat(NodeStatus.valueOf("ACTIVE")).isNotNull();
        assertThat(NodeStatus.valueOf("UNAVAILABLE")).isNotNull();
        assertThat(NodeStatus.valueOf("SYNCING")).isNotNull();
    }

    @Test @DisplayName("ShardRole has expected values")
    void shardRoleValues() {
        assertThat(ShardRole.values()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(ShardRole.valueOf("PRIMARY")).isNotNull();
    }

    @Test @DisplayName("ReplicaState has expected values")
    void replicaStateValues() {
        assertThat(ReplicaState.values()).hasSizeGreaterThanOrEqualTo(2);
    }

    // ══════════════════════════════════════════════════════════════
    // Events
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Events")
    class EventTests {

        @Test @DisplayName("SpectorNodeStartedEvent")
        void nodeStarted() {
            var e = new SpectorNodeStartedEvent("node-1", NOW, 8080, "standalone");
            assertThat(e.nodeId()).isEqualTo("node-1");
            assertThat(e.port()).isEqualTo(8080);
            assertThat(e.mode()).isEqualTo("standalone");
            assertThat(e).isInstanceOf(SpectorEvent.class);
        }

        @Test @DisplayName("SpectorNodeStoppingEvent")
        void nodeStopping() {
            var e = new SpectorNodeStoppingEvent("node-1", NOW, "shutdown");
            assertThat(e.reason()).isEqualTo("shutdown");
        }

        @Test @DisplayName("SpectorDocumentIngestedEvent")
        void documentIngested() {
            var e = new SpectorDocumentIngestedEvent("node-1", NOW, "doc-1", true);
            assertThat(e.documentId()).isEqualTo("doc-1");
            assertThat(e.autoEmbedded()).isTrue();
        }

        @Test @DisplayName("SpectorDocumentDeletedEvent")
        void documentDeleted() {
            var e = new SpectorDocumentDeletedEvent("node-1", NOW, "doc-1");
            assertThat(e.documentId()).isEqualTo("doc-1");
        }

        @Test @DisplayName("SpectorSearchCompletedEvent")
        void searchCompleted() {
            var e = new SpectorSearchCompletedEvent("node-1", NOW, 10, 50L, "HYBRID");
            assertThat(e.resultCount()).isEqualTo(10);
            assertThat(e.latencyMs()).isEqualTo(50L);
            assertThat(e.searchMode()).isEqualTo("HYBRID");
        }

        @Test @DisplayName("SpectorSearchFailedEvent")
        void searchFailed() {
            var e = new SpectorSearchFailedEvent("node-1", NOW, "VECTOR", "timeout");
            assertThat(e.errorMessage()).isEqualTo("timeout");
            assertThat(e.searchMode()).isEqualTo("VECTOR");
        }

        @Test @DisplayName("SpectorIndexRebuiltEvent")
        void indexRebuilt() {
            var e = new SpectorIndexRebuiltEvent("node-1", NOW, "HNSW", 1000L, 250L);
            assertThat(e.documentCount()).isEqualTo(1000L);
            assertThat(e.indexType()).isEqualTo("HNSW");
        }

        @Test @DisplayName("SpectorBulkIngestCompletedEvent")
        void bulkIngest() {
            var e = new SpectorBulkIngestCompletedEvent("node-1", NOW, 100, 95, 5);
            assertThat(e.totalDocuments()).isEqualTo(100);
            assertThat(e.successCount()).isEqualTo(95);
            assertThat(e.failedCount()).isEqualTo(5);
        }

        @Test @DisplayName("SpectorMcpToolExecutedEvent")
        void mcpToolExecuted() {
            var e = new SpectorMcpToolExecutedEvent("node-1", NOW, "client-1", "search", 100L);
            assertThat(e.toolName()).isEqualTo("search");
            assertThat(e.executionMs()).isEqualTo(100L);
        }

        @Test @DisplayName("SpectorMcpClientConnectedEvent")
        void mcpClientConnected() {
            var e = new SpectorMcpClientConnectedEvent("node-1", NOW, "client-1", "127.0.0.1");
            assertThat(e.clientId()).isEqualTo("client-1");
        }

        @Test @DisplayName("SpectorMcpClientDisconnectedEvent")
        void mcpClientDisconnected() {
            var e = new SpectorMcpClientDisconnectedEvent("node-1", NOW, "client-1");
            assertThat(e.clientId()).isEqualTo("client-1");
        }

        @Test @DisplayName("SpectorNodeJoinedEvent")
        void nodeJoined() {
            var e = new SpectorNodeJoinedEvent("node-1", NOW, "node-2", "192.168.1.2:8080");
            assertThat(e.joinedNodeId()).isEqualTo("node-2");
        }

        @Test @DisplayName("SpectorNodeLeftEvent")
        void nodeLeft() {
            var e = new SpectorNodeLeftEvent("node-1", NOW, "node-2", "decommissioned");
            assertThat(e.leftNodeId()).isEqualTo("node-2");
        }

        @Test @DisplayName("SpectorNodeHealthChangedEvent")
        void healthChanged() {
            var e = new SpectorNodeHealthChangedEvent("node-1", NOW, true, "all good");
            assertThat(e.healthy()).isTrue();
        }

        @Test @DisplayName("SpectorEmbeddingProviderChangedEvent")
        void embeddingChanged() {
            var e = new SpectorEmbeddingProviderChangedEvent("node-1", NOW, "nomic", true);
            assertThat(e.providerName()).isEqualTo("nomic");
            assertThat(e.available()).isTrue();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // SpectorEventBus
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("SpectorEventBus")
    class EventBusTests {

        @Test @DisplayName("subscribe and publish")
        void subscribeAndPublish() {
            var bus = new SpectorEventBus();
            var events = new java.util.ArrayList<SpectorEvent>();
            bus.subscribe(SpectorNodeStartedEvent.class, events::add);
            bus.publish(new SpectorNodeStartedEvent("n1", NOW, 8080, "standalone"));
            assertThat(events).hasSize(1);
        }

        @Test @DisplayName("cancel stops delivery")
        void cancelSubscription() {
            var bus = new SpectorEventBus();
            var events = new java.util.ArrayList<SpectorEvent>();
            var sub = bus.subscribe(SpectorNodeStartedEvent.class, events::add);
            sub.cancel();
            bus.publish(new SpectorNodeStartedEvent("n1", NOW, 8080, "standalone"));
            assertThat(events).isEmpty();
        }

        @Test @DisplayName("different event types are isolated")
        void typeIsolation() {
            var bus = new SpectorEventBus();
            var events = new java.util.ArrayList<SpectorEvent>();
            bus.subscribe(SpectorNodeStartedEvent.class, events::add);
            bus.publish(new SpectorDocumentIngestedEvent("n1", NOW, "doc", false));
            assertThat(events).isEmpty();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Cluster DTOs
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Cluster DTOs")
    class ClusterTests {

        @Test @DisplayName("NodeInfo construction")
        void nodeInfo() {
            var info = new NodeInfo("node-1", "192.168.1.1:8080", NodeStatus.ACTIVE, NOW);
            assertThat(info.nodeId()).isEqualTo("node-1");
            assertThat(info.status()).isEqualTo(NodeStatus.ACTIVE);
        }

        @Test @DisplayName("ShardAssignment")
        void shardAssignment() {
            var sa = new ShardAssignment(0, "192.168.1.1:8080", ShardRole.PRIMARY);
            assertThat(sa.shardIndex()).isZero();
            assertThat(sa.role()).isEqualTo(ShardRole.PRIMARY);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Search DTOs
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Search DTOs")
    class SearchDtoTests {

        @Test @DisplayName("SearchResponseDto construction")
        void searchResponse() {
            var result = Map.<String, Object>of("id", "doc-1", "score", 0.95);
            var sr = new SearchResponseDto(List.of(result), 1, 50L, "HYBRID");
            assertThat(sr.results()).hasSize(1);
        }
    }
}
