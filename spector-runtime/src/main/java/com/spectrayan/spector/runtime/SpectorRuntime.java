package com.spectrayan.spector.runtime;

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.commons.config.SpectorConfigFactory;
import com.spectrayan.spector.commons.config.SpectorProperties;
import com.spectrayan.spector.embed.EmbeddingProvider;
import com.spectrayan.spector.engine.SpectorConfig;
import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.memory.MemoryPersistenceMode;
import com.spectrayan.spector.memory.SpectorMemory;

/**
 * Unified application context for a running Spector instance.
 *
 * <p>Composes the two core subsystems:</p>
 * <ul>
 *   <li>{@link SpectorEngine} — vector search, ingestion, RAG (always created)</li>
 *   <li>{@link SpectorMemory} — cognitive memory with biological mechanisms
 *       (opt-in via {@code spector.memory.enabled: true})</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   SpectorProperties props = SpectorProperties.builder()
 *       .configFile(Path.of("spector.yml"))
 *       .build();
 *   EmbeddingProvider embedder = createEmbedder(props);
 *
 *   try (SpectorRuntime runtime = SpectorRuntime.from(props, embedder)) {
 *       // Search
 *       runtime.engine().ingest("doc1", "title", "content");
 *       var results = runtime.engine().search("query", 10);
 *
 *       // Cognitive memory (if enabled)
 *       if (runtime.hasMemory()) {
 *           runtime.memory().remember("pref", "User likes dark mode",
 *               MemoryType.SEMANTIC, "preferences").join();
 *       }
 *   }
 * }</pre>
 *
 * <p>Both subsystems share the same {@link EmbeddingProvider}, ensuring
 * consistent vector dimensions across search and memory.</p>
 */
public final class SpectorRuntime implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SpectorRuntime.class);

    private final SpectorEngine engine;
    private final SpectorMemory memory;  // nullable
    private final SpectorProperties properties;

    private SpectorRuntime(SpectorEngine engine, SpectorMemory memory, SpectorProperties properties) {
        this.engine = engine;
        this.memory = memory;
        this.properties = properties;
    }

    // ─────────────── Factory ───────────────

    /**
     * Creates a runtime from hierarchical properties and an embedding provider.
     *
     * <p>Always creates a {@link SpectorEngine}. Creates a {@link SpectorMemory}
     * only if {@code spector.memory.enabled} is {@code true}.</p>
     *
     * @param props    hierarchical configuration
     * @param embedder embedding provider (shared by engine and memory)
     * @return initialized runtime (caller must close)
     */
    public static SpectorRuntime from(SpectorProperties props, EmbeddingProvider embedder) {
        // ── Engine ──
        SpectorConfig engineConfig = SpectorConfig.from(props);
        SpectorEngine engine = new SpectorEngine(engineConfig, embedder);
        log.info("[Runtime] Engine initialized: dims={}, index={}, persistence={}",
                engineConfig.dimensions(), engineConfig.indexType(), engineConfig.persistenceMode());

        // ── Memory (opt-in) ──
        SpectorMemory memory = null;
        var memoryConfig = SpectorConfigFactory.memoryDefaults(props);

        if (memoryConfig.enabled()) {
            memory = SpectorMemory.builder()
                    .dimensions(engineConfig.dimensions()) // share dimensions with engine
                    .embeddingProvider(embedder)            // share embedder
                    .persistenceMode(MemoryPersistenceMode.valueOf(memoryConfig.persistenceMode()))
                    .persistence(memoryConfig.persistencePath())
                    .semanticCapacity(memoryConfig.capacity())
                    .build();
            log.info("[Runtime] Cognitive memory enabled: persistence={}, path={}",
                    memoryConfig.persistenceMode(), memoryConfig.persistencePath());
        } else {
            log.info("[Runtime] Cognitive memory disabled (set spector.memory.enabled=true to enable)");
        }

        return new SpectorRuntime(engine, memory, props);
    }

    /**
     * Creates a runtime with engine only (no memory).
     *
     * @param engine the search engine
     * @param props  configuration properties
     * @return runtime wrapping the engine
     */
    public static SpectorRuntime engineOnly(SpectorEngine engine, SpectorProperties props) {
        return new SpectorRuntime(engine, null, props);
    }

    // ─────────────── Accessors ───────────────

    /** Returns the search engine (never null). */
    public SpectorEngine engine() { return engine; }

    /** Returns the cognitive memory, or {@code null} if not enabled. */
    public SpectorMemory memory() { return memory; }

    /** Returns {@code true} if cognitive memory is available. */
    public boolean hasMemory() { return memory != null; }

    /** Returns the configuration properties used to create this runtime. */
    public SpectorProperties properties() { return properties; }

    // ─────────────── Lifecycle ───────────────

    @Override
    public void close() {
        try {
            engine.close();
        } catch (Exception e) {
            log.error("[Runtime] Error closing engine: {}", e.getMessage(), e);
        }
        if (memory != null) {
            try {
                memory.close();
            } catch (Exception e) {
                log.error("[Runtime] Error closing memory: {}", e.getMessage(), e);
            }
        }
        log.info("[Runtime] Shutdown complete");
    }
}
