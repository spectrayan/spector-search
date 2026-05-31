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
 * Exception thrown when a Hebbian graph operation fails.
 *
 * <p>Covers edge strengthening, spreading activation, decay,
 * and session boundary detection ({@code SPE-310-006}).</p>
 *
 * @see ErrorCode#GRAPH_HEBBIAN_FAILED
 */
public class SpectorHebbianException extends SpectorGraphException {

    private final String operation;

    public SpectorHebbianException(String operation) {
        super(ErrorCode.GRAPH_HEBBIAN_FAILED, operation);
        this.operation = operation;
    }

    public SpectorHebbianException(String operation, Throwable cause) {
        super(ErrorCode.GRAPH_HEBBIAN_FAILED, cause, operation);
        this.operation = operation;
    }

    /** Returns the Hebbian operation that failed. */
    public String getOperation() {
        return operation;
    }
}
