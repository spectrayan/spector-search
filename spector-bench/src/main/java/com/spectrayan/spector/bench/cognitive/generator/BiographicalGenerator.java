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
package com.spectrayan.spector.bench.cognitive.generator;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.bench.cognitive.model.BenchmarkCorpusRecord;
import com.spectrayan.spector.bench.cognitive.model.EntityMention;
import com.spectrayan.spector.bench.cognitive.model.PersonaDef;
import com.spectrayan.spector.memory.MemoryType;

import tools.jackson.core.type.TypeReference;

/**
 * Generates biographical and historical memories spanning the persona's past.
 *
 * <p>Produces long-term memories covering childhood, school years, career milestones,
 * family events, and formative experiences. These memories are backdated to timestamps
 * spanning the configured {@code biographicalDepthYears} into the past, providing the
 * temporal depth needed for recency-based retrieval testing.</p>
 *
 * <h3>Biographical Categories</h3>
 * <ul>
 *   <li>Childhood/early life — ages 5–12</li>
 *   <li>School/education — ages 12–22</li>
 *   <li>Early career — ages 22–(current age - 5)</li>
 *   <li>Recent career — last 5 years</li>
 *   <li>Family/relationships — scattered across timeline</li>
 *   <li>Formative experiences — key life events at various ages</li>
 * </ul>
 */
public final class BiographicalGenerator {

    private static final Logger log = LoggerFactory.getLogger(BiographicalGenerator.class);

    private static final String SYSTEM_PROMPT = """
            You are a dataset generator for a cognitive memory benchmark. Generate biographical
            memories from a person's past — things they would naturally recall or share with an
            AI companion when reminiscing.
            
            Generate memories as a JSON array. Each memory object must have:
            - "text": the memory content (50-400 chars, first person, reflective/nostalgic tone)
            - "title": a short title (5-50 chars)
            - "memory_type": one of EPISODIC, SEMANTIC, PROCEDURAL
            - "category": one of "childhood", "school", "career", "family", "formative"
            - "approximate_age": the age when this memory occurred
            - "entity_mentions": array of {"name": "...", "type": "PERSON|ORGANIZATION|LOCATION|CONCEPT"}
            
            Respond ONLY with a valid JSON array. No markdown, no explanation.
            """;

    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE =
            new TypeReference<>() {};

    private final OllamaCompletionClient client;
    private final PersonaDef persona;
    private final GeneratorConfig config;
    private int nextMemoryId;

    /**
     * Creates a biographical generator.
     *
     * @param client  the Ollama completion client for LLM generation
     * @param persona the persona definition providing life context
     * @param config  generator configuration with biographical depth settings
     */
    public BiographicalGenerator(OllamaCompletionClient client, PersonaDef persona, GeneratorConfig config) {
        this.client = client;
        this.persona = persona;
        this.config = config;
        this.nextMemoryId = 1;
    }

    /**
     * Sets the starting memory ID for generated records.
     *
     * @param startId the next memory ID to assign
     */
    public void setNextMemoryId(int startId) {
        this.nextMemoryId = startId;
    }

    /**
     * Generates biographical memories spanning the persona's life history.
     *
     * <p>Creates memories distributed across the configured biographical depth,
     * covering major life periods and categories. The number of memories per
     * category is proportional to the time span and importance of each period.</p>
     *
     * @param existingCorpus existing corpus for context (may be empty)
     * @return list of generated biographical corpus records with backdated timestamps
     */
    public List<BenchmarkCorpusRecord> generateBiographical(List<BenchmarkCorpusRecord> existingCorpus) {
        List<BenchmarkCorpusRecord> allBiographical = new ArrayList<>();
        int depthYears = config.biographicalDepthYears();

        // Target ~20% of total corpus as biographical
        int targetCount = config.totalCorpusSize() / 5;
        int perCategory = Math.max(2, targetCount / 5);

        log.info("Generating ~{} biographical memories spanning {} years", targetCount, depthYears);

        // Generate memories for each biographical category
        allBiographical.addAll(generateCategory("childhood", 5, 12, perCategory));
        allBiographical.addAll(generateCategory("school", 12, 22, perCategory));
        allBiographical.addAll(generateCategory("career",
                Math.max(22, persona.age() - depthYears), persona.age() - 2, perCategory));
        allBiographical.addAll(generateCategory("family", 15, persona.age(), perCategory));
        allBiographical.addAll(generateCategory("formative", 10, persona.age(), perCategory));

        log.info("Generated {} total biographical memories", allBiographical.size());
        return allBiographical;
    }

    // ─────────────── Private helpers ───────────────

    private List<BenchmarkCorpusRecord> generateCategory(String category, int ageFrom, int ageTo, int count) {
        String userPrompt = buildCategoryPrompt(category, ageFrom, ageTo, count);

        try {
            List<Map<String, Object>> rawMemories = client.completeAsJson(SYSTEM_PROMPT, userPrompt, LIST_MAP_TYPE);
            return convertToRecords(rawMemories, category, ageFrom, ageTo);
        } catch (OllamaCompletionException e) {
            log.warn("Failed to generate biographical memories for category '{}': {}",
                    category, e.getMessage());
            return List.of();
        }
    }

    private String buildCategoryPrompt(String category, int ageFrom, int ageTo, int count) {
        StringBuilder sb = new StringBuilder();
        sb.append("Generate ").append(count).append(" biographical memories for this persona ");
        sb.append("from the '").append(category).append("' period of their life ");
        sb.append("(ages ").append(ageFrom).append(" to ").append(ageTo).append(").\n\n");

        sb.append("Persona:\n");
        sb.append("- Name: ").append(persona.name()).append("\n");
        sb.append("- Current age: ").append(persona.age()).append("\n");
        sb.append("- Occupation: ").append(persona.occupation()).append("\n");
        sb.append("- Interests: ").append(String.join(", ", persona.interests())).append("\n");
        sb.append("- Life context: ").append(persona.lifeContext()).append("\n");
        sb.append("- Personality: ").append(String.join(", ", persona.personalityTraits())).append("\n");

        sb.append("\nCategory guidance:\n");
        switch (category) {
            case "childhood" -> sb.append("Early memories: family moments, first experiences, "
                    + "childhood friends, moving houses, pets, elementary school milestones.");
            case "school" -> sb.append("Academic experiences: teachers, friendships, first crushes, "
                    + "extracurriculars, graduation, college decisions, discoveries.");
            case "career" -> sb.append("Professional journey: first job, promotions, mentors, "
                    + "major projects, career pivots, workplace relationships, achievements.");
            case "family" -> sb.append("Family events: holidays, births, losses, traditions, "
                    + "romantic relationships, friendships, moving, major life changes.");
            case "formative" -> sb.append("Key life events: moments of personal growth, challenges overcome, "
                    + "realizations, turning points, travels, skills learned.");
            default -> sb.append("General life memories.");
        }

        return sb.toString();
    }

    private List<BenchmarkCorpusRecord> convertToRecords(List<Map<String, Object>> rawMemories,
                                                          String category, int ageFrom, int ageTo) {
        List<BenchmarkCorpusRecord> records = new ArrayList<>();

        for (int i = 0; i < rawMemories.size(); i++) {
            Map<String, Object> raw = rawMemories.get(i);
            try {
                String id = String.format("mem-%04d", nextMemoryId++);
                String text = getStringOr(raw, "text", "Biographical memory");
                String title = getStringOr(raw, "title", "Untitled");
                String memTypeStr = getStringOr(raw, "memory_type", "EPISODIC");
                MemoryType memoryType = parseMemoryType(memTypeStr);

                // Calculate timestamp based on approximate age
                int approxAge = getIntOr(raw, "approximate_age", ageFrom + (ageTo - ageFrom) / 2);
                approxAge = Math.max(ageFrom, Math.min(ageTo, approxAge));
                int yearsAgo = persona.age() - approxAge;
                long timestampMs = LocalDate.now().minusYears(yearsAgo).minusDays(i)
                        .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();

                String sessionId = "session-biographical-" + category + "-" + i;
                List<EntityMention> entities = parseEntityMentions(raw.get("entity_mentions"));

                BenchmarkCorpusRecord record = new BenchmarkCorpusRecord(
                        id, text, title,
                        List.of(), // synapticTags — filled by CognitiveAnnotator
                        (byte) 0, // valence — filled by CognitiveAnnotator
                        1.0f,     // importance — filled by CognitiveAnnotator
                        50,       // arousal — filled by CognitiveAnnotator
                        sessionId,
                        timestampMs,
                        entities,
                        memoryType,
                        0
                );
                records.add(record);
            } catch (Exception e) {
                log.warn("Skipping malformed biographical memory at index {}: {}", i, e.getMessage());
            }
        }
        return records;
    }

    @SuppressWarnings("unchecked")
    private List<EntityMention> parseEntityMentions(Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof List<?> list) {
            List<EntityMention> entities = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> wildMap) {
                    Map<String, Object> map = (Map<String, Object>) wildMap;
                    String name = String.valueOf(map.getOrDefault("name", "unknown"));
                    String type = String.valueOf(map.getOrDefault("type", "CONCEPT"));
                    entities.add(new EntityMention(name, type));
                }
            }
            return entities;
        }
        return List.of();
    }

    private static MemoryType parseMemoryType(String raw) {
        try {
            return MemoryType.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return MemoryType.EPISODIC;
        }
    }

    private static String getStringOr(Map<String, Object> map, String key, String defaultValue) {
        Object val = map.get(key);
        return val instanceof String s ? s : defaultValue;
    }

    private static int getIntOr(Map<String, Object> map, String key, int defaultValue) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { /* fall through */ }
        }
        return defaultValue;
    }
}
