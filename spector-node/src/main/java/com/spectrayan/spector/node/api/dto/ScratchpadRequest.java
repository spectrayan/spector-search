package com.spectrayan.spector.node.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.commons.error.SpectorValidationException;

/**
 * Request DTO for {@code POST /memory/scratchpad}.
 *
 * @param text the scratchpad note to store in working memory
 */
public record ScratchpadRequest(
        @JsonProperty("text") String text
) {
    public ScratchpadRequest {
        if (text == null || text.isBlank()) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "text", "required and must not be blank");
        }
    }
}
