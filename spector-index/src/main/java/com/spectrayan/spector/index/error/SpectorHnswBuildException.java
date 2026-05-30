package com.spectrayan.spector.index.error;

import com.spectrayan.spector.commons.error.*;

/**
 * Exception thrown when parallel HNSW index construction fails.
 *
 * <p>This indicates that a virtual thread encountered an unrecoverable error
 * during parallel construction. The partial graph is discarded.</p>
 *
 * @see SpectorIndexException
 */
public class SpectorHnswBuildException extends SpectorIndexException {

    /**
     * Creates a new build exception.
     *
     * @param message description of the failure
     */
    public SpectorHnswBuildException(String message) {
        super(ErrorCode.HNSW_BUILD_FAILED, message);
    }

    /**
     * Creates a new build exception with a cause.
     *
     * @param message description of the failure
     * @param cause   the underlying cause
     */
    public SpectorHnswBuildException(String message, Throwable cause) {
        super(ErrorCode.HNSW_BUILD_FAILED, cause, message);
    }
}
