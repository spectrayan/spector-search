package com.spectrayan.spector.node.api.dto;

import java.util.List;

import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.commons.error.SpectorValidationException;

/**
 * Request DTO for bulk document ingestion ({@code POST /api/v1/ingest/bulk}).
 */
public class BulkIngestRequest {

    /** List of documents to ingest. */
    public List<IngestRequest> documents;

    /**
     * Validates that the documents list is non-empty.
     *
     * @throws ValidationException if validation fails
     */
    public void validate() throws SpectorValidationException {
        if (documents == null || documents.isEmpty()) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "documents", "non-empty array required");
        }
    }
}
