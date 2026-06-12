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
import com.spectrayan.spector.embed.ollama.OllamaLlmProvider;
import com.spectrayan.spector.embed.ollama.OllamaSparseEncodingProvider;
import com.spectrayan.spector.embed.ollama.OllamaTokenEmbeddingProvider;
import com.spectrayan.spector.memory.DefaultSpectorMemory;
import com.spectrayan.spector.memory.model.MemoryPersistenceMode;
import com.spectrayan.spector.memory.SpectorMemory;
import com.spectrayan.spector.memory.graph.EntityExtractionMode;
import com.spectrayan.spector.memory.e2e.E2ESeedData.SeedMemory;
import com.spectrayan.spector.test.judge.LlmJudgeConfig;
import com.spectrayan.spector.test.judge.LlmTestJudge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Shared singleton context for all E2E test classes.
 *
 * <p>Lazily initializes the {@link SpectorMemory} instance and ingests all seed data
 * exactly <b>once per JVM</b>, regardless of how many test classes are executed.
 * This avoids re-ingesting 90+ memories for every test class while keeping each
 * test class independently runnable.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>Uses {@code synchronized} on the class lock. JUnit 5 test classes call
 * {@link #get()} from their {@code @BeforeAll} methods — all of which run on
 * the same thread in the default sequential executor.</p>
 */
public final class E2EMemoryContext {

    private static final Logger log = LoggerFactory.getLogger(E2EMemoryContext.class);

    /** Embedding model used for all tests. */
    static final String EMBEDDING_MODEL = "qwen3-embedding";

    private static SpectorMemory memory;
    private static OllamaEmbeddingProvider embeddingProvider;
    private static List<SeedMemory> seedMemories;
    private static LlmTestJudge llmJudge;
    private static boolean ingested;

    private E2EMemoryContext() {}

    /**
     * Returns the shared context, initializing it on first call.
     *
     * @return this context (singleton)
     */
    public static synchronized E2EMemoryContext get() {
        if (memory == null) {
            initialize();
        }
        return INSTANCE;
    }

    private static final E2EMemoryContext INSTANCE = new E2EMemoryContext();

    /**
     * Returns the shared SpectorMemory instance.
     */
    public SpectorMemory memory() {
        return memory;
    }

    /**
     * Returns the shared embedding provider.
     */
    public OllamaEmbeddingProvider embeddingProvider() {
        return embeddingProvider;
    }

    /**
     * Returns the loaded seed memories.
     */
    public List<SeedMemory> seedMemories() {
        return seedMemories;
    }

    /**
     * Returns whether ingestion has completed.
     */
    public boolean isIngested() {
        return ingested;
    }

    /**
     * Returns the LLM test judge, or null if LLM judging is not enabled.
     *
     * <p>Enabled via {@code LLM_JUDGE=true} environment variable or
     * {@code -DLLM_JUDGE=true} system property.</p>
     */
    public LlmTestJudge llmJudge() {
        return llmJudge;
    }

    /**
     * Ingests all seed memories into the shared memory instance.
     * Called once, guarded by the {@link #ingested} flag.
     */
    public synchronized void ingestIfNeeded() {
        if (ingested) return;

        log.info("═══ Ingesting {} seed memories ═══", seedMemories.size());
        long start = System.currentTimeMillis();

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (SeedMemory seed : seedMemories) {
            futures.add(memory.remember(
                    seed.id(), seed.text(), seed.type(), seed.source(), seed.tags()));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        long elapsed = System.currentTimeMillis() - start;
        log.info("✅ Ingested {} memories in {}ms ({}ms/memory)",
                seedMemories.size(), elapsed,
                String.format("%.1f", (double) elapsed / seedMemories.size()));

        ingested = true;
    }

    // ── Initialization ──

    private static void initialize() {
        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║  Spector Memory E2E — Initializing shared context           ║");
        log.info("╚══════════════════════════════════════════════════════════════╝");

        // Load seed data from markdown files
        seedMemories = E2ESeedData.loadAll();
        log.info("Loaded {} seed memories", seedMemories.size());

        // Create embedding provider
        embeddingProvider = OllamaEmbeddingProvider.create(EMBEDDING_MODEL);
        int dims = embeddingProvider.dimensions();
        log.info("Embedding model: {} ({}D)", EMBEDDING_MODEL, dims);

        // Create SPLADE + ColBERT providers (reuse the same embedding provider)
        var sparseProvider = new OllamaSparseEncodingProvider(embeddingProvider);
        var tokenProvider = new OllamaTokenEmbeddingProvider(embeddingProvider);

        // Build the memory system with all subsystems enabled
        memory = DefaultSpectorMemory.builder()
                .dimensions(dims)
                .embeddingProvider(embeddingProvider)
                .sparseEncodingProvider(sparseProvider)
                .tokenEmbeddingProvider(tokenProvider)
                .persistenceMode(MemoryPersistenceMode.IN_MEMORY)
                .workingCapacity(50)
                .episodicPartitionCapacity(500)
                .semanticCapacity(200)
                .proceduralCapacity(100)
                .entityExtractionMode(EntityExtractionMode.CUSTOM)
                .entityExtractor(new TestEntityExtractor())
                .entityGraphCapacity(1000)
                .hebbianGraphCapacity(500)
                .temporalChainCapacity(500)
                .surpriseWarmup(10)
                .flashbulbThreshold(2.5)
                .build();

        log.info("SpectorMemory initialized (IN_MEMORY mode, all subsystems enabled)");

        // Initialize LLM judge if enabled
        LlmJudgeConfig judgeConfig = LlmJudgeConfig.fromEnvironment();
        if (judgeConfig.enabled()) {
            try {
                OllamaLlmProvider judgeLlm = OllamaLlmProvider.create(
                        judgeConfig.model(), judgeConfig.baseUrl());
                if (judgeLlm.isAvailable()) {
                    llmJudge = LlmTestJudge.create(judgeLlm);
                    log.info("LLM Judge enabled: model={}, url={}",
                            judgeConfig.model(), judgeConfig.baseUrl());
                } else {
                    log.warn("LLM Judge requested but Ollama is unavailable at {}",
                            judgeConfig.baseUrl());
                }
            } catch (Exception e) {
                log.warn("LLM Judge initialization failed: {}", e.getMessage());
            }
        } else {
            log.info("LLM Judge disabled (set LLM_JUDGE=true to enable)");
        }
    }

    /**
     * Closes the shared memory instance. Called by JVM shutdown hook
     * or explicitly if needed.
     */
    public static synchronized void shutdown() {
        if (memory != null) {
            log.info("E2EMemoryContext shutting down");
            memory.close();
            memory = null;
            ingested = false;
        }
    }

    static {
        // Register shutdown hook to close the memory system
        Runtime.getRuntime().addShutdownHook(new Thread(E2EMemoryContext::shutdown));
    }
}
