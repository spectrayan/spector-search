package com.spectrayan.spector.rag;

import java.util.List;

/**
 * Output from a RAG pipeline execution.
 *
 * @param contextText  the assembled context string for LLM prompting
 * @param attributions source attributions for included chunks
 * @param message      optional message (e.g., "No matching documents found")
 * @param queryTimeMs  total pipeline execution time in milliseconds
 */
public record RagResponse(
        String contextText,
        List<Attribution> attributions,
        String message,
        long queryTimeMs
) {

    /**
     * Source attribution for a chunk in the context.
     *
     * @param documentId  source document ID
     * @param chunkOffset chunk offset within the document
     */
    public record Attribution(String documentId, int chunkOffset) {}

    /** Creates an empty response when no results are found. */
    public static RagResponse empty(long queryTimeMs) {
        return new RagResponse("", List.of(), "No matching documents were found", queryTimeMs);
    }
}
