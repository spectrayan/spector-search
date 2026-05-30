package com.spectrayan.spector.node.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;

import com.spectrayan.spector.commons.error.SpectorApiException;
import com.spectrayan.spector.commons.error.SpectorException;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.node.api.dto.ProblemDetail;

/**
 * Centralized Armeria exception handler for all Spector REST endpoints.
 *
 * <p>Produces RFC 9457 {@code application/problem+json} responses.</p>
 *
 * <h3>Exception Mapping</h3>
 * <ul>
 *   <li>{@link SpectorApiException} → HTTP status from exception</li>
 *   <li>{@link SpectorValidationException} → 400 Bad Request</li>
 *   <li>{@link SpectorException} → 500 Internal Server Error</li>
 *   <li>{@link IllegalArgumentException} → 400 Bad Request (fallback)</li>
 *   <li>All others → 500 Internal Server Error</li>
 * </ul>
 *
 * <h3>Logging Policy</h3>
 * <ul>
 *   <li>4xx errors → WARN level, error code + message only (no stack trace)</li>
 *   <li>5xx errors → ERROR level, error code + message + full stack trace</li>
 * </ul>
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9457">RFC 9457</a>
 */
public class ApiExceptionHandler implements ExceptionHandlerFunction {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** RFC 9457 content type. */
    private static final MediaType PROBLEM_JSON = MediaType.parse("application/problem+json");

    @Override
    public HttpResponse handleException(ServiceRequestContext ctx, HttpRequest req, Throwable cause) {
        // ── SpectorApiException — carries its own HTTP status ──
        if (cause instanceof SpectorApiException e) {
            int status = e.httpStatus();
            logByStatus(status, e.codeId(), e.getMessage(), e);
            return problemResponse(status, ProblemDetail.fromException(e, status, ctx.path()));
        }

        // ── SpectorValidationException → 400 ──
        if (cause instanceof SpectorValidationException e) {
            log.warn("{}: {}", e.codeId(), e.getMessage());
            return problemResponse(400, ProblemDetail.fromException(e, 400, ctx.path()));
        }

        // ── Any other SpectorException → 500 ──
        if (cause instanceof SpectorException e) {
            log.error("{}: {}", e.codeId(), e.getMessage(), e);
            return problemResponse(500, ProblemDetail.fromException(e, 500, ctx.path()));
        }

        // ── IllegalArgumentException → 400 (framework/library fallback) ──
        if (cause instanceof IllegalArgumentException e) {
            log.warn("Bad request: {}", e.getMessage());
            return problemResponse(400,
                    ProblemDetail.of(400, "Bad Request", e.getMessage(), ctx.path()));
        }

        // ── Unexpected — 500, full stack trace ──
        log.error("Unexpected error on {}", ctx.path(), cause);
        return problemResponse(500,
                ProblemDetail.of(500, "Internal Server Error",
                        "An unexpected error occurred", ctx.path()));
    }

    /**
     * Builds an {@code application/problem+json} HTTP response.
     */
    private static HttpResponse problemResponse(int status, ProblemDetail problem) {
        return HttpResponse.ofJson(HttpStatus.valueOf(status), PROBLEM_JSON, problem);
    }

    /**
     * Logs at WARN for 4xx, ERROR (with stack trace) for 5xx.
     */
    private static void logByStatus(int status, String codeId, String message, Throwable cause) {
        if (status >= 500) {
            log.error("{}: {}", codeId, message, cause);
        } else {
            log.warn("{}: {}", codeId, message);
        }
    }
}
