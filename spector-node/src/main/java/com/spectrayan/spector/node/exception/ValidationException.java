package com.spectrayan.spector.node.exception;

/**
 * Validation exception for request parameter errors.
 *
 * <p>Always maps to HTTP 400 Bad Request. Use for input validation
 * failures like missing required fields, out-of-range values, or
 * dimension mismatches.</p>
 */
public class ValidationException extends SpectorApiException {

    public ValidationException(String message) {
        super(400, message);
    }

    public ValidationException(String field, String reason) {
        super(400, field + ": " + reason);
    }
}
