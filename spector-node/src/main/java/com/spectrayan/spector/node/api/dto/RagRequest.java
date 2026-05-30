package com.spectrayan.spector.node.api.dto;

import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.commons.error.SpectorValidationException;

/**
 * Request DTO for the RAG endpoint ({@code POST /api/v1/rag}).
 *
 * <p>Accepts a query string plus optional retrieval parameters.
 * The query is embedded, searched, and assembled into a context
 * string within a token limit.</p>
 */
public class RagRequest {

    private static final int MAX_QUERY_LENGTH = 2000;

    /** The query text (1–2000 characters, required). */
    public String query;

    /** Maximum number of chunks to retrieve (1–100, default 5). */
    public Integer topK;

    /** Maximum token limit for assembled context (1–8192, default 4096). */
    public Integer tokenLimit;

    /** Search mode: "vector" or "hybrid" (default "vector"). */
    public String searchMode;

    /**
     * Validates the request.
     *
     * @throws ValidationException if validation fails
     */
    public void validate() {
        if (query == null || query.isBlank()) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "query", "non-empty query is required");
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "query", "must not exceed " + MAX_QUERY_LENGTH + " characters");
        }
    }

    /** Returns topK, clamped to [1, 100] with default 5. */
    public int resolvedTopK() {
        return clamp(topK != null ? topK : 5, 1, 100);
    }

    /** Returns token limit, clamped to [1, 8192] with default 4096. */
    public int resolvedTokenLimit() {
        return clamp(tokenLimit != null ? tokenLimit : 4096, 1, 8192);
    }

    /** Whether to use hybrid search mode. */
    public boolean isHybrid() {
        return "hybrid".equalsIgnoreCase(searchMode);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
