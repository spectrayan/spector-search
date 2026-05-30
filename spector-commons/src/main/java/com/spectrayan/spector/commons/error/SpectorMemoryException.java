package com.spectrayan.spector.commons.error;

/**
 * Exception for cognitive memory tier errors ({@code SPE-310-xxx}).
 *
 * <p>Covers memory tier capacity, recall pipeline failures, consolidation errors,
 * memory ID lookups, and WAL corruption in the memory subsystem.</p>
 *
 * @see ErrorCode#MEMORY_TIER_FULL
 * @see ErrorCode#MEMORY_RECALL_FAILED
 */
public class SpectorMemoryException extends SpectorException {

    public SpectorMemoryException(ErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }

    public SpectorMemoryException(ErrorCode errorCode, Throwable cause, Object... args) {
        super(errorCode, cause, args);
    }
}
