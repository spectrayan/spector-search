package com.spectrayan.spector.storage.error;

import com.spectrayan.spector.commons.error.*;

/**
 * Exception thrown when a memory-mapped file creation or mapping fails.
 *
 * @see SpectorStorageException
 */
public class SpectorMmapException extends SpectorStorageException {

    private final String path;
    private final String details;

    public SpectorMmapException(String path, String details) {
        super(ErrorCode.MMAP_FAILED, path + ": " + details);
        this.path = path;
        this.details = details;
    }

    public SpectorMmapException(String path, String details, Throwable cause) {
        super(ErrorCode.MMAP_FAILED, cause, path + ": " + details);
        this.path = path;
        this.details = details;
    }

    /** Returns the path of the file that failed to map. */
    public String getPath() {
        return path;
    }

    /** Returns details of the mapping failure. */
    public String getDetails() {
        return details;
    }
}
