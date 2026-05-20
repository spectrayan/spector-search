package com.spectrayan.spector.engine.rag;

/**
 * Source attribution metadata for a chunk included in the assembled context.
 *
 * @param documentId  the identifier of the source document
 * @param chunkOffset the offset (index) of the chunk within the source document
 */
public record ChunkAttribution(String documentId, int chunkOffset) {

    public ChunkAttribution {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId must not be null or blank");
        }
        if (chunkOffset < 0) {
            throw new IllegalArgumentException("chunkOffset must not be negative");
        }
    }
}
