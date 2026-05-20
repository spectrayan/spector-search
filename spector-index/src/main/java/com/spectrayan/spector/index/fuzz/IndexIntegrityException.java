package com.spectrayan.spector.index.fuzz;

/**
 * Exception thrown when an index integrity check detects corruption or
 * violation of structural invariants.
 */
public class IndexIntegrityException extends RuntimeException {

    public IndexIntegrityException(String message) {
        super(message);
    }

    public IndexIntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
