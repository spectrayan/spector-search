package com.spectrayan.spector.commons.error;

/**
 * Exception thrown when the memory consolidation process fails.
 *
 * @see SpectorMemoryException
 */
public class SpectorMemoryConsolidationException extends SpectorMemoryException {

    private final String details;

    public SpectorMemoryConsolidationException(String details) {
        super(ErrorCode.MEMORY_CONSOLIDATION_FAILED, details);
        this.details = details;
    }

    public SpectorMemoryConsolidationException(String details, Throwable cause) {
        super(ErrorCode.MEMORY_CONSOLIDATION_FAILED, cause, details);
        this.details = details;
    }

    /** Returns details of the memory consolidation failure. */
    public String getDetails() {
        return details;
    }
}
