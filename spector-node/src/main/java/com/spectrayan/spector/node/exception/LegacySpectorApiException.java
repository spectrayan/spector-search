package com.spectrayan.spector.node.exception;

/**
 * Legacy API exception for backward compatibility.
 *
 * @deprecated Use {@link com.spectrayan.spector.commons.error.SpectorApiException} instead.
 *             This class is retained for backward compatibility and will be removed in v0.2.0.
 */
@Deprecated(since = "0.1.0", forRemoval = true)
public class LegacySpectorApiException extends RuntimeException {

    private final int statusCode;

    public LegacySpectorApiException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public LegacySpectorApiException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /** HTTP status code for the error response. */
    public int statusCode() {
        return statusCode;
    }

    /** 400 Bad Request. */
    public static LegacySpectorApiException badRequest(String message) {
        return new LegacySpectorApiException(400, message);
    }

    /** 404 Not Found. */
    public static LegacySpectorApiException notFound(String message) {
        return new LegacySpectorApiException(404, message);
    }

    /** 409 Conflict. */
    public static LegacySpectorApiException conflict(String message) {
        return new LegacySpectorApiException(409, message);
    }

    /** 503 Service Unavailable. */
    public static LegacySpectorApiException serviceUnavailable(String message) {
        return new LegacySpectorApiException(503, message);
    }

    /** 500 Internal Server Error. */
    public static LegacySpectorApiException internal(String message, Throwable cause) {
        return new LegacySpectorApiException(500, message, cause);
    }
}
