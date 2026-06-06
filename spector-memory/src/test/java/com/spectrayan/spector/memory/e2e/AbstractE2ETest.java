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

import com.spectrayan.spector.embed.ollama.OllamaEmbeddingProvider;
import com.spectrayan.spector.memory.CognitiveResult;
import com.spectrayan.spector.memory.SpectorMemory;
import com.spectrayan.spector.memory.e2e.E2ESeedData.SeedMemory;
import com.spectrayan.spector.test.judge.LlmAssertions;
import com.spectrayan.spector.test.judge.LlmTestJudge;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

/**
 * Base class for all E2E integration tests.
 *
 * <p>Provides shared access to the singleton {@link E2EMemoryContext},
 * common helper methods, and the {@code OLLAMA_LIVE} assumption gate.</p>
 *
 * <p>All concrete E2E test classes should extend this class and use
 * the protected fields {@code memory}, {@code embeddingProvider}, and
 * {@code seedMemories}.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractE2ETest {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected SpectorMemory memory;
    protected OllamaEmbeddingProvider embeddingProvider;
    protected List<SeedMemory> seedMemories;

    @BeforeAll
    void initContext() {
        boolean ollamaLive = "true".equalsIgnoreCase(System.getenv("OLLAMA_LIVE"))
                || "true".equalsIgnoreCase(System.getProperty("OLLAMA_LIVE"));
        Assumptions.assumeTrue(ollamaLive,
                "Skipping E2E test — set OLLAMA_LIVE=true (env or -DOLLAMA_LIVE=true) to run");

        E2EMemoryContext ctx = E2EMemoryContext.get();
        this.memory = ctx.memory();
        this.embeddingProvider = ctx.embeddingProvider();
        this.seedMemories = ctx.seedMemories();

        // Ensure seed data is ingested
        ctx.ingestIfNeeded();
    }

    // ══════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ══════════════════════════════════════════════════════════════

    /**
     * Prints recall results in a formatted table for diagnostics.
     */
    protected void printResults(List<CognitiveResult> results) {
        for (int i = 0; i < results.size(); i++) {
            CognitiveResult r = results.get(i);
            log.info("  [{}] {} | {} | {} | val={} | rc={} | tags={} | {}",
                    i + 1, r.score(), r.id(), r.memoryType(), r.valence(),
                    r.agentRecallCount(),
                    r.synapticTags() != null ? Arrays.toString(r.synapticTags()) : "[]",
                    truncate(r.text(), 60));
        }
    }

    /**
     * Truncates a string to the specified max length, adding ellipsis if needed.
     */
    protected static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "…";
    }

    /**
     * Returns the IDs from a list of cognitive results.
     */
    protected static List<String> idsOf(List<CognitiveResult> results) {
        return results.stream().map(CognitiveResult::id).toList();
    }

    // ══════════════════════════════════════════════════════════════
    // LLM JUDGE HELPERS
    // ══════════════════════════════════════════════════════════════

    /**
     * Returns the LLM test judge, or null if not enabled.
     */
    protected LlmTestJudge llmJudge() {
        return E2EMemoryContext.get().llmJudge();
    }

    /**
     * Returns whether LLM judging is enabled.
     */
    protected boolean isLlmJudgeEnabled() {
        return llmJudge() != null;
    }

    /**
     * Creates a fluent LLM assertion for recall results.
     * Returns null if the judge is not enabled — callers should check
     * {@link #isLlmJudgeEnabled()} first.
     *
     * @param query   the recall query
     * @param results the recall results
     * @return fluent assertion, or null if judge is disabled
     */
    protected LlmAssertions.LlmRecallAssert<CognitiveResult> llmAssertRecall(
            String query, List<CognitiveResult> results) {
        if (!isLlmJudgeEnabled()) return null;
        return LlmAssertions.assertRecall(llmJudge(), query, results, CognitiveResult::text);
    }
}
