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

import com.spectrayan.spector.commons.ResourceUtils;
import com.spectrayan.spector.embed.TextGenerationProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM-powered entity extractor using a {@link TextGenerationProvider}.
 *
 * <h3>How It Works</h3>
 * <p>Sends a structured prompt to the LLM asking it to identify entities
 * and their relationships from the text. The prompt is loaded from the
 * classpath resource {@code prompts/entity-extraction.txt} and cached
 * by {@link ResourceUtils}. The LLM returns a simple line-based format
 * which is parsed into {@link ExtractedEntity} records.</p>
 *
 * <h3>Output Format Expected from LLM</h3>
 * <pre>
 *   ENTITY: Alice | PERSON
 *   ENTITY: Project Alpha | PROJECT
 *   RELATION: Alice | MANAGES | Project Alpha
 * </pre>
 *
 * <h3>Fallback</h3>
 * <p>If the LLM is unavailable or returns unparseable output,
 * returns an empty list (graceful degradation).</p>
 *
 * <h3>Performance Note</h3>
 * <p>LLM inference adds ~500ms–2s per memory. Use this extractor for
 * high-value ingestion where entity quality justifies the latency.</p>
 *
 * @see EntityExtractor
 * @see TextGenerationProvider
 * @see ResourceUtils
 */
public final class LlmEntityExtractor implements EntityExtractor {

    private static final Logger log = LoggerFactory.getLogger(LlmEntityExtractor.class);

    /** Classpath path to the entity extraction prompt template. */
    private static final String PROMPT_RESOURCE = "prompts/entity-extraction.txt";

    private static final int MAX_CONTENT_FOR_PROMPT = 1500;
    private static final int DEFAULT_MAX_ENTITIES = 10;
    private static final int DEFAULT_MAX_RELATIONS = 20;

    private static final Pattern ENTITY_PATTERN = Pattern.compile(
            "^ENTITY:\\s*(.+?)\\s*\\|\\s*(\\w+)\\s*$", Pattern.MULTILINE);
    private static final Pattern RELATION_PATTERN = Pattern.compile(
            "^RELATION:\\s*(.+?)\\s*\\|\\s*(\\w+)\\s*\\|\\s*(.+?)\\s*$", Pattern.MULTILINE);

    private final TextGenerationProvider generator;
    private final int maxEntities;
    private final int maxRelations;

    /**
     * Creates an LLM entity extractor with default limits.
     *
     * @param generator the text generation provider
     */
    public LlmEntityExtractor(TextGenerationProvider generator) {
        this(generator, DEFAULT_MAX_ENTITIES, DEFAULT_MAX_RELATIONS);
    }

    /**
     * Creates an LLM entity extractor with custom limits.
     *
     * @param generator    the text generation provider
     * @param maxEntities  maximum entities to extract per memory
     * @param maxRelations maximum relations to extract per memory
     */
    public LlmEntityExtractor(TextGenerationProvider generator,
                               int maxEntities, int maxRelations) {
        this.generator = generator;
        this.maxEntities = maxEntities;
        this.maxRelations = maxRelations;
    }

    @Override
    public List<ExtractedEntity> extract(String id, String text) {
        if (generator == null || !generator.isAvailable()) {
            return List.of();
        }

        try {
            String content = text != null && text.length() > MAX_CONTENT_FOR_PROMPT
                    ? text.substring(0, MAX_CONTENT_FOR_PROMPT) : text;

            // Load prompt template from classpath (cached by ResourceUtils)
            String promptTemplate = ResourceUtils.loadResource(PROMPT_RESOURCE);
            String prompt = String.format(promptTemplate,
                    maxEntities, maxRelations,
                    content != null ? content : id);
            String response = generator.generate(prompt);

            if (response == null || response.isBlank()) {
                log.debug("LLM returned empty entities for '{}', skipping", id);
                return List.of();
            }

            return parseResponse(response, id);

        } catch (Exception e) {
            log.warn("LLM entity extraction failed for '{}': {}", id, e.getMessage());
            return List.of();
        }
    }

    @Override
    public boolean isAvailable() {
        return generator != null && generator.isAvailable();
    }

    /**
     * Parses the LLM response into extracted entities with relations.
     */
    private List<ExtractedEntity> parseResponse(String response, String id) {
        // Parse entities
        List<String> entityNames = new ArrayList<>();
        List<EntityType> entityTypes = new ArrayList<>();

        Matcher entityMatcher = ENTITY_PATTERN.matcher(response);
        int entityCount = 0;
        while (entityMatcher.find() && entityCount < maxEntities) {
            String name = entityMatcher.group(1).trim();
            String typeStr = entityMatcher.group(2).trim().toUpperCase(Locale.ROOT);

            EntityType type;
            try {
                type = EntityType.valueOf(typeStr);
            } catch (IllegalArgumentException e) {
                type = EntityType.OTHER;
            }

            entityNames.add(name);
            entityTypes.add(type);
            entityCount++;
        }

        if (entityNames.isEmpty()) {
            log.debug("No entities parsed from LLM response for '{}'", id);
            return List.of();
        }

        // Parse relations
        List<RelationTriple> relations = new ArrayList<>();
        Matcher relationMatcher = RELATION_PATTERN.matcher(response);
        int relationCount = 0;
        while (relationMatcher.find() && relationCount < maxRelations) {
            String source = relationMatcher.group(1).trim();
            String typeStr = relationMatcher.group(2).trim().toUpperCase(Locale.ROOT);
            String target = relationMatcher.group(3).trim();

            RelationType relType;
            try {
                relType = RelationType.valueOf(typeStr);
            } catch (IllegalArgumentException e) {
                relType = RelationType.OTHER;
            }

            relations.add(new RelationTriple(source, relType, target));
            relationCount++;
        }

        // Build ExtractedEntity list with attached relations
        List<ExtractedEntity> result = new ArrayList<>();
        for (int i = 0; i < entityNames.size(); i++) {
            String name = entityNames.get(i);
            EntityType type = entityTypes.get(i);

            // Collect relations where this entity is the source
            List<EntityRelation> entityRelations = relations.stream()
                    .filter(r -> r.source.equalsIgnoreCase(name))
                    .map(r -> new EntityRelation(r.target, r.type))
                    .toList();

            result.add(new ExtractedEntity(name, type, entityRelations));
        }

        log.debug("LLM extracted {} entities, {} relations for '{}'",
                entityNames.size(), relations.size(), id);
        return result;
    }

    private record RelationTriple(String source, RelationType type, String target) {}
}
