package com.spectrayan.spector.node.exception;

/**
 * Validation exception for request parameter errors.
 *
 * <p>Always maps to HTTP 400 Bad Request.</p>
 *
 * @deprecated Use {@link com.spectrayan.spector.commons.error.SpectorValidationException} instead.
 */
@Deprecated(since = "0.1.0", forRemoval = true)
public class ValidationException extends LegacySpectorApiException {

    public ValidationException(String message) {
        super(400, message);
    }

    public ValidationException(String field, String reason) {
        super(400, field + ": " + reason);
    }
}
