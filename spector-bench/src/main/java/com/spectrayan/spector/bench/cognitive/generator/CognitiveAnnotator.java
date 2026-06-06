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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.bench.cognitive.model.BenchmarkCorpusRecord;
import com.spectrayan.spector.bench.cognitive.model.PersonaDef;

import tools.jackson.core.type.TypeReference;

/**
 * Annotates corpus records with cognitive metadata using Ollama LLM.
 *
 * <p>Takes raw or partially-annotated corpus records and enriches them with
 * cognitive annotations: valence, importance, arousal, and synaptic tags.
 * Processes records in batches to minimize LLM round-trips while respecting
 * context window limits.</p>
 *
 * <h3>Annotation Fields</h3>
 * <ul>
 *   <li><b>valence</b> — emotional tone, signed byte (-128 to +127):
 *       negative = unpleasant, 0 = neutral, positive = pleasant</li>
 *   <li><b>importance</b> — ICNU-fused score (0.05 to 10.0):
 *       how significant the memory is for retrieval ranking</li>
 *   <li><b>arousal</b> — physiological activation (0–255):
 *       how emotionally activating the memory is</li>
 *   <li><b>synapticTags</b> — 1–10 contextual tags for Bloom filter encoding</li>
 * </ul>
 */
public final class CognitiveAnnotator {

    private static final Logger log = LoggerFactory.getLogger(CognitiveAnnotator.class);

    /** Number of records to annotate per LLM batch request. */
    private static final int BATCH_SIZE = 15;

    private static final String SYSTEM_PROMPT = """
            You are a cognitive annotation engine. For each memory text provided, assign
            cognitive metadata that would be appropriate for a memory retrieval system.
            
            For each memory, provide:
            - "valence": integer -128 to 127 (negative=unpleasant, 0=neutral, positive=pleasant)
            - "importance": float 0.05 to 10.0 (how significant/memorable this is)
            - "arousal": integer 0 to 255 (how emotionally activating — 0=calm, 255=extremely intense)
            - "synapticTags": array of 2-8 contextual keywords for semantic categorization
            
            Respond with a JSON array of annotation objects in the same order as the input memories.
            Each object must have exactly: valence, importance, arousal, synapticTags.
            
            Respond ONLY with a valid JSON array. No markdown, no explanation.
            """;

    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE =
            new TypeReference<>() {};

    private final OllamaCompletionClient client;
    private final PersonaDef persona;

    /**
     * Creates a cognitive annotator.
     *
     * @param client  the Ollama completion client for LLM annotation
     * @param persona the persona definition providing context for annotation decisions
     */
    public CognitiveAnnotator(OllamaCompletionClient client, PersonaDef persona) {
        this.client = client;
        this.persona = persona;
    }

    /**
     * Annotates all corpus records with cognitive metadata.
     *
     * <p>Processes records in batches. If annotation fails for a batch, the original
     * records are preserved with default annotation values.</p>
     *
     * @param records the corpus records to annotate
     * @return new list of records with cognitive annotations applied
     */
    public List<BenchmarkCorpusRecord> annotateAll(List<BenchmarkCorpusRecord> records) {
        if (records.isEmpty()) {
            return List.of();
        }

        log.info("Annotating {} corpus records in batches of {}", records.size(), BATCH_SIZE);
        List<BenchmarkCorpusRecord> annotated = new ArrayList<>(records.size());

        for (int i = 0; i < records.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, records.size());
            List<BenchmarkCorpusRecord> batch = records.subList(i, end);

            List<BenchmarkCorpusRecord> annotatedBatch = annotateBatch(batch);
            annotated.addAll(annotatedBatch);

            if ((i / BATCH_SIZE) % 10 == 0) {
                log.info("Annotation progress: {}/{} records", Math.min(end, records.size()), records.size());
            }
        }

        log.info("Annotation complete: {} records processed", annotated.size());
        return annotated;
    }

    // ─────────────── Private helpers ───────────────

    private List<BenchmarkCorpusRecord> annotateBatch(List<BenchmarkCorpusRecord> batch) {
        String userPrompt = buildBatchPrompt(batch);

        try {
            List<Map<String, Object>> annotations = client.completeAsJson(SYSTEM_PROMPT, userPrompt, LIST_MAP_TYPE);
            return applyAnnotations(batch, annotations);
        } catch (OllamaCompletionException e) {
            log.warn("Annotation batch failed, preserving defaults: {}", e.getMessage());
            return new ArrayList<>(batch);
        }
    }

    private String buildBatchPrompt(List<BenchmarkCorpusRecord> batch) {
        StringBuilder sb = new StringBuilder();
        sb.append("Annotate these ").append(batch.size()).append(" memories with cognitive metadata.\n");
        sb.append("Persona context: ").append(persona.name()).append(", ")
          .append(persona.age()).append("yo ").append(persona.occupation()).append(".\n\n");

        for (int i = 0; i < batch.size(); i++) {
            BenchmarkCorpusRecord record = batch.get(i);
            sb.append("Memory ").append(i + 1).append(": [").append(record.title()).append("] ");
            sb.append(record.text()).append("\n");
        }

        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private List<BenchmarkCorpusRecord> applyAnnotations(List<BenchmarkCorpusRecord> batch,
                                                          List<Map<String, Object>> annotations) {
        List<BenchmarkCorpusRecord> result = new ArrayList<>(batch.size());

        for (int i = 0; i < batch.size(); i++) {
            BenchmarkCorpusRecord original = batch.get(i);

            if (i < annotations.size()) {
                Map<String, Object> ann = annotations.get(i);
                byte valence = clampByte(getNumberOr(ann, "valence", 0));
                float importance = clampImportance(getFloatOr(ann, "importance", 1.0f));
                int arousal = clampArousal(getNumberOr(ann, "arousal", 50));
                List<String> tags = parseStringList(ann.get("synapticTags"));

                if (tags.isEmpty()) {
                    tags = List.of("general");
                }
                if (tags.size() > 10) {
                    tags = tags.subList(0, 10);
                }

                result.add(new BenchmarkCorpusRecord(
                        original.id(), original.text(), original.title(),
                        tags, valence, importance, arousal,
                        original.sessionId(), original.timestampMs(),
                        original.entityMentions(), original.memoryType(),
                        original.agentRecallCount()
                ));
            } else {
                // Annotation count mismatch — keep original
                result.add(original);
            }
        }

        return result;
    }

    private static byte clampByte(int value) {
        return (byte) Math.max(-128, Math.min(127, value));
    }

    private static float clampImportance(float value) {
        return Math.max(0.05f, Math.min(10.0f, value));
    }

    private static int clampArousal(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static int getNumberOr(Map<String, Object> map, String key, int defaultValue) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { /* fall through */ }
        }
        return defaultValue;
    }

    private static float getFloatOr(Map<String, Object> map, String key, float defaultValue) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.floatValue();
        if (val instanceof String s) {
            try { return Float.parseFloat(s); } catch (NumberFormatException e) { /* fall through */ }
        }
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    private static List<String> parseStringList(Object raw) {
        if (raw instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof String s && !s.isBlank()) {
                    result.add(s.toLowerCase().strip());
                }
            }
            return result;
        }
        return List.of();
    }
}
