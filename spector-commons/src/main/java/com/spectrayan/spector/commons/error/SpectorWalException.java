package com.spectrayan.spector.commons.error;

/**
 * Exception thrown when a write-ahead log (WAL) write or replay operation fails.
 *
 * @see SpectorStorageException
 */
public class SpectorWalException extends SpectorStorageException {

    private final String details;

    public SpectorWalException(String details) {
        super(ErrorCode.WAL_WRITE_FAILED, details);
        this.details = details;
    }

    public SpectorWalException(String details, Throwable cause) {
        super(ErrorCode.WAL_WRITE_FAILED, cause, details);
        this.details = details;
    }

    public SpectorWalException(ErrorCode errorCode, String details) {
        super(errorCode, details);
        this.details = details;
    }

    public SpectorWalException(ErrorCode errorCode, Throwable cause, String details) {
        super(errorCode, cause, details);
        this.details = details;
    }

    /** Returns the details of the WAL failure. */
    public String getDetails() {
        return details;
    }
}
