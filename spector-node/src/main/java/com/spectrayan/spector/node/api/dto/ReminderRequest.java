package com.spectrayan.spector.node.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.commons.error.SpectorValidationException;

/**
 * Request DTO for {@code POST /memory/reminder}.
 *
 * @param text         the reminder text
 * @param delaySeconds seconds until the reminder triggers
 * @param tags         optional comma-separated tags
 */
public record ReminderRequest(
        @JsonProperty("text") String text,
        @JsonProperty("delaySeconds") int delaySeconds,
        @JsonProperty("tags") String tags
) {
    public ReminderRequest {
        if (text == null || text.isBlank()) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "text", "required and must not be blank");
        }
        if (delaySeconds <= 0) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "delaySeconds", 1, Integer.MAX_VALUE, delaySeconds);
        }
    }

    /** Returns tags as an array, or empty array if none provided. */
    public String[] tagsArray() {
        if (tags == null || tags.isBlank()) return new String[0];
        return tags.split(",");
    }
}
