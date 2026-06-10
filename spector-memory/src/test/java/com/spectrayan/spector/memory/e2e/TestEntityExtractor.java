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
package com.spectrayan.spector.memory.e2e;

import com.spectrayan.spector.memory.graph.EntityExtractor;
import com.spectrayan.spector.memory.graph.EntityRelation;
import com.spectrayan.spector.memory.graph.ExtractedEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic pattern-based entity extractor for E2E tests.
 *
 * <p>Extracts entities using regex patterns and known technology lists.
 * This avoids LLM latency and cost while providing predictable entity
 * extraction for graph traversal testing.</p>
 *
 * <h3>Extraction Rules</h3>
 * <ul>
 *   <li><b>PERSON</b>: Capitalized first+last name patterns (e.g., "Alice Chen")</li>
 *   <li><b>PROJECT</b>: Words following "Project" (e.g., "Project Phoenix")</li>
 *   <li><b>TOOL/FRAMEWORK</b>: Known technology names from a static list</li>
 *   <li><b>Relations</b>: Pattern-based (e.g., "manages" → MANAGES, "created" → CREATED)</li>
 * </ul>
 */
public final class TestEntityExtractor implements EntityExtractor {

    /** Pattern for person names: two consecutive capitalized words not in common word list. */
    private static final Pattern PERSON_PATTERN = Pattern.compile(
            "\\b([A-Z][a-z]+ [A-Z][a-z]+)\\b");

    /** Pattern for project references. */
    private static final Pattern PROJECT_PATTERN = Pattern.compile(
            "\\bProject\\s+([A-Z][a-zA-Z]+)\\b");

    /** Known technology names → entity type string mapping. */
    private static final Map<String, String> TECH_NAMES = Map.ofEntries(
            Map.entry("postgresql", "TECHNOLOGY"),
            Map.entry("postgres", "TECHNOLOGY"),
            Map.entry("redis", "TECHNOLOGY"),
            Map.entry("kafka", "TOOL"),
            Map.entry("kubernetes", "TOOL"),
            Map.entry("docker", "TOOL"),
            Map.entry("grafana", "TOOL"),
            Map.entry("prometheus", "TOOL"),
            Map.entry("jenkins", "TOOL"),
            Map.entry("terraform", "TOOL"),
            Map.entry("spring boot", "TECHNOLOGY"),
            Map.entry("spring", "TECHNOLOGY"),
            Map.entry("flyway", "TECHNOLOGY"),
            Map.entry("gatling", "TOOL"),
            Map.entry("hibernate", "TECHNOLOGY"),
            Map.entry("hikaricp", "TECHNOLOGY"),
            Map.entry("java", "SKILL"),
            Map.entry("grpc", "API"),
            Map.entry("jwt", "API"),
            Map.entry("oauth2", "API"),
            Map.entry("avro", "API"),
            Map.entry("aws", "PRODUCT"),
            Map.entry("github", "PRODUCT")
    );

    /** Common words to exclude from person name detection. */
    private static final Set<String> COMMON_EXCLUSIONS = Set.of(
            "The", "This", "That", "When", "Each", "All", "Any",
            "Not Null", "Code Review", "Data Loss", "File System",
            "Load Test", "Schema Registry", "API Portal", "REST API",
            "Batch Processing", "Full Access", "Read Only",
            "Spring Boot", "Spring Cloud", "Spring Security",
            "Spring Data", "Cloud Config", "Pod Autoscaler",
            "Auto Configuration", "Point Recovery"
    );

    /** Relation keyword → relation type string mapping. */
    private static final Map<String, String> RELATION_KEYWORDS = Map.of(
            "manages", "MANAGES",
            "manage", "MANAGES",
            "authored", "AUTHORED",
            "created", "CREATED_BY",
            "reports to", "REPORTS_TO",
            "depends on", "DEPENDS_ON",
            "uses", "USES",
            "reviewed", "AUTHORED",
            "implemented", "IMPLEMENTS",
            "designed", "CREATED_BY"
    );

    @Override
    public List<ExtractedEntity> extract(String id, String text) {
        if (text == null || text.isBlank()) return List.of();

        // Use LinkedHashMap to maintain insertion order and deduplicate
        Map<String, String> entities = new LinkedHashMap<>();
        List<RelationTriple> relations = new ArrayList<>();

        // Extract persons
        Matcher personMatcher = PERSON_PATTERN.matcher(text);
        while (personMatcher.find()) {
            String name = personMatcher.group(1);
            if (!COMMON_EXCLUSIONS.contains(name)
                    && !TECH_NAMES.containsKey(name.toLowerCase(Locale.ROOT))) {
                entities.putIfAbsent(name, "PERSON");
            }
        }

        // Extract projects
        Matcher projectMatcher = PROJECT_PATTERN.matcher(text);
        while (projectMatcher.find()) {
            String projectName = "Project " + projectMatcher.group(1);
            entities.putIfAbsent(projectName, "PROJECT");
        }

        // Extract technologies (case-insensitive search)
        String lower = text.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : TECH_NAMES.entrySet()) {
            if (lower.contains(entry.getKey())) {
                // Capitalize for consistency
                String techName = capitalizeFirst(entry.getKey());
                entities.putIfAbsent(techName, entry.getValue());
            }
        }

        // Extract relations between found entities
        List<String> entityNames = new ArrayList<>(entities.keySet());
        for (Map.Entry<String, String> rkEntry : RELATION_KEYWORDS.entrySet()) {
            String keyword = rkEntry.getKey();
            if (lower.contains(keyword)) {
                // Try to find source and target entities near the keyword
                for (int i = 0; i < entityNames.size(); i++) {
                    for (int j = i + 1; j < entityNames.size(); j++) {
                        String source = entityNames.get(i);
                        String target = entityNames.get(j);
                        // Check if source appears before the keyword and target after
                        int sourceIdx = text.indexOf(source);
                        int keywordIdx = lower.indexOf(keyword);
                        int targetIdx = text.indexOf(target, keywordIdx);
                        if (sourceIdx >= 0 && sourceIdx < keywordIdx && targetIdx > keywordIdx) {
                            relations.add(new RelationTriple(source, rkEntry.getValue(), target));
                        }
                    }
                }
            }
        }

        // Build result
        List<ExtractedEntity> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : entities.entrySet()) {
            String name = entry.getKey();
            String type = entry.getValue();

            List<EntityRelation> entityRelations = relations.stream()
                    .filter(r -> r.source.equals(name))
                    .map(r -> new EntityRelation(r.target, r.type))
                    .toList();

            result.add(new ExtractedEntity(name, type, entityRelations));
        }

        return result;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    private static String capitalizeFirst(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private record RelationTriple(String source, String type, String target) {}
}
