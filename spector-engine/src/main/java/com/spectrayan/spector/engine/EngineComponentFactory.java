package com.spectrayan.spector.engine;

import com.spectrayan.spector.gpu.GpuBatchSimilarity;
import com.spectrayan.spector.gpu.GpuCapability;
import com.spectrayan.spector.index.BM25Index;
import com.spectrayan.spector.index.DiskHnswIndex;
import com.spectrayan.spector.index.KeywordIndex;
import com.spectrayan.spector.index.VectorIndex;
import com.spectrayan.spector.query.ranking.LlmReranker;
import com.spectrayan.spector.query.ranking.Reranker;
import com.spectrayan.spector.storage.DocumentStore;
import com.spectrayan.spector.storage.InMemoryVectorStore;
import com.spectrayan.spector.storage.PersistenceMode;
import com.spectrayan.spector.storage.VectorStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Abstract Factory that assembles a consistent family of engine components.
 *
 * <p>Replaces the ~150-line procedural constructor in {@link SpectorEngine}
 * with a focused, testable factory. Each subsystem (index, store, GPU,
 * reranker) is created by a dedicated method that can be overridden in
 * subclasses for testing or custom configurations.</p>
 *
 * <h3>Component Creation Order</h3>
 * <ol>
 *   <li>Attempt disk index load (if persistence=DISK and file exists)</li>
 *   <li>Create vector store (via {@link VectorStoreFactory})</li>
 *   <li>Create document store</li>
 *   <li>Create vector index (via {@link VectorIndexFactory})</li>
 *   <li>Create keyword index (BM25)</li>
 *   <li>Create GPU batch similarity (optional, graceful fallback)</li>
 *   <li>Create LLM reranker (optional)</li>
 * </ol>
 */
public class EngineComponentFactory {

    private static final Logger log = LoggerFactory.getLogger(EngineComponentFactory.class);

    private final VectorIndexFactory indexFactory;
    private final VectorStoreFactory storeFactory;

    public EngineComponentFactory() {
        this(new VectorIndexFactory(), new VectorStoreFactory());
    }

    /** Allows injecting custom factories (for testing). */
    public EngineComponentFactory(VectorIndexFactory indexFactory, VectorStoreFactory storeFactory) {
        this.indexFactory = indexFactory;
        this.storeFactory = storeFactory;
    }

    /**
     * Assembles all engine components from the given configuration.
     *
     * @param config the engine configuration
     * @return fully assembled component bag
     */
    public EngineComponents create(SpectorConfig config) {
        VectorStore vs;
        DocumentStore ds;
        VectorIndex vi;
        KeywordIndex ki;
        boolean loadedFromDisk = false;

        // ── Try loading from disk ──
        if (config.persistenceMode() == PersistenceMode.DISK) {
            Path indexFile = config.dataDirectory().resolve("index.spct");
            if (Files.exists(indexFile)) {
                try {
                    log.info("Loading existing disk index from {}", indexFile);
                    var diskIndex = DiskHnswIndex.open(indexFile);
                    vs = new InMemoryVectorStore(config.dimensions(), config.capacity());
                    ds = new DocumentStore(config.capacity());
                    vi = diskIndex;
                    ki = new BM25Index();
                    loadedFromDisk = true;
                    log.info("Loaded disk index: {} vectors", diskIndex.size());
                } catch (IOException e) {
                    log.warn("Failed to load disk index, creating fresh: {}", e.getMessage());
                    vs = null; ds = null; vi = null; ki = null;
                }
            } else {
                vs = null; ds = null; vi = null; ki = null;
            }
        } else {
            vs = null; ds = null; vi = null; ki = null;
        }

        // ── Build fresh components if not loaded from disk ──
        if (!loadedFromDisk) {
            vs = storeFactory.create(config);
            ds = new DocumentStore(config.capacity());
            vi = indexFactory.create(config);
            ki = new BM25Index();
        }

        // ── GPU acceleration (optional, graceful fallback) ──
        GpuBatchSimilarity gpu = createGpu(config);

        // ── LLM Reranker (optional) ──
        Reranker reranker = createReranker(config);

        return new EngineComponents(vs, ds, vi, ki, reranker, gpu);
    }

    /**
     * Creates the GPU batch similarity module if requested and available.
     */
    protected GpuBatchSimilarity createGpu(SpectorConfig config) {
        if (!config.gpuEnabled()) return null;

        try {
            if (GpuCapability.isAvailable()) {
                GpuBatchSimilarity gpu = new GpuBatchSimilarity();
                log.info("GPU acceleration enabled: {}", GpuCapability.detect().report());
                return gpu;
            } else {
                log.info("GPU requested but not available — falling back to CPU SIMD. {}",
                        GpuCapability.detect().report());
            }
        } catch (Exception e) {
            log.warn("GPU initialization failed — falling back to CPU SIMD: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Creates the LLM reranker if enabled.
     */
    protected Reranker createReranker(SpectorConfig config) {
        if (!config.rerankerEnabled()) return null;

        try {
            Reranker rr = new LlmReranker(
                    config.rerankerOllamaUrl(),
                    config.rerankerModel(),
                    config.rerankerMaxCandidates());
            log.info("LLM re-ranker enabled: model={}, maxCandidates={}",
                    config.rerankerModel(), config.rerankerMaxCandidates());
            return rr;
        } catch (Exception e) {
            log.warn("LLM re-ranker initialization failed: {}", e.getMessage());
            return null;
        }
    }
}
