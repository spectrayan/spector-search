package com.spectrayan.spector.cluster;

import java.time.Instant;

/**
 * Represents a write operation that needs to be replicated across replicas.
 *
 * @param sequenceNumber monotonically increasing sequence number for ordering
 * @param documentId     the document affected by the write
 * @param operationType  the type of operation (INSERT, UPDATE, DELETE)
 * @param payload        the serialized data payload (null for DELETE)
 * @param timestamp      when the write was acknowledged on the primary
 */
public record WriteOperation(
        long sequenceNumber,
        String documentId,
        OperationType operationType,
        byte[] payload,
        Instant timestamp
) {
    /**
     * Types of write operations that can be replicated.
     */
    public enum OperationType {
        INSERT,
        UPDATE,
        DELETE
    }
}
