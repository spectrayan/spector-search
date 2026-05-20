package com.spectrayan.spector.server;

import java.util.List;

/**
 * Response DTO for the RAG endpoint ({@code POST /api/v1/rag}).
 */
public class RagResponse {

    /** The assembled context string. Empty when no matches found. */
    public String context;

    /** Source attributions for each chunk included in the context. */
    public List<Attribution> attributions;

    /** Message providing additional information (e.g., no matches found). */
    public String message;

    public RagResponse() {}

    public RagResponse(String context, List<Attribution> attributions, String message) {
        this.context = context;
        this.attributions = attributions;
        this.message = message;
    }

    /**
     * Source attribution entry for a chunk in the assembled context.
     */
    public static class Attribution {
        public String documentId;
        public int chunkOffset;

        public Attribution() {}

        public Attribution(String documentId, int chunkOffset) {
            this.documentId = documentId;
            this.chunkOffset = chunkOffset;
        }
    }
}
