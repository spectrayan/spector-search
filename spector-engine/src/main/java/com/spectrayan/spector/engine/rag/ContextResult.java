package com.spectrayan.spector.engine.rag;

import java.util.List;

/**
 * Result of context assembly by the {@link ContextBuilder}.
 *
 * @param contextText  the assembled context string (empty if no chunks fit)
 * @param attributions source attribution entries for each included chunk
 * @param isEmpty      indicator that no chunks were included in the context
 */
public record ContextResult(String contextText, List<ChunkAttribution> attributions, boolean isEmpty) {

    public ContextResult {
        if (contextText == null) {
            throw new IllegalArgumentException("contextText must not be null");
        }
        if (attributions == null) {
            throw new IllegalArgumentException("attributions must not be null");
        }
        attributions = List.copyOf(attributions);
    }

    /**
     * Creates an empty context result indicating no chunks were included.
     */
    public static ContextResult empty() {
        return new ContextResult("", List.of(), true);
    }
}
