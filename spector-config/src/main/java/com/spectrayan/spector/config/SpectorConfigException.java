package com.spectrayan.spector.config;

import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Thrown when Spector configuration loading or validation fails.
 *
 * @deprecated Use {@link com.spectrayan.spector.commons.error.SpectorConfigException} instead.
 *             This class is retained for backward compatibility and will be removed in v0.2.0.
 */
@Deprecated(since = "0.1.0", forRemoval = true)
public class SpectorConfigException extends RuntimeException {

    private final ErrorCode errorCode;

    /** @deprecated Use the ErrorCode constructor instead. */
    @Deprecated
    public SpectorConfigException(String message) {
        super(message);
        this.errorCode = null;
    }

    /** @deprecated Use the ErrorCode constructor instead. */
    @Deprecated
    public SpectorConfigException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = null;
    }

    /**
     * Creates a config exception with a structured error code.
     *
     * @param errorCode the config error code (SPE-110-xxx)
     * @param args      values for message template placeholders
     */
    public SpectorConfigException(ErrorCode errorCode, Object... args) {
        super(errorCode.format(args));
        this.errorCode = errorCode;
    }

    /**
     * Creates a config exception with a structured error code and cause.
     *
     * @param errorCode the config error code
     * @param cause     the underlying exception
     * @param args      values for message template placeholders
     */
    public SpectorConfigException(ErrorCode errorCode, Throwable cause, Object... args) {
        super(errorCode.format(args), cause);
        this.errorCode = errorCode;
    }

    /** Returns the error code, or {@code null} for legacy exceptions. */
    public ErrorCode errorCode() {
        return errorCode;
    }
}
