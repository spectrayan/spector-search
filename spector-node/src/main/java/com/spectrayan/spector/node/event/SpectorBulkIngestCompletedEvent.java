package com.spectrayan.spector.node.event;

import java.time.Instant;

/** Fired when a bulk ingestion batch completes. */
public record SpectorBulkIngestCompletedEvent(
        String nodeId, Instant timestamp,
        int totalDocuments, int successCount, int failedCount
) implements SpectorEvent {
    @Override public String eventType() { return "document.bulk_completed"; }
}
