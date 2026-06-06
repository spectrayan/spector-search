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
 * Generates daily conversations using Ollama LLM based on persona context.
 *
 * <p>Produces corpus records simulating a day's worth of interactions between
 * the persona and their AI assistant Jarvis. Each day follows time-of-day patterns
 * (morning, work, evening) and respects weekday/weekend schedules to maintain
 * temporal coherence.</p>
 *
 * <h3>Generation Strategy</h3>
 * <ul>
 *   <li>Morning slot: personal routine, planning, health tracking</li>
 *   <li>Work slot (weekdays): professional tasks, debugging, meetings</li>
 *   <li>Evening slot: social, hobbies, reflection</li>
 *   <li>Weekend: personal projects, leisure, family/friends</li>
 * </ul>
 */
public final class ConversationGenerator {

    private static final Logger log = LoggerFactory.getLogger(ConversationGenerator.class);

    private static final String SYSTEM_PROMPT = """
            You are a dataset generator for a cognitive memory benchmark. Your role is to generate
            realistic conversation memories between a person and their AI assistant Jarvis throughout a day.
            
            Generate conversation turns as a JSON array. Each turn must have a user message AND a
            Jarvis response. Each object must have:
            - "userText": what the user told Jarvis (50-300 chars, first person, natural language)
            - "aiResponse": what Jarvis replied (50-300 chars, helpful/empathetic tone)
            - "userTitle": short title for the user's memory (5-50 chars)
            - "aiTitle": short title for Jarvis's response memory (5-50 chars)
            - "memoryType": one of EPISODIC, SEMANTIC, PROCEDURAL, WORKING
            - "entityMentions": array of {"name": "...", "type": "PERSON|SOFTWARE|ORGANIZATION|LOCATION|CONCEPT"}
            
            Jarvis should respond naturally — offering suggestions, empathizing,
            asking follow-up questions, or providing relevant information.
            
            Respond ONLY with a valid JSON array. No markdown, no explanation.
            """;

    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE =
            new TypeReference<>() {};

    private final OllamaCompletionClient client;
    private final PersonaDef persona;
    private final GeneratorConfig config;
    private int nextMemoryId;

    /**
     * Creates a conversation generator.
     *
     * @param client  the Ollama completion client for LLM generation
     * @param persona the persona definition grounding conversations
     * @param config  generator configuration
     */
    public ConversationGenerator(OllamaCompletionClient client, PersonaDef persona, GeneratorConfig config) {
        this.client = client;
        this.persona = persona;
        this.config = config;
        this.nextMemoryId = 1;
    }

    /**
     * Sets the starting memory ID for generated records (used in incremental generation).
     *
     * @param startId the next memory ID to assign
     */
    public void setNextMemoryId(int startId) {
        this.nextMemoryId = startId;
    }

    /**
     * Generates corpus records for a single simulated day.
     *
     * <p>Produces multiple memories following time-of-day patterns. The day index
     * determines the simulated date and session IDs for temporal chaining.</p>
     *
     * @param dayIndex       zero-based day index (0 = most recent day, higher = further back)
     * @param existingCorpus existing corpus records for context (may be empty)
     * @return list of generated corpus records for this day
     */
    public List<BenchmarkCorpusRecord> generateDay(int dayIndex, List<BenchmarkCorpusRecord> existingCorpus) {
        List<BenchmarkCorpusRecord> dayRecords = new ArrayList<>();
        LocalDate simulatedDate = LocalDate.now().minusDays(dayIndex);
        boolean isWeekend = simulatedDate.getDayOfWeek().getValue() >= 6;

        String sessionId = "session-" + simulatedDate + "-";

        // Generate morning memories
        dayRecords.addAll(generateSlot(dayIndex, simulatedDate, sessionId + "morning",
                "morning", isWeekend, existingCorpus));

        // Generate work/daytime memories
        if (!isWeekend) {
            dayRecords.addAll(generateSlot(dayIndex, simulatedDate, sessionId + "work",
                    "work", false, existingCorpus));
        } else {
            dayRecords.addAll(generateSlot(dayIndex, simulatedDate, sessionId + "daytime",
                    "weekend-daytime", true, existingCorpus));
        }

        // Generate evening memories
        dayRecords.addAll(generateSlot(dayIndex, simulatedDate, sessionId + "evening",
                "evening", isWeekend, existingCorpus));

        log.info("Generated {} memories for day {} ({})", dayRecords.size(), dayIndex, simulatedDate);
        return dayRecords;
    }

    // ─────────────── Private helpers ───────────────

    private List<BenchmarkCorpusRecord> generateSlot(int dayIndex, LocalDate date,
                                                      String sessionId, String timeSlot,
                                                      boolean isWeekend,
                                                      List<BenchmarkCorpusRecord> existingCorpus) {
        int memoriesPerSlot = Math.max(1, config.conversationsPerDay() / 3);

        String userPrompt = buildSlotPrompt(date, timeSlot, isWeekend, memoriesPerSlot, existingCorpus);

        try {
            List<Map<String, Object>> rawMemories = client.completeAsJson(SYSTEM_PROMPT, userPrompt, LIST_MAP_TYPE);
            return convertToRecords(rawMemories, sessionId, date, timeSlot);
        } catch (OllamaCompletionException e) {
            log.warn("Failed to generate memories for slot {} on day {}: {}",
                    timeSlot, dayIndex, e.getMessage());
            return List.of();
        }
    }

    private String buildSlotPrompt(LocalDate date, String timeSlot, boolean isWeekend,
                                    int count, List<BenchmarkCorpusRecord> existingCorpus) {
        StringBuilder sb = new StringBuilder();
        sb.append("Generate ").append(count).append(" memories for the following persona ");
        sb.append("during the ").append(timeSlot).append(" slot on ").append(date);
        if (isWeekend) {
            sb.append(" (weekend)");
        }
        sb.append(".\n\n");

        sb.append("Persona:\n");
        sb.append("- Name: ").append(persona.name()).append("\n");
        sb.append("- Age: ").append(persona.age()).append("\n");
        sb.append("- Occupation: ").append(persona.occupation()).append("\n");
        sb.append("- Interests: ").append(String.join(", ", persona.interests())).append("\n");
        sb.append("- Life context: ").append(persona.lifeContext()).append("\n");
        sb.append("- Personality: ").append(String.join(", ", persona.personalityTraits())).append("\n");

        // Provide recent context from existing corpus
        if (!existingCorpus.isEmpty()) {
            sb.append("\nRecent conversation context (for continuity):\n");
            int start = Math.max(0, existingCorpus.size() - 10);
            for (int i = start; i < existingCorpus.size(); i++) {
                BenchmarkCorpusRecord rec = existingCorpus.get(i);
                sb.append("- [").append(rec.title()).append("]: ").append(truncate(rec.text(), 120)).append("\n");
            }
        }

        sb.append("\nTime slot guidance:\n");
        switch (timeSlot) {
            case "morning" -> sb.append("Personal routine, planning the day, health/exercise, breakfast, commute thoughts.");
            case "work" -> sb.append("Professional tasks, code reviews, meetings, debugging, collaboration, problem-solving.");
            case "evening" -> sb.append("Social activities, hobbies, cooking, relaxation, reflection on the day, personal projects.");
            case "weekend-daytime" -> sb.append("Personal projects, leisure, errands, social outings, hobbies, exercise.");
            default -> sb.append("General daily activities.");
        }

        return sb.toString();
    }

    private List<BenchmarkCorpusRecord> convertToRecords(List<Map<String, Object>> rawMemories,
                                                          String sessionId, LocalDate date,
                                                          String timeSlot) {
        List<BenchmarkCorpusRecord> records = new ArrayList<>();
        int baseHour = switch (timeSlot) {
            case "morning" -> 7;
            case "work" -> 9;
            case "evening" -> 18;
            case "weekend-daytime" -> 10;
            default -> 12;
        };

        for (int i = 0; i < rawMemories.size(); i++) {
            Map<String, Object> raw = rawMemories.get(i);
            try {
                // User message record
                String userText = getStringOr(raw, "userText", getStringOr(raw, "text", "Memory content unavailable"));
                if (userText.isBlank() || userText.length() < 10) {
                    log.debug("Skipping entry with empty/short user text at index {}", i);
                    continue;
                }
                String userTitle = getStringOr(raw, "userTitle", getStringOr(raw, "title", "Untitled"));
                String memTypeStr = getStringOr(raw, "memoryType", getStringOr(raw, "memory_type", "EPISODIC"));
                MemoryType memoryType = parseMemoryType(memTypeStr);

                long userTimestampMs = date.atTime(baseHour + i, i * 10 % 60)
                        .toInstant(ZoneOffset.UTC).toEpochMilli();

                List<EntityMention> entities = parseEntityMentions(
                        raw.get("entityMentions") != null ? raw.get("entityMentions") : raw.get("entity_mentions"));

                String userId = String.format("mem-%04d", nextMemoryId++);
                BenchmarkCorpusRecord userRecord = new BenchmarkCorpusRecord(
                        userId, userText, userTitle,
                        List.of(), // synapticTags — filled by CognitiveAnnotator
                        (byte) 0, // valence — filled by CognitiveAnnotator
                        1.0f,     // importance — filled by CognitiveAnnotator
                        50,       // arousal — filled by CognitiveAnnotator
                        sessionId,
                        userTimestampMs,
                        entities,
                        memoryType,
                        0         // agentRecallCount starts at 0
                );
                records.add(userRecord);

                // Jarvis response record (if present)
                String aiResponse = getStringOr(raw, "aiResponse", null);
                if (aiResponse != null) {
                    String aiTitle = getStringOr(raw, "aiTitle", "AI: " + userTitle);
                    String aiId = String.format("mem-%04d", nextMemoryId++);
                    long aiTimestampMs = userTimestampMs + 30_000L; // 30 seconds after user

                    BenchmarkCorpusRecord aiRecord = new BenchmarkCorpusRecord(
                            aiId, aiResponse, aiTitle,
                            List.of(),
                            (byte) 0,
                            1.0f,
                            50,
                            sessionId,
                            aiTimestampMs,
                            entities, // share entity mentions
                            MemoryType.SEMANTIC, // AI responses are typically semantic knowledge
                            0
                    );
                    records.add(aiRecord);
                }
            } catch (Exception e) {
                log.warn("Skipping malformed memory at index {}: {}", i, e.getMessage());
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

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
