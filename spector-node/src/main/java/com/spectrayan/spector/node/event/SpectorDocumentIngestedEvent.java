package com.spectrayan.spector.node.event;

import java.time.Instant;

/** Fired when a document is successfully ingested into the index. */
public record SpectorDocumentIngestedEvent(
        String nodeId, Instant timestamp,
        String documentId, boolean autoEmbedded
) implements SpectorEvent {
    @Override public String eventType() { return "document.ingested"; }
}
