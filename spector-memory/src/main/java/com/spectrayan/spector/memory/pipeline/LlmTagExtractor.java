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
package com.spectrayan.spector.memory.pipeline;

import com.spectrayan.spector.embed.TextGenerationProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Locale;

/**
 * LLM-powered tag extractor that uses a {@link TextGenerationProvider}
 * to extract semantic tags from document content.
 *
 * <h3>How It Works</h3>
 * <p>
 * Sends a structured prompt to the LLM asking it to identify 5–10
 * contextual tags from the text. The LLM returns comma-separated tags
 * which are parsed into the synaptic tag array.
 * </p>
 *
 * <h3>Fallback</h3>
 * <p>
 * If the LLM is unavailable or returns an unparseable response,
 * falls back to {@link ContentTagExtractor} for basic keyword extraction.
 * </p>
 *
 * <h3>Performance Note</h3>
 * <p>
 * LLM inference adds ~500ms–2s per chunk. Use this extractor for
 * high-value ingestion (e.g., user-provided documents) where tag quality
 * justifies the latency. For bulk ingestion of thousands of files,
 * {@link ContentTagExtractor} is recommended.
 * </p>
 *
 * @see TagExtractor
 * @see TextGenerationProvider
 */
public final class LlmTagExtractor implements TagExtractor {

    private static final Logger log = LoggerFactory.getLogger(LlmTagExtractor.class);

    private static final int MAX_TAGS = 30;

    private static final String PROMPT_TEMPLATE = """
            Extract 10 to 15 highly specific contextual tags from the following text.

            Tag Formatting Rules:
            1. All tags must be in lowercase.
            2. Multi-word tags must be hyphenated instead of using spaces (e.g., "product launch" becomes "product-launch").
            3. Do not include spaces, symbols, punctuation, or special characters (hyphens are allowed per rule 2).
            4. Every tag must be directly derived from the text. Do not invent or infer tags that are not present.
            5. Return ONLY a comma-separated list of tags. No markdown, no explanations, no preamble.

            Text to extract from:
            %s

            Tags:""";

    private final TextGenerationProvider generator;
    private final TagExtractor fallback;

    /**
     * Creates an LLM tag extractor with the default content-based fallback.
     *
     * @param generator the text generation provider (e.g., Ollama)
     */
    public LlmTagExtractor(TextGenerationProvider generator) {
        this(generator, new ContentTagExtractor());
    }

    /**
     * Creates an LLM tag extractor with a custom fallback.
     *
     * @param generator the text generation provider
     * @param fallback  fallback extractor for when LLM is unavailable
     */
    public LlmTagExtractor(TextGenerationProvider generator, TagExtractor fallback) {
        this.generator = generator;
        this.fallback = fallback;
    }

    @Override
    public String[] extract(String id, String text) {
        if (generator == null || !generator.isAvailable()) {
            log.info("[TagExtract] LLM unavailable for '{}', using fallback", truncId(id));
            return fallback.extract(id, text);
        }

        long startNs = System.nanoTime();
        try {
            String prompt = String.format(PROMPT_TEMPLATE, text != null ? text : id);
            log.debug("[TagExtract] LLM prompt for '{}': {} chars", truncId(id), prompt.length());

            String response = generator.generate(prompt);
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

            if (response == null || response.isBlank()) {
                log.info("[TagExtract] LLM returned empty for '{}' ({}ms), using fallback",
                        truncId(id), elapsedMs);
                return fallback.extract(id, text);
            }

            log.debug("[TagExtract] LLM raw response for '{}': '{}'", truncId(id),
                    response.length() > 200 ? response.substring(0, 200) + "..." : response);

            // Parse comma-separated tags
            String[] tags = Arrays.stream(response.split("[,;\\n]"))
                    .map(String::trim)
                    .map(s -> s.toLowerCase(Locale.ROOT))
                    .map(s -> s.replaceAll("[^a-z0-9\\- ]", ""))
                    .map(s -> s.replaceAll("\\s+", "-"))
                    .map(s -> s.replaceAll("-{2,}", "-"))
                    .map(s -> s.replaceAll("^-|-$", ""))
                    .filter(s -> !s.isBlank() && s.length() > 1)
                    .distinct()
                    .limit(MAX_TAGS)
                    .toArray(String[]::new);

            if (tags.length == 0) {
                log.info("[TagExtract] LLM tags parsed to empty for '{}' (raw='{}', {}ms), using fallback",
                        truncId(id), response.trim(), elapsedMs);
                return fallback.extract(id, text);
            }

            log.info("[TagExtract] LLM extracted {} tags for '{}' in {}ms: [{}]",
                    tags.length, truncId(id), elapsedMs, String.join(", ", tags));
            return tags;

        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
            log.warn("[TagExtract] LLM failed for '{}' in {}ms: {}, using fallback",
                    truncId(id), elapsedMs, e.getMessage());
            return fallback.extract(id, text);
        }
    }

    /** Truncate long IDs (file paths) for readable logs. */
    private static String truncId(String id) {
        if (id == null)
            return "null";
        return id.length() > 60 ? "..." + id.substring(id.length() - 57) : id;
    }
}
