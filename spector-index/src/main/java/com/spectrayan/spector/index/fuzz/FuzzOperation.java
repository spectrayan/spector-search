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
