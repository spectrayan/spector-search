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
 * Exception thrown when graph persistence (save/load) fails.
 *
 * <p>Covers Hebbian, temporal, entity, and co-activation
 * graph file I/O ({@code SPE-310-010}).</p>
 *
 * @see ErrorCode#GRAPH_PERSISTENCE_FAILED
 */
public class SpectorGraphPersistenceException extends SpectorGraphException {

    private final String graphType;
    private final String path;

    public SpectorGraphPersistenceException(String graphType, Object path) {
        super(ErrorCode.GRAPH_PERSISTENCE_FAILED, graphType, path);
        this.graphType = graphType;
        this.path = String.valueOf(path);
    }

    public SpectorGraphPersistenceException(String graphType, Object path, Throwable cause) {
        super(ErrorCode.GRAPH_PERSISTENCE_FAILED, cause, graphType, path);
        this.graphType = graphType;
        this.path = String.valueOf(path);
    }

    /** Returns the type of graph that failed to persist. */
    public String getGraphType() {
        return graphType;
    }

    /** Returns the file path involved. */
    public String getPath() {
        return path;
    }
}
