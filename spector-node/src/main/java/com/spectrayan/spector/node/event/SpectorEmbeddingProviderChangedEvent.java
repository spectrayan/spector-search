package com.spectrayan.spector.node.event;

import java.time.Instant;

/** Fired when the embedding provider status changes (connected, disconnected, switched). */
public record SpectorEmbeddingProviderChangedEvent(
        String nodeId, Instant timestamp,
        String providerName, boolean available
) implements SpectorEvent {
    @Override public String eventType() { return "engine.embedding_provider_changed"; }
}
