package com.spectrayan.spector.commons.error;

/**
 * Exception thrown when a configuration file cannot be found at the specified path.
 *
 * @see SpectorConfigException
 */
public class SpectorConfigNotFoundException extends SpectorConfigException {

    private final String path;

    public SpectorConfigNotFoundException(String path) {
        super(ErrorCode.CONFIG_FILE_NOT_FOUND, path);
        this.path = path;
    }

    public SpectorConfigNotFoundException(String path, Throwable cause) {
        super(ErrorCode.CONFIG_FILE_NOT_FOUND, cause, path);
        this.path = path;
    }

    /** Returns the path to the configuration file that was not found. */
    public String getPath() {
        return path;
    }
}
