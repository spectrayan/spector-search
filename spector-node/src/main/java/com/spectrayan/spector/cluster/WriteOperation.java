/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
