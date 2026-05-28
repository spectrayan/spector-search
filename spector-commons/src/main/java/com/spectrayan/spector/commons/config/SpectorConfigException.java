package com.spectrayan.spector.commons.config;

/**
 * Thrown when Spector configuration loading or validation fails.
 */
public class SpectorConfigException extends RuntimeException {

    public SpectorConfigException(String message) {
        super(message);
    }

    public SpectorConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
