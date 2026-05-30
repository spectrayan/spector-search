package com.spectrayan.spector.node.api.dto;

import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.query.SearchQuery;

/**
 * Request DTO for the search endpoint ({@code POST /api/v1/search}).
 *
 * <p>Supports keyword, vector, and hybrid search modes. The mode is
 * auto-detected from the provided fields if not explicitly set.</p>
 */
public class SearchRequest {

    /** Query text for keyword/hybrid search. */
    public String text;

    /** Query vector for vector/hybrid search. */
    public float[] vector;

    /** Explicit search mode: "KEYWORD", "VECTOR", "HYBRID" (auto-detected if null). */
    public String mode;

    /** Number of results to return (default: 10). */
    public int topK;

    /**
     * Resolves the search mode from explicit mode or field presence.
     */
    public SearchQuery.SearchMode resolvedMode() {
        if (mode != null) {
            try {
                return SearchQuery.SearchMode.valueOf(mode.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // Invalid mode string — fall through to auto-detection
                System.getLogger(SearchRequest.class.getName())
                        .log(System.Logger.Level.WARNING, "Unknown search mode ''{0}'', auto-detecting", mode);
            }
        }
        if (text != null && vector != null) return SearchQuery.SearchMode.HYBRID;
        if (vector != null) return SearchQuery.SearchMode.VECTOR;
        return SearchQuery.SearchMode.KEYWORD;
    }

    /**
     * Converts this request to a {@link SearchQuery}.
     *
     * @return the search query
     * @throws ValidationException if the request is invalid
     */
    public SearchQuery toQuery() {
        int k = topK > 0 ? topK : 10;
        return switch (resolvedMode()) {
            case KEYWORD -> {
                if (text == null || text.isBlank()) throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "text", "required for keyword search");
                yield SearchQuery.keyword(text, k);
            }
            case VECTOR -> {
                if (vector == null || vector.length == 0) throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "vector", "required for vector search");
                yield SearchQuery.vector(vector, k);
            }
            case HYBRID -> {
                if (text == null || text.isBlank()) throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "text", "required for hybrid search");
                if (vector == null || vector.length == 0) throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "vector", "required for hybrid search");
                yield SearchQuery.hybrid(text, vector, k);
            }
        };
    }
}
