package com.spectrayan.spector.node.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;

import com.spectrayan.spector.commons.error.SpectorApiException;
import com.spectrayan.spector.commons.error.SpectorException;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.node.api.dto.ErrorResponse;

/**
 * Centralized Armeria exception handler for all Spector REST endpoints.
 *
 * <p>Catches exceptions thrown by {@code @Get/@Post/@Delete} handler methods
 * and converts them into structured JSON error responses with error codes.</p>
 *
 * <h3>Exception Mapping</h3>
 * <ul>
 *   <li>{@link SpectorApiException} → HTTP status from exception, structured error code</li>
 *   <li>{@link SpectorValidationException} → 400 Bad Request, structured error code</li>
 *   <li>{@link SpectorException} → 500 Internal, structured error code</li>
 *   <li>{@link LegacySpectorApiException} → status from exception (deprecated)</li>
 *   <li>{@link IllegalArgumentException} → 400 (legacy fallback)</li>
 *   <li>All others → 500</li>
 * </ul>
 *
 * <h3>Logging Policy</h3>
 * <ul>
 *   <li>4xx errors → WARN level, code + message only (no stack trace)</li>
 *   <li>5xx errors → ERROR level, code + message + full stack trace</li>
 * </ul>
 */
public class ApiExceptionHandler implements ExceptionHandlerFunction {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @Override
    public HttpResponse handleException(ServiceRequestContext ctx, HttpRequest req, Throwable cause) {
        // ── New framework: SpectorApiException with HTTP status + error code ──
        if (cause instanceof SpectorApiException e) {
            int status = e.httpStatus();
            if (status >= 500) {
                log.error("{}: {}", e.codeId(), e.getMessage(), e);
            } else {
                log.warn("{}: {}", e.codeId(), e.getMessage());
            }
            return HttpResponse.ofJson(
                    HttpStatus.valueOf(status),
                    ErrorResponse.of(e.codeId(), e.category().displayName(),
                            status, e.getMessage(), ctx.path()));
        }

        // ── New framework: SpectorValidationException → 400 ──
        if (cause instanceof SpectorValidationException e) {
            log.warn("{}: {}", e.codeId(), e.getMessage());
            return HttpResponse.ofJson(
                    HttpStatus.BAD_REQUEST,
                    ErrorResponse.of(e.codeId(), e.category().displayName(),
                            400, e.getMessage(), ctx.path()));
        }

        // ── New framework: any other SpectorException → 500 ──
        if (cause instanceof SpectorException e) {
            log.error("{}: {}", e.codeId(), e.getMessage(), e);
            return HttpResponse.ofJson(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    ErrorResponse.of(e.codeId(), e.category().displayName(),
                            500, e.getMessage(), ctx.path()));
        }

        // ── Legacy: old SpectorApiException (deprecated) ──
        if (cause instanceof LegacySpectorApiException e) {
            if (e.statusCode() >= 500) {
                log.error("API error [{}]: {}", e.statusCode(), e.getMessage(), e);
            } else {
                log.warn("API error [{}]: {}", e.statusCode(), e.getMessage());
            }
            return HttpResponse.ofJson(
                    HttpStatus.valueOf(e.statusCode()),
                    ErrorResponse.of(e.statusCode(), e.getMessage(), ctx.path()));
        }

        // ── Legacy: raw IllegalArgumentException → 400 ──
        if (cause instanceof IllegalArgumentException e) {
            log.warn("Bad request: {}", e.getMessage());
            return HttpResponse.ofJson(
                    HttpStatus.BAD_REQUEST,
                    ErrorResponse.of(400, e.getMessage(), ctx.path()));
        }

        // ── Unexpected — log full stack trace ──
        log.error("Unexpected error on {}", ctx.path(), cause);
        return HttpResponse.ofJson(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorResponse.of(500, "Internal server error", ctx.path()));
    }
}
