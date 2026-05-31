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

import java.util.List;

/**
 * Service Provider Interface for entity extraction from memory text.
 *
 * <p>Implementations analyze text to identify named entities and their relationships.
 * This follows the same pluggable pattern as
 * {@link com.spectrayan.spector.memory.pipeline.TagExtractor} and
 * {@link com.spectrayan.spector.embed.EmbeddingProvider}.</p>
 *
 * <h3>Implementations</h3>
 * <ul>
 *   <li>{@link LlmEntityExtractor} — LLM-powered extraction via TextGenerationProvider</li>
 *   <li>{@link NoOpEntityExtractor} — returns empty list (when extraction is disabled)</li>
 * </ul>
 *
 * @see ExtractedEntity
 * @see EntityGraph
 */
public interface EntityExtractor {

    /**
     * Extracts entities and their relationships from text.
     *
     * @param id   the memory identifier
     * @param text the memory content to analyze
     * @return list of extracted entities with typed relations
     */
    List<ExtractedEntity> extract(String id, String text);

    /**
     * Returns whether this extractor is available and ready.
     *
     * @return true if the extractor can process requests
     */
    default boolean isAvailable() {
        return true;
    }
}
