package com.spectrayan.spector.node.event;

import java.time.Instant;

/**
 * SSE event: {@code ingestion.completed} — emitted when an async ingestion task finishes.
 *
 * <p>Carries the final summary: total chunks stored, failures, duration,
 * and overall success/failure status. The UI uses this to show a
 * notification bell alert.</p>
 */
public record SpectorIngestionCompletedEvent(
        String nodeId, Instant timestamp,
        String taskId, String description,
        int chunksStored, int failures,
        long durationMs, boolean success
) implements SpectorEvent {
    @Override public String eventType() { return "ingestion.completed"; }
}
