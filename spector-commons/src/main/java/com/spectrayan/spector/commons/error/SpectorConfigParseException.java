package com.spectrayan.spector.commons.error;

/**
 * Exception thrown when configuration parsing fails.
 *
 * @see SpectorConfigException
 */
public class SpectorConfigParseException extends SpectorConfigException {

    private final String details;

    public SpectorConfigParseException(String details) {
        super(ErrorCode.CONFIG_PARSE_FAILED, details);
        this.details = details;
    }

    public SpectorConfigParseException(String details, Throwable cause) {
        super(ErrorCode.CONFIG_PARSE_FAILED, cause, details);
        this.details = details;
    }

    /** Returns the details of the parsing failure. */
    public String getDetails() {
        return details;
    }
}
