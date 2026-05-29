package com.spectrayan.spector.node.exception;

import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;

import com.spectrayan.spector.node.api.dto.ErrorResponse;

/**
 * Centralized Armeria exception handler for all Spector REST endpoints.
 *
 * <p>Catches exceptions thrown by {@code @Get/@Post/@Delete} handler methods
 * and converts them into structured JSON error responses. Applied via
 * {@code @ExceptionHandler(ApiExceptionHandler.class)} on each endpoint class.</p>
 *
 * <h3>Exception Mapping</h3>
 * <ul>
 *   <li>{@link SpectorApiException} → status from exception</li>
 *   <li>{@link ValidationException} → 400</li>
 *   <li>{@link IllegalArgumentException} → 400 (Armeria built-in)</li>
 *   <li>All others → 500</li>
 * </ul>
 */
public class ApiExceptionHandler implements ExceptionHandlerFunction {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @Override
    public HttpResponse handleException(ServiceRequestContext ctx, HttpRequest req, Throwable cause) {
        if (cause instanceof SpectorApiException e) {
            if (e.statusCode() >= 500) {
                log.error("API error [{}]: {}", e.statusCode(), e.getMessage(), e);
            } else {
                log.warn("API error [{}]: {}", e.statusCode(), e.getMessage());
            }
            return HttpResponse.ofJson(
                    HttpStatus.valueOf(e.statusCode()),
                    ErrorResponse.of(e.statusCode(), e.getMessage(), ctx.path()));
        }

        if (cause instanceof IllegalArgumentException e) {
            log.warn("Bad request: {}", e.getMessage());
            return HttpResponse.ofJson(
                    HttpStatus.BAD_REQUEST,
                    ErrorResponse.of(400, e.getMessage(), ctx.path()));
        }

        // Unexpected — log full stack trace
        log.error("Unexpected error on {}", ctx.path(), cause);
        return HttpResponse.ofJson(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorResponse.of(500, "Internal server error", ctx.path()));
    }
}
