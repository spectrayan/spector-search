package com.spectrayan.spector.index.error;

import com.spectrayan.spector.commons.error.*;

/**
 * Exception thrown when a write operation is attempted on a read-only index.
 *
 * @see SpectorIndexException
 */
public class SpectorIndexReadOnlyException extends SpectorIndexException {

    public SpectorIndexReadOnlyException() {
        super(ErrorCode.INDEX_READ_ONLY);
    }

    public SpectorIndexReadOnlyException(Throwable cause) {
        super(ErrorCode.INDEX_READ_ONLY, cause);
    }
}
