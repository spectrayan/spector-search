package com.spectrayan.spector.ingestion;

import java.util.List;

/**
 * Outcome of an ingestion operation.
 *
 * @param documentId   the parent document ID
 * @param chunksStored number of chunks successfully stored
 * @param failures     list of chunk IDs that failed (empty on full success)
 * @param durationMs   total time spent in milliseconds
 */
public record IngestionResult(
        String documentId,
        int chunksStored,
        List<String> failures,
        long durationMs
) {
    /** Creates a successful single-document result. */
    public static IngestionResult single(String documentId, long durationMs) {
        return new IngestionResult(documentId, 1, List.of(), durationMs);
    }

    /** Creates a chunked result. */
    public static IngestionResult chunked(String documentId, int chunks, List<String> failures, long durationMs) {
        return new IngestionResult(documentId, chunks, failures, durationMs);
    }

    /** Returns true if all chunks were stored successfully. */
    public boolean isFullSuccess() {
        return failures.isEmpty();
    }
}
