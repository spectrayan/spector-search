package com.spectrayan.spector.storage.error;

import com.spectrayan.spector.commons.error.*;

/**
 * Exception thrown when an operation is attempted on a closed memory segment or store.
 *
 * @see SpectorStorageException
 */
public class SpectorSegmentClosedException extends SpectorStorageException {

    public SpectorSegmentClosedException() {
        super(ErrorCode.SEGMENT_CLOSED);
    }

    public SpectorSegmentClosedException(Throwable cause) {
        super(ErrorCode.SEGMENT_CLOSED, cause);
    }
}
