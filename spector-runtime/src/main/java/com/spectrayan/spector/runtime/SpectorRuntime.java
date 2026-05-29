package com.spectrayan.spector.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.config.SpectorConfigFactory;
import com.spectrayan.spector.config.SpectorMode;
import com.spectrayan.spector.config.SpectorProperties;
import com.spectrayan.spector.config.PersistenceMode;
import com.spectrayan.spector.embed.EmbeddingProvider;
import com.spectrayan.spector.config.SpectorConfig;
import com.spectrayan.spector.engine.DefaultSpectorEngine;
import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.memory.DefaultSpectorMemory;
import com.spectrayan.spector.memory.MemoryPersistenceMode;
import com.spectrayan.spector.memory.SpectorMemory;

/**
 * Composition root for a Spector instance.
 *
 * <p><strong>This is the single entry point for all Spector consumers.</strong>
 * It creates, wires, and exposes the subsystem services. No business logic
 * lives here — each service owns its domain.</p>
 *
 * <h3>Services</h3>
 * <ul>
 *   <li>{@link #search()} — mode-aware search (engine or memory)</li>
 *   <li>{@link #ingestion()} — mode-aware ingestion (text, file, directory)</li>
 * </ul>
 *
 * <h3>Direct Subsystem Access</h3>
 * <ul>
 *   <li>{@link #engine()} — vector search engine (always available)</li>
 *   <li>{@link #memory()} — cognitive memory (null if not enabled)</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   try (SpectorRuntime runtime = SpectorRuntime.from(props, embedder)) {
 *       runtime.ingestion().ingest("doc1", "some text");
 *       runtime.ingestion().ingest(Path.of("/docs"), "**\/*.md", 800, 100, ".git");
 *       var results = runtime.search().query("something", 10);
 *   }
 * }</pre>
 */
public final class SpectorRuntime implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SpectorRuntime.class);

    private final SpectorEngine engine;
    private final SpectorMemory memory;  // nullable
    private final SpectorProperties properties;
    private final SpectorMode mode;

    // Lazily created services
    private volatile SearchHandler searchService;
    private volatile IngestionHandler ingestionService;

    private SpectorRuntime(SpectorEngine engine, SpectorMemory memory,
                           SpectorProperties properties, SpectorMode mode) {
        this.engine = engine;
        this.memory = memory;
        this.properties = properties;
        this.mode = mode;
    }

    // ─────────────── Factory ───────────────

    /**
     * Creates a runtime from configuration and embedding provider.
     *
     * @param props    hierarchical configuration
     * @param embedder embedding provider (shared by engine and memory)
     * @return initialized runtime (caller must close)
     */
    public static SpectorRuntime from(SpectorProperties props, EmbeddingProvider embedder) {
        SpectorMode mode = SpectorConfigFactory.mode(props);

        // ── Read memory config early (needed to configure engine in MEMORY mode) ──
        var memoryConfig = SpectorConfigFactory.memoryDefaults(props);
        boolean memoryEnabled = memoryConfig.enabled() || mode == SpectorMode.MEMORY;

        // ── Engine ──
        SpectorConfig engineConfig = SpectorConfig.from(props);
        // In MEMORY mode the engine provides the shared HNSW index for semantic
        // recall. Use DISK persistence so the HNSW graph + VectorStore survive
        // restarts. Point engine data to .spector/index (sibling of memory path).
        // Use the memory config's capacity so the HNSW can hold all semantic vectors.
        if (mode == SpectorMode.MEMORY && memoryEnabled) {
            java.nio.file.Path indexDir = memoryConfig.persistencePath()
                    .resolveSibling("index");
            engineConfig = engineConfig
                    .withPersistence(PersistenceMode.DISK, indexDir)
                    .withCapacity(memoryConfig.capacity());
        }
        SpectorEngine engine = new DefaultSpectorEngine(engineConfig, embedder);
        log.info("[Runtime] Engine: dims={}, index={}, persistence={}, dataDir={}, mode={}",
                engineConfig.dimensions(), engineConfig.indexType(),
                engineConfig.persistenceMode(), engineConfig.dataDirectory(),
                mode);

        // ── Memory (opt-in or auto-enabled in MEMORY mode) ──
        SpectorMemory memory = null;

        if (memoryEnabled) {
            var memoryBuilder = DefaultSpectorMemory.builder()
                    .dimensions(engineConfig.dimensions())
                    .embeddingProvider(embedder)
                    .persistenceMode(MemoryPersistenceMode.valueOf(memoryConfig.persistenceMode()))
                    .persistence(memoryConfig.persistencePath())
                    .semanticCapacity(memoryConfig.capacity());

            if (mode == SpectorMode.MEMORY) {
                memoryBuilder.semanticIndex(engine.index());
                memoryBuilder.vectorStore(engine.vectorStore());
            }

            memory = memoryBuilder.build();
            log.info("[Runtime] Memory: persistence={}, path={}",
                    memoryConfig.persistenceMode(), memoryConfig.persistencePath());
        }

        return new SpectorRuntime(engine, memory, props, mode);
    }

    /**
     * Creates a runtime with engine only (no memory).
     */
    public static SpectorRuntime engineOnly(SpectorEngine engine, SpectorProperties props) {
        return new SpectorRuntime(engine, null, props, SpectorMode.SEARCH);
    }

    // ─────────────── Service Accessors ───────────────

    /** Returns the mode-aware search service. */
    public SearchHandler search() {
        if (searchService == null) {
            searchService = new SearchHandler(engine, memory, mode);
        }
        return searchService;
    }

    /** Returns the mode-aware ingestion service. */
    public IngestionHandler ingestion() {
        if (ingestionService == null) {
            var ingestionConfig = SpectorConfigFactory.ingestionDefaults(properties);

            // Select target based on mode
            com.spectrayan.spector.ingestion.IngestionTarget target =
                    (mode == SpectorMode.MEMORY && memory != null)
                    ? memory.target()
                    : engine.target();

            // Build unified pipeline from config
            var pipeline = com.spectrayan.spector.ingestion.IngestionPipeline.builder()
                    .target(target)
                    .embeddingProvider(engine.embeddingProvider())
                    .chunking(new com.spectrayan.spector.commons.TextChunker(
                            ingestionConfig.chunkSize(), ingestionConfig.chunkOverlap()))
                    .chunkThreshold(ingestionConfig.chunkSize())
                    .build();

            ingestionService = new IngestionHandler(pipeline, engine, memory, mode);
        }
        return ingestionService;
    }

    // ─────────────── Direct Subsystem Access ───────────────

    /** Returns the search engine (never null). */
    public SpectorEngine engine() { return engine; }

    /** Returns the cognitive memory, or {@code null} if not enabled. */
    public SpectorMemory memory() { return memory; }

    /** Returns {@code true} if cognitive memory is available. */
    public boolean hasMemory() { return memory != null; }

    /** Returns the configuration properties. */
    public SpectorProperties properties() { return properties; }

    /** Returns the global operating mode. */
    public SpectorMode mode() { return mode; }

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
        log.info("[Runtime] Shutdown complete (mode={})", mode);
    }
}
