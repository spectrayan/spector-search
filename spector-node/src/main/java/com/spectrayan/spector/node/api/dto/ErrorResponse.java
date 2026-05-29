package com.spectrayan.spector.node.api.dto;

import java.time.Instant;

/**
 * Standard JSON error response for all Spector API errors.
 *
 * <p>Returned by {@link com.spectrayan.spector.node.exception.ApiExceptionHandler}
 * for any request that fails with a known or unknown error.</p>
 *
 * <h3>Example</h3>
 * <pre>{@code
 * {
 *   "error": "id is required",
 *   "status": 400,
 *   "path": "/api/v1/ingest",
 *   "timestamp": "2026-05-29T19:58:00Z"
 * }
 * }</pre>
 *
 * @param error     human-readable error message
 * @param status    HTTP status code
 * @param path      request path that caused the error
 * @param timestamp ISO-8601 timestamp of the error
 */
public record ErrorResponse(
        String error,
        int status,
        String path,
        String timestamp
) {

    /** Factory method with auto-timestamp. */
    public static ErrorResponse of(int status, String error, String path) {
        return new ErrorResponse(error, status, path, Instant.now().toString());
    }
}
