package com.spectrayan.spector.node.exception;

/**
 * Base exception for Spector API errors.
 *
 * <p>Maps directly to an HTTP status code. Caught by {@link ApiExceptionHandler}
 * and rendered as a JSON error response.</p>
 *
 * <p>Factory methods follow the HTTP status naming convention:</p>
 * <pre>{@code
 *   throw SpectorApiException.badRequest("id is required");
 *   throw SpectorApiException.notFound("Document not found: " + id);
 *   throw SpectorApiException.serviceUnavailable("Embedding service is unavailable");
 * }</pre>
 */
public class SpectorApiException extends RuntimeException {

    private final int statusCode;

    public SpectorApiException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public SpectorApiException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /** HTTP status code for the error response. */
    public int statusCode() {
        return statusCode;
    }

    // ─────────────── Factory methods ───────────────

    /** 400 Bad Request. */
    public static SpectorApiException badRequest(String message) {
        return new SpectorApiException(400, message);
    }

    /** 404 Not Found. */
    public static SpectorApiException notFound(String message) {
        return new SpectorApiException(404, message);
    }

    /** 409 Conflict. */
    public static SpectorApiException conflict(String message) {
        return new SpectorApiException(409, message);
    }

    /** 503 Service Unavailable. */
    public static SpectorApiException serviceUnavailable(String message) {
        return new SpectorApiException(503, message);
    }

    /** 500 Internal Server Error. */
    public static SpectorApiException internal(String message, Throwable cause) {
        return new SpectorApiException(500, message, cause);
    }
}
