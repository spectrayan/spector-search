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
package com.spectrayan.spector.memory.middleware;

import com.spectrayan.spector.memory.CognitiveResult;
import com.spectrayan.spector.memory.RecallOptions;
import com.spectrayan.spector.memory.SpectorMemory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Implicit recall proxy — enriches LLM prompts with relevant memories.
 *
 * <h3>The Problem</h3>
 * <p>Standard MCP is tool-call-only — the LLM decides when to call tools.
 * For implicit recall (automatically injecting memories before the LLM
 * sees the prompt), we need a middleware layer.</p>
 *
 * <h3>Architecture</h3>
 * <p>This class sits between the user prompt and the LLM. For each incoming
 * prompt, it:</p>
 * <ol>
 *   <li>Calls {@link SpectorMemory#recall} with the prompt text</li>
 *   <li>Formats top-K memories as a system message prefix</li>
 *   <li>Returns the enriched prompt for the caller to forward to the LLM</li>
 * </ol>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   var proxy = new ImplicitRecallProxy(memory);
 *   String enrichedSystemMessage = proxy.enrichPrompt(userMessage, systemMessage);
 *   // Forward enrichedSystemMessage + userMessage to LLM
 * }</pre>
 *
 * <p>This is optional middleware — not part of the MCP server. Deploy it
 * as an HTTP proxy, middleware layer, or direct integration in your agent.</p>
 */
public final class ImplicitRecallProxy {

    private static final Logger log = LoggerFactory.getLogger(ImplicitRecallProxy.class);

    private static final String MEMORY_HEADER = "\n\n--- Agent Memory Context ---\n";
    private static final String MEMORY_FOOTER = "\n--- End Memory Context ---\n\n";

    private final SpectorMemory memory;
    private final int defaultTopK;
    private final float minImportance;

    /**
     * Creates a proxy with default settings.
     *
     * @param memory the cognitive memory instance
     */
    public ImplicitRecallProxy(SpectorMemory memory) {
        this(memory, 5, 0.1f);
    }

    /**
     * Creates a proxy with custom settings.
     *
     * @param memory        the cognitive memory instance
     * @param defaultTopK   number of memories to inject
     * @param minImportance minimum importance threshold for injection
     */
    public ImplicitRecallProxy(SpectorMemory memory, int defaultTopK, float minImportance) {
        this.memory = memory;
        this.defaultTopK = defaultTopK;
        this.minImportance = minImportance;
    }

    /**
     * Enriches a system message with relevant memories based on the user's prompt.
     *
     * @param userMessage   the user's prompt (used for recall query)
     * @param systemMessage the existing system message (memories are appended)
     * @return enriched system message with memory context
     */
    public String enrichPrompt(String userMessage, String systemMessage) {
        if (userMessage == null || userMessage.isBlank()) return systemMessage;

        List<CognitiveResult> results = memory.recall(userMessage,
                RecallOptions.builder()
                        .topK(defaultTopK)
                        .minImportance(minImportance)
                        .build());

        if (results.isEmpty()) {
            log.debug("No memories found for implicit recall: '{}'",
                    userMessage.substring(0, Math.min(50, userMessage.length())));
            return systemMessage;
        }

        var sb = new StringBuilder(systemMessage != null ? systemMessage : "");
        sb.append(MEMORY_HEADER);

        for (int i = 0; i < results.size(); i++) {
            CognitiveResult r = results.get(i);
            sb.append(i + 1).append(". ");
            sb.append("[").append(r.memoryType()).append("]");
            sb.append(" (confidence=").append(String.format("%.2f", r.ltpAdjustedDecay()));
            sb.append(", source=").append(r.source()).append(") ");
            sb.append(r.text()).append("\n");
        }

        sb.append(MEMORY_FOOTER);

        log.debug("Implicit recall injected {} memories for '{}'",
                results.size(), userMessage.substring(0, Math.min(50, userMessage.length())));

        return sb.toString();
    }

    /**
     * Enriches a prompt without an existing system message.
     */
    public String enrichPrompt(String userMessage) {
        return enrichPrompt(userMessage, "");
    }

    /**
     * Checks if any memories would be recalled for a given prompt.
     * Useful for deciding whether to inject memory context.
     */
    public boolean hasRelevantMemories(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return false;
        return !memory.recall(userMessage,
                RecallOptions.builder().topK(1).minImportance(minImportance).build()).isEmpty();
    }
}
