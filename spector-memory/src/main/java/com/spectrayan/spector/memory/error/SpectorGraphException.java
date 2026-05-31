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
 * Exception thrown when a cognitive graph operation fails.
 *
 * <p>Covers Hebbian graph, temporal chain, entity graph,
 * and co-activation tracker operations ({@code SPE-310-006} through
 * {@code SPE-310-011}).</p>
 *
 * @see ErrorCode#GRAPH_HEBBIAN_FAILED
 * @see ErrorCode#GRAPH_TEMPORAL_FAILED
 * @see ErrorCode#GRAPH_ENTITY_FAILED
 * @see ErrorCode#GRAPH_COACTIVATION_FAILED
 * @see ErrorCode#GRAPH_PERSISTENCE_FAILED
 * @see ErrorCode#GRAPH_DECAY_FAILED
 */
public class SpectorGraphException extends SpectorMemoryException {

    public SpectorGraphException(ErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }

    public SpectorGraphException(ErrorCode errorCode, Throwable cause, Object... args) {
        super(errorCode, cause, args);
    }
}
