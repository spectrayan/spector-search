package com.spectrayan.spector.index.error;

import com.spectrayan.spector.commons.error.*;

/**
 * Exception thrown when an index integrity check detects corruption or
 * violation of structural invariants.
 *
 * @see SpectorIndexException
 */
public class SpectorIndexIntegrityException extends SpectorIndexException {

    public SpectorIndexIntegrityException(String message) {
        super(ErrorCode.HNSW_GRAPH_CORRUPTED, message);
    }

    public SpectorIndexIntegrityException(String message, Throwable cause) {
        super(ErrorCode.HNSW_GRAPH_CORRUPTED, cause, message);
    }
}
