package com.spectrayan.spector.rag;

import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Source attribution metadata for a chunk included in the assembled context.
 *
 * @param documentId  the identifier of the source document
 * @param chunkOffset the offset (index) of the chunk within the source document
 */
public record ChunkAttribution(String documentId, int chunkOffset) {

    public ChunkAttribution {
        if (documentId == null || documentId.isBlank()) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "documentId");
        }
        if (chunkOffset < 0) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NEGATIVE, "chunkOffset", 0);
        }
    }
}
