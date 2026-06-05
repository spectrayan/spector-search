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

/**
 * A typed entity reference within a corpus memory.
 *
 * <p>Represents an entity mentioned in the memory text, classified by
 * {@link com.spectrayan.spector.memory.graph.EntityType EntityType} name.</p>
 *
 * @param name the entity name as it appears in or is extracted from the memory text
 * @param type the entity type (matches {@code EntityType} enum name, e.g., "PERSON", "SOFTWARE")
 */
public record EntityMention(String name, String type) {}
