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
 * Exception thrown when graph decay or pruning fails during consolidation.
 *
 * <p>Covers Hebbian edge decay, temporal chain pruning,
 * and entity graph homeostasis ({@code SPE-310-011}).</p>
 *
 * @see ErrorCode#GRAPH_DECAY_FAILED
 */
public class SpectorGraphDecayException extends SpectorGraphException {

    private final String details;

    public SpectorGraphDecayException(String details) {
        super(ErrorCode.GRAPH_DECAY_FAILED, details);
        this.details = details;
    }

    public SpectorGraphDecayException(String details, Throwable cause) {
        super(ErrorCode.GRAPH_DECAY_FAILED, cause, details);
        this.details = details;
    }

    /** Returns details of the decay failure. */
    public String getDetails() {
        return details;
    }
}
