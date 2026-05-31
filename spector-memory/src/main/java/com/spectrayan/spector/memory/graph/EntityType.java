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
package com.spectrayan.spector.memory.graph;

/**
 * Entity types for the knowledge graph.
 *
 * <p>Entities extracted from memory text are classified into these categories
 * to enable typed traversal and filtering in the entity-relationship graph.</p>
 */
public enum EntityType {
    PERSON,
    ORGANIZATION,
    PROJECT,
    CONCEPT,
    EVENT,
    LOCATION,
    TOOL,
    OTHER
}
