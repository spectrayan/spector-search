package com.spectrayan.spector.node.api.dto;

import java.net.URI;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.commons.error.SpectorException;

/**
 * RFC 9457 (Problem Details for HTTP APIs) compliant error response.
 *
 * <p>Content-Type: {@code application/problem+json}</p>
 *
 * <h3>Standard Members (RFC 9457 §3.1)</h3>
 * <ul>
 *   <li>{@code type}     — URI reference identifying the problem type</li>
 *   <li>{@code title}    — short human-readable summary of the problem type</li>
 *   <li>{@code status}   — HTTP status code</li>
 *   <li>{@code detail}   — human-readable explanation specific to this occurrence</li>
 *   <li>{@code instance} — URI reference identifying the specific occurrence</li>
 * </ul>
 *
 * <h3>Extension Members (Spector-specific)</h3>
 * <ul>
 *   <li>{@code errorCode}  — Spector error code, e.g. {@code "SPE-100-002"}</li>
 *   <li>{@code category}   — error category, e.g. {@code "Validation"}</li>
 *   <li>{@code timestamp}  — ISO-8601 timestamp of when the error occurred</li>
 * </ul>
 *
 * <h3>Example</h3>
 * <pre>{@code
 * {
 *   "type": "https://docs.spectrayan.com/errors/SPE-100-002",
 *   "title": "Validation Error",
 *   "status": 400,
 *   "detail": "[SPE-100-002] Expected 384 dimensions but received 768",
 *   "instance": "/api/v1/ingest",
 *   "errorCode": "SPE-100-002",
 *   "category": "Validation",
 *   "timestamp": "2026-05-30T12:00:00Z"
 * }
 * }</pre>
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9457">RFC 9457</a>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"type", "title", "status", "detail", "instance", "errorCode", "category", "timestamp"})
public record ProblemDetail(

        /** URI reference identifying the problem type (RFC 9457 §3.1.1). */
        @JsonProperty("type")
        URI type,

        /** Short human-readable summary of the problem type (RFC 9457 §3.1.3). */
        @JsonProperty("title")
        String title,

        /** HTTP status code (RFC 9457 §3.1.2). */
        @JsonProperty("status")
        int status,

        /** Human-readable explanation specific to this occurrence (RFC 9457 §3.1.4). */
        @JsonProperty("detail")
        String detail,

        /** URI reference identifying the specific occurrence (RFC 9457 §3.1.5). */
        @JsonProperty("instance")
        String instance,

        // ── Spector extension members ──

        /** Spector error code, e.g. "SPE-100-002". */
        @JsonProperty("errorCode")
        String errorCode,

        /** Error category display name, e.g. "Validation". */
        @JsonProperty("category")
        String category,

        /** ISO-8601 timestamp of the error. */
        @JsonProperty("timestamp")
        String timestamp
) {
    /** Base URI for Spector error type documentation. */
    private static final String ERROR_TYPE_BASE = "https://docs.spectrayan.com/errors/";

    /** Default type URI for unknown/untyped errors (RFC 9457 §3.1.1). */
    private static final URI ABOUT_BLANK = URI.create("about:blank");

    /**
     * Creates a ProblemDetail from a {@link SpectorException}.
     *
     * @param e        the Spector exception
     * @param status   the HTTP status code to return
     * @param instance the request path that triggered the error
     * @return a fully populated ProblemDetail
     */
    public static ProblemDetail fromException(SpectorException e, int status, String instance) {
        ErrorCode code = e.errorCode();
        String codeId = e.codeId();
        return new ProblemDetail(
                URI.create(ERROR_TYPE_BASE + codeId),
                code.category().displayName() + " Error",
                status,
                e.getMessage(),
                instance,
                codeId,
                code.category().displayName(),
                Instant.now().toString()
        );
    }

    /**
     * Creates a ProblemDetail for an error without a Spector error code.
     *
     * <p>Per RFC 9457 §3.1.1, when no specific type is defined, the type
     * SHOULD be {@code "about:blank"}.</p>
     *
     * @param status   the HTTP status code
     * @param title    short description (e.g. "Bad Request")
     * @param detail   specific error message
     * @param instance the request path
     * @return a ProblemDetail with no Spector extensions
     */
    public static ProblemDetail of(int status, String title, String detail, String instance) {
        return new ProblemDetail(
                ABOUT_BLANK,
                title,
                status,
                detail,
                instance,
                null,
                null,
                Instant.now().toString()
        );
    }
}
