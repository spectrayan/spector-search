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
 * Relation types for typed edges in the entity-relationship graph.
 *
 * <p>These types enable filtered traversal — e.g., "follow only MANAGES edges"
 * to answer "who manages the project that Alice works on?"</p>
 */
public enum RelationType {
    MANAGES,
    AUTHORED,
    ATTENDED,
    PART_OF,
    RELATED_TO,
    CAUSES,
    DEPENDS_ON,
    USES,
    CREATED,
    MENTIONS,
    WORKS_ON,
    LOCATED_IN,
    OTHER
}
