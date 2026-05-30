package com.spectrayan.spector.config.error;

import com.spectrayan.spector.commons.error.*;

/**
 * Exception thrown when a configuration value is invalid or a required key is missing.
 *
 * @see SpectorConfigException
 */
public class SpectorConfigValueException extends SpectorConfigException {

    private final String key;
    private final Object value;

    public SpectorConfigValueException(String key, Object value) {
        super(ErrorCode.CONFIG_VALUE_INVALID, key, value);
        this.key = key;
        this.value = value;
    }

    public SpectorConfigValueException(String key, Object value, Throwable cause) {
        super(ErrorCode.CONFIG_VALUE_INVALID, cause, key, value);
        this.key = key;
        this.value = value;
    }

    public SpectorConfigValueException(ErrorCode errorCode, String key, Object value) {
        super(errorCode, key, value);
        this.key = key;
        this.value = value;
    }

    public SpectorConfigValueException(ErrorCode errorCode, Throwable cause, String key, Object value) {
        super(errorCode, cause, key, value);
        this.key = key;
        this.value = value;
    }

    /** Returns the configuration key that has an invalid or missing value. */
    public String getKey() {
        return key;
    }

    /** Returns the invalid value, or null if the key was missing. */
    public Object getValue() {
        return value;
    }
}
