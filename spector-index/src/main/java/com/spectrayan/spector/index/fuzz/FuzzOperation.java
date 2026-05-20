package com.spectrayan.spector.index.fuzz;

/**
 * Represents a single operation in a fuzz sequence.
 *
 * @param type       the kind of operation
 * @param vector     the vector data used (may be null for DELETE)
 * @param vectorId   the vector/document ID
 * @param indexType  which index this operation targets
 */
public record FuzzOperation(
        OperationType type,
        float[] vector,
        String vectorId,
        IndexType indexType
) {
    public enum OperationType {
        INSERT,
        DELETE,
        SEARCH
    }
}
