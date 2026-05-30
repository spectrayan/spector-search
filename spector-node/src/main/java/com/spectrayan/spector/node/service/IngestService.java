package com.spectrayan.spector.node.service;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.cluster.ClusterCoordinator;
import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.node.api.dto.BulkIngestRequest;
import com.spectrayan.spector.node.api.dto.IngestRequest;
import com.spectrayan.spector.node.event.SpectorBulkIngestCompletedEvent;
import com.spectrayan.spector.node.event.SpectorDocumentDeletedEvent;
import com.spectrayan.spector.node.event.SpectorDocumentIngestedEvent;
import com.spectrayan.spector.node.event.SpectorEventBus;
import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.commons.error.SpectorException;

/**
 * Ingest service facade — encapsulates local vs cluster routing for document ingestion.
 *
 * <p>Handles three ingestion modes:</p>
 * <ul>
 *   <li><b>Manual</b> — client provides pre-computed vector</li>
 *   <li><b>Auto-embed</b> — engine embeds the content automatically</li>
 *   <li><b>Bulk</b> — batch of documents, mixed modes</li>
 * </ul>
 *
 * <p>Publishes {@link SpectorDocumentIngestedEvent} for each successful ingestion
 * and {@link SpectorBulkIngestCompletedEvent} for bulk operations.</p>
 */
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final SpectorEngine engine;
    private final ClusterCoordinator coordinator; // nullable
    private final SpectorEventBus eventBus;
    private final String nodeId;

    public IngestService(SpectorEngine engine, ClusterCoordinator coordinator,
                         SpectorEventBus eventBus, String nodeId) {
        this.engine = engine;
        this.coordinator = coordinator;
        this.eventBus = eventBus;
        this.nodeId = nodeId;
    }

    /**
     * Ingests a document with a pre-computed vector.
     */
    public void ingest(IngestRequest request) throws com.spectrayan.spector.commons.error.SpectorException {
        request.validateForIngest(engine.config().dimensions());

        if (coordinator != null) {
            coordinator.ingest(request.id, request.content, request.vector);
        } else {
            engine.ingest(request.id, request.titleOrEmpty(), request.content, request.vector);
        }

        eventBus.publish(new SpectorDocumentIngestedEvent(
                nodeId, Instant.now(), request.id, false));
    }

    /**
     * Ingests a document with automatic embedding.
     */
    public void autoIngest(IngestRequest request) throws com.spectrayan.spector.commons.error.SpectorException {
        request.validateForAutoIngest();

        if (!engine.hasEmbeddingProvider()) {
            throw com.spectrayan.spector.commons.error.SpectorApiException.conflict(
                    com.spectrayan.spector.commons.error.ErrorCode.EMBEDDING_PROVIDER_MISSING);
        }

        if (request.title != null && !request.title.isEmpty()) {
            engine.ingest(request.id, request.title, request.content);
        } else {
            engine.ingest(request.id, request.content);
        }

        eventBus.publish(new SpectorDocumentIngestedEvent(
                nodeId, Instant.now(), request.id, true));
    }

    /**
     * Bulk ingests multiple documents.
     *
     * @return array of [total, success, failed]
     */
    public int[] bulkIngest(BulkIngestRequest request) throws com.spectrayan.spector.commons.error.SpectorException {
        request.validate();

        int success = 0;
        int failed = 0;

        for (var doc : request.documents) {
            try {
                if (doc.id == null || doc.content == null) {
                    failed++;
                    continue;
                }
                if (doc.vector != null && doc.vector.length > 0) {
                    if (coordinator != null) {
                        coordinator.ingest(doc.id, doc.content, doc.vector);
                    } else {
                        engine.ingest(doc.id, doc.titleOrEmpty(), doc.content, doc.vector);
                    }
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

        eventBus.publish(new SpectorBulkIngestCompletedEvent(
                nodeId, Instant.now(), request.documents.size(), success, failed));

        return new int[]{request.documents.size(), success, failed};
    }

    /**
     * Deletes a document by ID.
     *
     * @return true if the document was found and deleted
     */
    public boolean delete(String id) {
        boolean deleted = engine.delete(id);
        if (deleted) {
            eventBus.publish(new SpectorDocumentDeletedEvent(
                    nodeId, Instant.now(), id));
        }
        return deleted;
    }
}
