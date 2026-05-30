package com.spectrayan.spector.commons.error;

/**
 * Exception for server-side transport errors ({@code SPE-500-xxx}).
 *
 * <p>Base class for REST API, gRPC, and MCP server errors. The subclass
 * {@link SpectorApiException} adds HTTP status code mapping.</p>
 *
 * @see ErrorCode#API_BAD_REQUEST
 * @see ErrorCode#MCP_TOOL_FAILED
 * @see SpectorApiException
 */
public class SpectorServerException extends SpectorException {

    public SpectorServerException(ErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }

    public SpectorServerException(ErrorCode errorCode, Throwable cause, Object... args) {
        super(errorCode, cause, args);
    }
}
