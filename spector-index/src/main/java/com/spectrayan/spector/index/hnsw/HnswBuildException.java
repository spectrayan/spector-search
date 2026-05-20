package com.spectrayan.spector.index;

/**
 * Exception thrown when parallel HNSW index construction fails.
 *
 * <p>This indicates that a virtual thread encountered an unrecoverable error
 * during parallel construction. The partial graph is discarded.</p>
 */
public class HnswBuildException extends RuntimeException {

    /**
     * Creates a new build exception.
     *
     * @param message description of the failure
     */
    public HnswBuildException(String message) {
        super(message);
    }

    /**
     * Creates a new build exception with a cause.
     *
     * @param message description of the failure
     * @param cause   the underlying cause
     */
    public HnswBuildException(String message, Throwable cause) {
        super(message, cause);
    }
}
