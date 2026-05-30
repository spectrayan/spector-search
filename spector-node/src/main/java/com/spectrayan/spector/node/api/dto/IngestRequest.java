package com.spectrayan.spector.node.api.dto;

import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.commons.error.SpectorValidationException;

/**
 * Request DTO for document ingestion ({@code POST /api/v1/ingest}).
 *
 * <p>For auto-embedding (no vector provided), use {@code POST /api/v1/ingest/auto}
 * which does not require the {@code vector} field.</p>
 */
public class IngestRequest {

    /** Document ID (required). */
    public String id;

    /** Optional document title. */
    public String title;

    /** Document content (required). */
    public String content;

    /** Pre-computed embedding vector (required for /ingest, optional for /ingest/auto). */
    public float[] vector;

    /**
     * Validates the required fields for manual ingestion (with vector).
     *
     * @param expectedDimensions the expected vector dimensions from engine config
     * @throws ValidationException if validation fails
     */
    public void validateForIngest(int expectedDimensions) throws SpectorValidationException {
        if (id == null || id.isEmpty()) throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "id", "required");
        if (content == null || content.isEmpty()) throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "content", "required");
        if (vector == null || vector.length == 0) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "vector", "required (use /api/v1/ingest/auto for auto-embedding)");
        }
        if (vector.length != expectedDimensions) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "vector", "dimension mismatch: expected " + expectedDimensions + ", got " + vector.length);
        }
    }

    /**
     * Validates the required fields for auto-embedding ingestion.
     *
     * @throws ValidationException if validation fails
     */
    public void validateForAutoIngest() throws SpectorValidationException {
        if (id == null || id.isEmpty()) throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "id", "required");
        if (content == null || content.isEmpty()) throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "content", "required");
    }

    /** Returns the title, defaulting to empty string if null. */
    public String titleOrEmpty() {
        return title != null ? title : "";
    }
}
