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
 * Exception thrown when a co-activation tracker operation fails.
 *
 * <p>Covers pair recording, STDP updates, predictive strength
 * computation, and association lookup ({@code SPE-310-009}).</p>
 *
 * @see ErrorCode#GRAPH_COACTIVATION_FAILED
 */
public class SpectorCoActivationException extends SpectorGraphException {

    private final String operation;

    public SpectorCoActivationException(String operation) {
        super(ErrorCode.GRAPH_COACTIVATION_FAILED, operation);
        this.operation = operation;
    }

    public SpectorCoActivationException(String operation, Throwable cause) {
        super(ErrorCode.GRAPH_COACTIVATION_FAILED, cause, operation);
        this.operation = operation;
    }

    /** Returns the co-activation operation that failed. */
    public String getOperation() {
        return operation;
    }
}
