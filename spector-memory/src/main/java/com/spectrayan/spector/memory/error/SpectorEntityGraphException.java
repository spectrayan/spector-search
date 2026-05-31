/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Business Source License 1.1 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://github.com/spectrayan/spector/blob/main/spector-memory/LICENSE
 *
 * Change Date: May 27, 2030
 * Change License: Apache License, Version 2.0
 */
package com.spectrayan.spector.memory.error;

import com.spectrayan.spector.commons.error.*;

/**
 * Exception thrown when an entity graph operation fails.
 *
 * <p>Covers entity addition, relation linking, entity lookup,
 * memory linking, and graph traversal ({@code SPE-310-008}).</p>
 *
 * @see ErrorCode#GRAPH_ENTITY_FAILED
 */
public class SpectorEntityGraphException extends SpectorGraphException {

    private final String operation;

    public SpectorEntityGraphException(String operation) {
        super(ErrorCode.GRAPH_ENTITY_FAILED, operation);
        this.operation = operation;
    }

    public SpectorEntityGraphException(String operation, Throwable cause) {
        super(ErrorCode.GRAPH_ENTITY_FAILED, cause, operation);
        this.operation = operation;
    }

    /** Returns the entity graph operation that failed. */
    public String getOperation() {
        return operation;
    }
}
