package com.spectrayan.spector.node.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.commons.error.SpectorValidationException;

/**
 * Request DTO for {@code POST /memory/introspect}.
 *
 * @param topic the topic to introspect (e.g., "kubernetes", "user preferences")
 */
public record IntrospectRequest(
        @JsonProperty("topic") String topic
) {
    public IntrospectRequest {
        if (topic == null || topic.isBlank()) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "topic", "required and must not be blank");
        }
    }
}
