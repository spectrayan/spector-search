package com.spectrayan.spector.storage.error;

import com.spectrayan.spector.commons.error.*;

/**
 * Exception thrown when the vector store has reached its capacity limits.
 *
 * @see SpectorStorageException
 */
public class SpectorStoreFullException extends SpectorStorageException {

    private final int maxCapacity;

    public SpectorStoreFullException(int maxCapacity) {
        super(ErrorCode.STORE_FULL, maxCapacity);
        this.maxCapacity = maxCapacity;
    }

    public SpectorStoreFullException(int maxCapacity, Throwable cause) {
        super(ErrorCode.STORE_FULL, cause, maxCapacity);
        this.maxCapacity = maxCapacity;
    }

    /** Returns the maximum capacity of the vector store. */
    public int getMaxCapacity() {
        return maxCapacity;
    }
}
