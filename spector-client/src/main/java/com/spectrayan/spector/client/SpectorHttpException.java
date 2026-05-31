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
package com.spectrayan.spector.client;

/**
 * Thrown when the server returns an HTTP error response (4xx or 5xx).
 * Contains the HTTP status code, error message from the response body, and the request URL.
 */
public class SpectorHttpException extends SpectorClientException {

    private final int statusCode;
    private final String errorMessage;
    private final String requestUrl;

    public SpectorHttpException(int statusCode, String errorMessage, String requestUrl) {
        super("HTTP " + statusCode + " from " + requestUrl + ": " + errorMessage);
        this.statusCode = statusCode;
        this.errorMessage = errorMessage;
        this.requestUrl = requestUrl;
    }

    /** Returns the HTTP status code from the server response. */
    public int statusCode() {
        return statusCode;
    }

    /** Returns the error message extracted from the response body. */
    public String errorMessage() {
        return errorMessage;
    }

    /** Returns the request URL that produced the error. */
    public String requestUrl() {
        return requestUrl;
    }
}
