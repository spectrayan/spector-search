/*
 * Copyright 2026 Spectrayan
 */
package com.spectrayan.spector.node.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.ArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.spectrayan.spector.cluster.ClusterCoordinator;
import com.spectrayan.spector.config.SpectorConfig;
import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.node.api.dto.BulkIngestRequest;
import com.spectrayan.spector.node.api.dto.IngestRequest;
import com.spectrayan.spector.node.event.SpectorBulkIngestCompletedEvent;
import com.spectrayan.spector.node.event.SpectorDocumentDeletedEvent;
import com.spectrayan.spector.node.event.SpectorDocumentIngestedEvent;
import com.spectrayan.spector.node.event.SpectorEvent;
import com.spectrayan.spector.node.event.SpectorEventBus;
import com.spectrayan.spector.commons.error.SpectorApiException;
import com.spectrayan.spector.commons.error.SpectorValidationException;

/**
 * Tests for {@link IngestService} — manual ingest, auto-embed, bulk, delete,
 * cluster routing, event publishing, error handling.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IngestService")
class IngestServiceTest {

    @Mock private SpectorEngine engine;
    @Mock private SpectorConfig config;
    private SpectorEventBus eventBus;
    private List<SpectorEvent> publishedEvents;

    private IngestService service;
    private IngestService serviceWithCluster;
    @Mock private ClusterCoordinator coordinator;

    @BeforeEach
    void setUp() {
        eventBus = new SpectorEventBus();
        publishedEvents = new ArrayList<>();
        eventBus.subscribe(publishedEvents::add);

        lenient().when(engine.config()).thenReturn(config);
        lenient().when(config.dimensions()).thenReturn(384);

        service = new IngestService(engine, null, eventBus, "node-1");
        serviceWithCluster = new IngestService(engine, coordinator, eventBus, "node-1");
    }

    // ══════════════════════════════════════════════════════════════
    // Manual ingest
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ingest (manual)")
    class ManualIngestTests {

        @Test @DisplayName("ingests document locally when no cluster")
        void localIngest() {
            var req = new IngestRequest();
            req.id = "doc-1";
            req.content = "Hello HNSW";
            req.vector = new float[384];

            service.ingest(req);

            verify(engine).ingest("doc-1", "", "Hello HNSW", new float[384]);
            assertThat(publishedEvents).hasSize(1);
            assertThat(publishedEvents.get(0)).isInstanceOf(SpectorDocumentIngestedEvent.class);
        }

        @Test @DisplayName("routes to cluster coordinator when available")
        void clusterIngest() {
            var req = new IngestRequest();
            req.id = "doc-1";
            req.content = "Hello";
            req.vector = new float[384];

            serviceWithCluster.ingest(req);

            verify(coordinator).ingest("doc-1", "Hello", new float[384]);
            verify(engine, never()).ingest(anyString(), anyString(), anyString(), any(float[].class));
        }

        @Test @DisplayName("rejects missing id")
        void rejectsMissingId() {
            var req = new IngestRequest();
            req.content = "text";
            req.vector = new float[384];
            assertThatThrownBy(() -> service.ingest(req))
                    .isInstanceOf(SpectorValidationException.class);
        }

        @Test @DisplayName("rejects missing content")
        void rejectsMissingContent() {
            var req = new IngestRequest();
            req.id = "doc-1";
            req.vector = new float[384];
            assertThatThrownBy(() -> service.ingest(req))
                    .isInstanceOf(SpectorValidationException.class);
        }

        @Test @DisplayName("rejects missing vector")
        void rejectsMissingVector() {
            var req = new IngestRequest();
            req.id = "doc-1";
            req.content = "text";
            assertThatThrownBy(() -> service.ingest(req))
                    .isInstanceOf(SpectorValidationException.class);
        }

        @Test @DisplayName("rejects wrong dimension vector")
        void rejectsWrongDimension() {
            var req = new IngestRequest();
            req.id = "doc-1";
            req.content = "text";
            req.vector = new float[768]; // expected 384
            assertThatThrownBy(() -> service.ingest(req))
                    .isInstanceOf(SpectorValidationException.class);
        }

        @Test @DisplayName("uses title when provided")
        void withTitle() {
            var req = new IngestRequest();
            req.id = "doc-1";
            req.title = "My Title";
            req.content = "Hello";
            req.vector = new float[384];

            service.ingest(req);

            verify(engine).ingest("doc-1", "My Title", "Hello", new float[384]);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Auto-embed ingest
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("autoIngest")
    class AutoIngestTests {

        @Test @DisplayName("auto-ingests with embedding provider")
        void autoIngestNoTitle() {
            when(engine.hasEmbeddingProvider()).thenReturn(true);
            var req = new IngestRequest();
            req.id = "doc-1";
            req.content = "Hello";

            service.autoIngest(req);

            verify(engine).ingest("doc-1", "Hello");
            assertThat(publishedEvents).hasSize(1);
            var event = (SpectorDocumentIngestedEvent) publishedEvents.get(0);
            assertThat(event.autoEmbedded()).isTrue();
        }

        @Test @DisplayName("auto-ingests with title")
        void autoIngestWithTitle() {
            when(engine.hasEmbeddingProvider()).thenReturn(true);
            var req = new IngestRequest();
            req.id = "doc-1";
            req.title = "My Doc";
            req.content = "Hello";

            service.autoIngest(req);

            verify(engine).ingest("doc-1", "My Doc", "Hello");
        }

        @Test @DisplayName("throws when no embedding provider")
        void noEmbeddingProvider() {
            when(engine.hasEmbeddingProvider()).thenReturn(false);
            var req = new IngestRequest();
            req.id = "doc-1";
            req.content = "Hello";

            assertThatThrownBy(() -> service.autoIngest(req))
                    .isInstanceOf(SpectorApiException.class);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Bulk ingest
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("bulkIngest")
    class BulkIngestTests {

        @Test @DisplayName("bulk ingests with vectors")
        void bulkWithVectors() {
            var doc1 = new IngestRequest();
            doc1.id = "d1"; doc1.content = "c1"; doc1.vector = new float[384];
            var doc2 = new IngestRequest();
            doc2.id = "d2"; doc2.content = "c2"; doc2.vector = new float[384];
            var bulk = new BulkIngestRequest();
            bulk.documents = List.of(doc1, doc2);

            int[] result = service.bulkIngest(bulk);

            assertThat(result[0]).isEqualTo(2); // total
            assertThat(result[1]).isEqualTo(2); // success
            assertThat(result[2]).isEqualTo(0); // failed
        }

        @Test @DisplayName("bulk skips null id/content")
        void bulkSkipsInvalid() {
            var doc = new IngestRequest();
            doc.id = null;
            doc.content = null;
            var bulk = new BulkIngestRequest();
            bulk.documents = List.of(doc);

            int[] result = service.bulkIngest(bulk);

            assertThat(result[2]).isEqualTo(1); // failed
        }

        @Test @DisplayName("publishes BulkIngestCompletedEvent")
        void publishesBulkEvent() {
            var doc = new IngestRequest();
            doc.id = "d1"; doc.content = "c1"; doc.vector = new float[384];
            var bulk = new BulkIngestRequest();
            bulk.documents = List.of(doc);

            service.bulkIngest(bulk);

            assertThat(publishedEvents).anyMatch(e -> e instanceof SpectorBulkIngestCompletedEvent);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Delete
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("delete")
    class DeleteTests {

        @Test @DisplayName("returns true and publishes event when document exists")
        void deleteExisting() {
            when(engine.delete("doc-1")).thenReturn(true);

            boolean result = service.delete("doc-1");

            assertThat(result).isTrue();
            assertThat(publishedEvents).hasSize(1);
            assertThat(publishedEvents.get(0)).isInstanceOf(SpectorDocumentDeletedEvent.class);
        }

        @Test @DisplayName("returns false and no event when document missing")
        void deleteMissing() {
            when(engine.delete("doc-x")).thenReturn(false);

            boolean result = service.delete("doc-x");

            assertThat(result).isFalse();
            assertThat(publishedEvents).isEmpty();
        }
    }
}
