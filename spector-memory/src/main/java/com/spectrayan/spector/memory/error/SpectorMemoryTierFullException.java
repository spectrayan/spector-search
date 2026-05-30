package com.spectrayan.spector.memory.error;

import com.spectrayan.spector.commons.error.*;

/**
 * Exception thrown when a cognitive memory tier has reached its capacity limits.
 *
 * @see SpectorMemoryException
 */
public class SpectorMemoryTierFullException extends SpectorMemoryException {

    private final String tier;
    private final int capacity;

    public SpectorMemoryTierFullException(String tier, int capacity) {
        super(ErrorCode.MEMORY_TIER_FULL, tier, capacity);
        this.tier = tier;
        this.capacity = capacity;
    }

    public SpectorMemoryTierFullException(String tier, int capacity, Throwable cause) {
        super(ErrorCode.MEMORY_TIER_FULL, cause, tier, capacity);
        this.tier = tier;
        this.capacity = capacity;
    }

    /** Returns the cognitive memory tier that reached capacity. */
    public String getTier() {
        return tier;
    }

    /** Returns the capacity limit of the tier. */
    public int getCapacity() {
        return capacity;
    }
}
