/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.spectrayan.spector.commons.error;

/**
 * Exception for HTTP API errors with status code mapping ({@code SPE-500-xxx}).
 *
 * <p>Extends {@link SpectorServerException} with an HTTP status code for REST API
 * error responses. Used by the API exception handler to build structured JSON
 * error responses.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   throw SpectorApiException.badRequest(ErrorCode.API_BAD_REQUEST, "id is required");
 *   throw SpectorApiException.notFound(ErrorCode.API_NOT_FOUND, "doc-123");
 * }</pre>
 *
 * @see ErrorCode#API_BAD_REQUEST
 * @see ErrorCode#API_NOT_FOUND
 */
public class SpectorApiException extends SpectorServerException {

    private final int httpStatus;

    /**
     * Creates an API exception with an HTTP status code and error code.
     *
     * @param httpStatus the HTTP response status code (e.g. 400, 404, 500)
     * @param errorCode  the stable Spector error code
     * @param args       values for message template placeholders
     */
    public SpectorApiException(int httpStatus, ErrorCode errorCode, Object... args) {
        super(errorCode, args);
        this.httpStatus = httpStatus;
    }

    /**
     * Creates an API exception with an HTTP status code, error code, and cause.
     *
     * @param httpStatus the HTTP response status code
     * @param errorCode  the stable Spector error code
     * @param cause      the underlying exception
     * @param args       values for message template placeholders
     */
    public SpectorApiException(int httpStatus, ErrorCode errorCode, Throwable cause, Object... args) {
        super(errorCode, cause, args);
        this.httpStatus = httpStatus;
    }

    /** The HTTP status code for the error response. */
    public int httpStatus() {
        return httpStatus;
    }

    // ─────────────── Factory methods ───────────────

    /** 400 Bad Request. */
    public static SpectorApiException badRequest(ErrorCode errorCode, Object... args) {
        return new SpectorApiException(400, errorCode, args);
    }

    /** 404 Not Found. */
    public static SpectorApiException notFound(ErrorCode errorCode, Object... args) {
        return new SpectorApiException(404, errorCode, args);
    }

    /** 409 Conflict. */
    public static SpectorApiException conflict(ErrorCode errorCode, Object... args) {
        return new SpectorApiException(409, errorCode, args);
    }

    /** 401 Unauthorized. */
    public static SpectorApiException unauthorized() {
        return new SpectorApiException(401, ErrorCode.API_UNAUTHORIZED);
    }

    /** 503 Service Unavailable. */
    public static SpectorApiException serviceUnavailable(ErrorCode errorCode, Object... args) {
        return new SpectorApiException(503, errorCode, args);
    }

    /** 500 Internal Server Error. */
    public static SpectorApiException internal(ErrorCode errorCode, Throwable cause, Object... args) {
        return new SpectorApiException(500, errorCode, cause, args);
    }
}
