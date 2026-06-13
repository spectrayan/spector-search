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
package com.spectrayan.spector.test.judge;

import com.spectrayan.spector.embed.GenerationOptions;
import com.spectrayan.spector.embed.TextGenerationProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM-based test judge for semantic validation of recall results.
 *
 * <p>Sends structured prompts to a {@link TextGenerationProvider} and parses
 * the LLM's JSON verdict to determine whether test results meet expectations.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   var llm = OllamaLlmProvider.create("qwen3:0.6b");
 *   var judge = LlmTestJudge.create(llm);
 *
 *   JudgeVerdict verdict = judge.judgeRelevance(
 *       "PostgreSQL connection pool exhaustion",
 *       resultTexts,
 *       "Results should contain memories about database connection issues"
 *   );
 *
 *   if (!verdict.passed()) {
 *       log.warn("LLM Judge: {}", verdict);
 *   }
 * }</pre>
 *
 * <h3>Thread Safety</h3>
 * <p>This class is thread-safe if the underlying {@link TextGenerationProvider} is.</p>
 *
 * @see JudgeVerdict
 * @see JudgePromptTemplates
 */
public class LlmTestJudge {

    private static final Logger log = LoggerFactory.getLogger(LlmTestJudge.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Regex to extract JSON from LLM response (handles thinking tags, markdown fences, etc.) */
    private static final Pattern JSON_EXTRACT = Pattern.compile(
            "\\{\\s*\"relevant\"\\s*:\\s*(true|false).*?\\}", Pattern.DOTALL);

    private final TextGenerationProvider llm;
    private final float temperature;
    private final int maxRetries;
    private final float confidenceThreshold;

    private LlmTestJudge(TextGenerationProvider llm, float temperature,
                          int maxRetries, float confidenceThreshold) {
        this.llm = llm;
        this.temperature = temperature;
        this.maxRetries = maxRetries;
        this.confidenceThreshold = confidenceThreshold;
    }

    /**
     * Creates a judge with default settings.
     *
     * @param llm the text generation provider
     * @return configured judge
     */
    public static LlmTestJudge create(TextGenerationProvider llm) {
        return new LlmTestJudge(llm, 0.1f, 2, 0.6f);
    }

    /**
     * Returns a new judge with a custom temperature.
     */
    public LlmTestJudge withTemperature(float t) {
        return new LlmTestJudge(llm, t, maxRetries, confidenceThreshold);
    }

    /**
     * Returns a new judge with custom retry count.
     */
    public LlmTestJudge withMaxRetries(int retries) {
        return new LlmTestJudge(llm, temperature, retries, confidenceThreshold);
    }

    /**
     * Returns a new judge with a custom confidence threshold.
     */
    public LlmTestJudge withConfidenceThreshold(float t) {
        return new LlmTestJudge(llm, temperature, maxRetries, t);
    }

    /**
     * Returns the underlying LLM provider.
     */
    public TextGenerationProvider llm() {
        return llm;
    }

    // ═══════════════════════════════════════════════════
    //  Judgment methods
    // ═══════════════════════════════════════════════════

    /**
     * Judges whether recall results are semantically relevant to the query.
     *
     * @param query       the recall query
     * @param resultTexts the text content of each recall result
     * @param criteria    human-readable description of what makes results relevant
     * @return the LLM's verdict
     */
    public JudgeVerdict judgeRelevance(String query, List<String> resultTexts, String criteria) {
        String prompt = JudgePromptTemplates.relevancePrompt(query, resultTexts, criteria);
        return executeJudgment(query, resultTexts.size(), prompt);
    }

    /**
     * Judges whether the ranking order of results makes sense.
     *
     * @param query         the recall query
     * @param rankedResults result texts in score-descending order
     * @return the LLM's verdict
     */
    public JudgeVerdict judgeRanking(String query, List<String> rankedResults) {
        String prompt = JudgePromptTemplates.rankingPrompt(query, rankedResults);
        return executeJudgment(query, rankedResults.size(), prompt);
    }

    /**
     * Judges whether results cover expected topics.
     *
     * @param query          the recall query
     * @param resultTexts    the text content of each recall result
     * @param expectedTopics topics that should be covered
     * @return the LLM's verdict
     */
    public JudgeVerdict judgeCoverage(String query, List<String> resultTexts,
                                      List<String> expectedTopics) {
        String prompt = JudgePromptTemplates.coveragePrompt(query, resultTexts, expectedTopics);
        return executeJudgment(query, resultTexts.size(), prompt);
    }

    // ═══════════════════════════════════════════════════
    //  Core execution
    // ═══════════════════════════════════════════════════

    private JudgeVerdict executeJudgment(String query, int resultCount, String prompt) {
        GenerationOptions options = GenerationOptions.builder()
                .temperature(temperature)
                .maxTokens(256)
                .build();

        long startNanos = System.nanoTime();

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                String rawResponse = llm.generate(prompt, options);
                long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;

                JudgeVerdict verdict = parseVerdict(rawResponse, query, resultCount, latencyMs);
                if (verdict != null) {
                    log.info("LLM Judge [{}]: {} (confidence={}, latency={}ms) — {}",
                            llm.modelName(),
                            verdict.relevant() ? "✅ RELEVANT" : "❌ NOT_RELEVANT",
                            String.format("%.2f", verdict.confidence()),
                            verdict.latencyMs(),
                            verdict.reasoning());
                    return verdict;
                }

                log.warn("LLM Judge: Failed to parse verdict on attempt {} — raw: {}",
                        attempt + 1, truncate(rawResponse, 200));

            } catch (TextGenerationProvider.GenerationException e) {
                long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
                log.warn("LLM Judge: Generation failed on attempt {}: {}", attempt + 1, e.getMessage());

                if (attempt == maxRetries) {
                    return JudgeVerdict.parseFailure(query, e.getMessage(), latencyMs);
                }
            }
        }

        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
        return JudgeVerdict.parseFailure(query, "All retry attempts failed", latencyMs);
    }

    // ═══════════════════════════════════════════════════
    //  Response parsing
    // ═══════════════════════════════════════════════════

    /**
     * Parses the LLM's JSON verdict from its response.
     *
     * <p>Handles common LLM output quirks: thinking tags, markdown code fences,
     * extra text before/after the JSON object.</p>
     */
    private JudgeVerdict parseVerdict(String rawResponse, String query,
                                      int resultCount, long latencyMs) {
        if (rawResponse == null || rawResponse.isBlank()) return null;

        // Strip thinking tags if present (qwen3/gemma4 output <think>...</think>)
        String cleaned = rawResponse.replaceAll("(?s)<think>.*?</think>", "").strip();

        // Extract JSON object from response (outside thinking tags)
        Matcher matcher = JSON_EXTRACT.matcher(cleaned);

        // Fallback: if no JSON found after stripping, try inside the raw response
        // (gemma4 sometimes puts the verdict JSON inside the thinking block)
        if (!matcher.find()) {
            matcher = JSON_EXTRACT.matcher(rawResponse);
            if (!matcher.find()) return null;
        }

        String jsonStr = matcher.group();

        try {
            JsonNode node = MAPPER.readTree(jsonStr);

            boolean relevant = node.has("relevant") && node.get("relevant").asBoolean();
            float confidence = node.has("confidence")
                    ? (float) node.get("confidence").asDouble() : 0.5f;
            String reasoning = node.has("reasoning")
                    ? node.get("reasoning").asText() : "No reasoning provided";

            return new JudgeVerdict(relevant, confidence, reasoning, query, resultCount, latencyMs);

        } catch (Exception e) {
            log.debug("Failed to parse JSON verdict '{}': {}", jsonStr, e.getMessage());
            return null;
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "<null>";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
