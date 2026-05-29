package com.spectrayan.spector.node.event;

import java.time.Instant;

/** Fired when a document is deleted from the index. */
public record SpectorDocumentDeletedEvent(
        String nodeId, Instant timestamp,
        String documentId
) implements SpectorEvent {
    @Override public String eventType() { return "document.deleted"; }
}
