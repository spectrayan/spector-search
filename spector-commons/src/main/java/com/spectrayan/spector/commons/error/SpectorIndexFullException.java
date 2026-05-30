package com.spectrayan.spector.commons.error;

/**
 * Exception thrown when the index has reached its maximum document capacity.
 *
 * @see SpectorIndexException
 */
public class SpectorIndexFullException extends SpectorIndexException {

    private final int maxCapacity;

    public SpectorIndexFullException(int maxCapacity) {
        super(ErrorCode.INDEX_FULL, maxCapacity);
        this.maxCapacity = maxCapacity;
    }

    public SpectorIndexFullException(int maxCapacity, Throwable cause) {
        super(ErrorCode.INDEX_FULL, cause, maxCapacity);
        this.maxCapacity = maxCapacity;
    }

    /** Returns the maximum capacity of the index. */
    public int getMaxCapacity() {
        return maxCapacity;
    }
}
