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
 * Exception thrown when a temporal chain operation fails.
 *
 * <p>Covers link, followForward, followBackward,
 * and session-boundary operations ({@code SPE-310-007}).</p>
 *
 * @see ErrorCode#GRAPH_TEMPORAL_FAILED
 */
public class SpectorTemporalChainException extends SpectorGraphException {

    private final String operation;

    public SpectorTemporalChainException(String operation) {
        super(ErrorCode.GRAPH_TEMPORAL_FAILED, operation);
        this.operation = operation;
    }

    public SpectorTemporalChainException(String operation, Throwable cause) {
        super(ErrorCode.GRAPH_TEMPORAL_FAILED, cause, operation);
        this.operation = operation;
    }

    /** Returns the temporal chain operation that failed. */
    public String getOperation() {
        return operation;
    }
}
