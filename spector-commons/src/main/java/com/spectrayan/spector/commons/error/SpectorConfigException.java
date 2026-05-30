package com.spectrayan.spector.commons.error;

/**
 * Exception for configuration loading, parsing, and validation errors ({@code SPE-110-xxx}).
 *
 * <p>Thrown when a configuration file cannot be found, parsed, or contains invalid values.
 * Replaces the previous unstructured {@code SpectorConfigException} from spector-config.</p>
 *
 * @see ErrorCode#CONFIG_FILE_NOT_FOUND
 * @see ErrorCode#CONFIG_PARSE_FAILED
 */
public class SpectorConfigException extends SpectorException {

    public SpectorConfigException(ErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }

    public SpectorConfigException(ErrorCode errorCode, String preformattedMessage, boolean isPreformatted) {
        super(errorCode, preformattedMessage, isPreformatted);
    }

    public SpectorConfigException(ErrorCode errorCode, Throwable cause, Object... args) {
        super(errorCode, cause, args);
    }
}
