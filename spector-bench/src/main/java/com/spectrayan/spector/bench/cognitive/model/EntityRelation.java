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
package com.spectrayan.spector.bench.cognitive.model;

import java.util.List;

/**
 * A typed directed relation between two entities, backed by one or more corpus memories.
 *
 * <p>Maps directly to one line in {@code entities.jsonl}. Each relation captures
 * a semantic link between a source entity and a target entity (e.g., "Alice WORKS_ON
 * payment service") and records which corpus memories establish that relation.</p>
 *
 * <h3>Field Constraints</h3>
 * <ul>
 *   <li>{@code fromEntity} — the source entity of the relation</li>
 *   <li>{@code toEntity} — the target entity of the relation</li>
 *   <li>{@code relationType} — one of the 21 supported relation types:
 *       MANAGES, REPORTS_TO, KNOWS, ASSIGNED_TO, AUTHORED, WORKS_ON, CREATED_BY,
 *       OWNS, IMPLEMENTS, PART_OF, CONTAINS, DEPENDS_ON, USES, CAUSES, BLOCKS,
 *       SUPERSEDES, PRECEDES, FOLLOWS, LOCATED_AT, RELATED_TO, OTHER</li>
 *   <li>{@code sourceMemoryIds} — 1 to 50 corpus IDs that establish this relation;
 *       each must reference an existing ID in {@code corpus.jsonl}</li>
 * </ul>
 *
 * @param fromEntity      the source entity of the directed relation
 * @param toEntity        the target entity of the directed relation
 * @param relationType    the semantic type of the relation (matches {@code RelationType} enum name)
 * @param sourceMemoryIds corpus memory IDs that evidence this relation (1–50 entries)
 */
public record EntityRelation(
        EntityMention fromEntity,
        EntityMention toEntity,
        String relationType,
        List<String> sourceMemoryIds
) {}
