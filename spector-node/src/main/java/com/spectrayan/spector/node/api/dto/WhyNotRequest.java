package com.spectrayan.spector.node.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.commons.error.SpectorValidationException;

/**
 * Request DTO for {@code POST /memory/why-not}.
 *
 * @param memoryId the ID of the memory to investigate
 * @param query    the query it was expected to match
 * @param topK     the topK used in the original recall (optional, default 5)
 */
public record WhyNotRequest(
        @JsonProperty("memoryId") String memoryId,
        @JsonProperty("query") String query,
        @JsonProperty("topK") Integer topK
) {
    public WhyNotRequest {
        if (memoryId == null || memoryId.isBlank()) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "memoryId", "required and must not be blank");
        }
        if (query == null || query.isBlank()) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "query", "required and must not be blank");
        }
    }

    /** Returns the effective topK, defaulting to 5 if not specified. */
    public int effectiveTopK() {
        return topK != null ? topK : 5;
    }
}
