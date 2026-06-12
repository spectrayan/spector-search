package com.spectrayan.spector.node.event;

import java.time.Instant;

/**
 * SSE event: {@code ingestion.progress} — emitted periodically during async ingestion.
 *
 * <p>Tracks chunking/embedding/storage progress for a single ingestion task.
 * Published every N chunks (or at meaningful milestones) to keep the UI
 * progress bar updated without flooding the event bus.</p>
 */
public record SpectorIngestionProgressEvent(
        String nodeId, Instant timestamp,
        String taskId, String description,
        int chunksStored, int totalChunks,
        int failures, double progressPercent
) implements SpectorEvent {
    @Override public String eventType() { return "ingestion.progress"; }
}
