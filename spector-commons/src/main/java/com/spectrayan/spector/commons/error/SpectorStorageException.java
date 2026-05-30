package com.spectrayan.spector.commons.error;

/**
 * Exception for vector store, memory-mapped I/O, off-heap, and disk errors ({@code SPE-210-xxx}).
 *
 * <p>Covers memory segment lifecycle, mmap failures, store capacity, disk I/O,
 * WAL operations, and file format issues.</p>
 *
 * @see ErrorCode#SEGMENT_CLOSED
 * @see ErrorCode#MMAP_FAILED
 * @see ErrorCode#WAL_WRITE_FAILED
 */
public class SpectorStorageException extends SpectorException {

    public SpectorStorageException(ErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }

    public SpectorStorageException(ErrorCode errorCode, String preformattedMessage, boolean isPreformatted) {
        super(errorCode, preformattedMessage, isPreformatted);
    }

    public SpectorStorageException(ErrorCode errorCode, Throwable cause, Object... args) {
        super(errorCode, cause, args);
    }
}
