package com.spectrayan.spector.memory.error;

import com.spectrayan.spector.commons.error.*;

/**
 * Exception thrown when the cognitive recall pipeline or memory identification fails.
 *
 * @see SpectorMemoryException
 */
public class SpectorMemoryRecallException extends SpectorMemoryException {

    private final String details;

    public SpectorMemoryRecallException(String details) {
        super(ErrorCode.MEMORY_RECALL_FAILED, details);
        this.details = details;
    }

    public SpectorMemoryRecallException(String details, Throwable cause) {
        super(ErrorCode.MEMORY_RECALL_FAILED, cause, details);
        this.details = details;
    }

    public SpectorMemoryRecallException(ErrorCode errorCode, String details) {
        super(errorCode, details);
        this.details = details;
    }

    public SpectorMemoryRecallException(ErrorCode errorCode, Throwable cause, String details) {
        super(errorCode, cause, details);
        this.details = details;
    }

    /** Returns details of the recall pipeline failure. */
    public String getDetails() {
        return details;
    }
}
