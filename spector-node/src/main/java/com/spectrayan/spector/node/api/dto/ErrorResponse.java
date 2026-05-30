package com.spectrayan.spector.node.api.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Standard JSON error response for all Spector API errors.
 *
 * <p>Returned by {@link com.spectrayan.spector.node.exception.ApiExceptionHandler}
 * for any request that fails with a known or unknown error.</p>
 *
 * <h3>Example (with error code)</h3>
 * <pre>{@code
 * {
 *   "error": {
 *     "code": "SPE-100-002",
 *     "category": "VALIDATION",
 *     "message": "Expected 384 dimensions but received 768",
 *     "status": 400,
 *     "path": "/api/v1/ingest",
 *     "timestamp": "2026-05-30T12:00:00Z"
 *   }
 * }
 * }</pre>
 *
 * <h3>Example (legacy, no error code)</h3>
 * <pre>{@code
 * {
 *   "error": {
 *     "message": "id is required",
 *     "status": 400,
 *     "path": "/api/v1/ingest",
 *     "timestamp": "2026-05-30T12:00:00Z"
 *   }
 * }
 * }</pre>
 *
 * @param code      SPE-XXX-YYY error code (null for legacy errors)
 * @param category  error category name (null for legacy errors)
 * @param message   human-readable error message
 * @param status    HTTP status code
 * @param path      request path that caused the error
 * @param timestamp ISO-8601 timestamp of the error
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        String code,
        String category,
        String message,
        int status,
        String path,
        String timestamp
) {

    /** Factory method for structured errors with error code. */
    public static ErrorResponse of(String code, String category, int status,
                                    String message, String path) {
        return new ErrorResponse(code, category, message, status, path, Instant.now().toString());
    }

    /** Factory method for legacy errors (no error code). */
    public static ErrorResponse of(int status, String message, String path) {
        return new ErrorResponse(null, null, message, status, path, Instant.now().toString());
    }
}
