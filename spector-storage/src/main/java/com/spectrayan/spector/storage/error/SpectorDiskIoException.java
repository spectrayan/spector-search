package com.spectrayan.spector.storage.error;

import com.spectrayan.spector.commons.error.*;

/**
 * Exception thrown when a disk read, write, or sync operation fails.
 *
 * @see SpectorStorageException
 */
public class SpectorDiskIoException extends SpectorStorageException {

    private final String details;

    public SpectorDiskIoException(String details) {
        super(ErrorCode.DISK_IO_FAILED, details);
        this.details = details;
    }

    public SpectorDiskIoException(String details, Throwable cause) {
        super(ErrorCode.DISK_IO_FAILED, cause, details);
        this.details = details;
    }

    /** Returns the details of the disk I/O failure. */
    public String getDetails() {
        return details;
    }
}
