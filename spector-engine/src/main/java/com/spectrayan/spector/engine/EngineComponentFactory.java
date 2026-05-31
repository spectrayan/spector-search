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
package com.spectrayan.spector.engine;




import com.spectrayan.spector.storage.VectorStoreFactory;
import com.spectrayan.spector.index.VectorIndexFactory;
import com.spectrayan.spector.config.SpectorConfig;
import com.spectrayan.spector.gpu.GpuBatchSimilarity;
import com.spectrayan.spector.gpu.GpuCapability;
import com.spectrayan.spector.index.BM25Index;
import com.spectrayan.spector.index.DiskHnswIndex;
import com.spectrayan.spector.index.ShardedDiskHnswIndex;
import com.spectrayan.spector.index.KeywordIndex;
import com.spectrayan.spector.index.VectorIndex;
import com.spectrayan.spector.query.ranking.LlmReranker;
import com.spectrayan.spector.query.ranking.Reranker;
import com.spectrayan.spector.storage.DocumentStore;
import com.spectrayan.spector.storage.ShardedIndexFormat;
import com.spectrayan.spector.storage.ShardedMappedVectorStore;
import com.spectrayan.spector.config.PersistenceMode;
import com.spectrayan.spector.storage.VectorStore;
import com.spectrayan.spector.config.PersistenceFiles;

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
    private final PersistenceFiles persistenceFiles;

    public EngineComponentFactory() {
        this(new VectorIndexFactory(), new VectorStoreFactory(), PersistenceFiles.DEFAULTS);
    }

    /** Allows injecting custom factories (for testing). */
    public EngineComponentFactory(VectorIndexFactory indexFactory, VectorStoreFactory storeFactory) {
        this(indexFactory, storeFactory, PersistenceFiles.DEFAULTS);
    }

    /** Full constructor with custom persistence file names. */
    public EngineComponentFactory(VectorIndexFactory indexFactory, VectorStoreFactory storeFactory,
                                   PersistenceFiles persistenceFiles) {
        this.indexFactory = indexFactory;
        this.storeFactory = storeFactory;
        this.persistenceFiles = persistenceFiles;
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

        // ── Create fresh writable components ──
        vs = storeFactory.create(config);
        vi = indexFactory.create(config, vs);
        ki = new BM25Index();

        // ── Load persisted data from disk (if available) ──
        if (config.persistenceMode() == PersistenceMode.DISK) {
            // 1. Load VectorStore ID mappings (restores id→index map + count)
            if (vs instanceof ShardedMappedVectorStore smvs) {
                Path idMappingsFile = persistenceFiles.resolveIdMappings(config.dataDirectory());
                if (Files.exists(idMappingsFile)) {
                    smvs.loadIdMappings(idMappingsFile);
                    log.info("Loaded VectorStore ID mappings: {} entries", smvs.size());
                }
            }

            // 2. Load HNSW graph structure from sharded index
            Path shardDir = persistenceFiles.resolveShardDir(config.dataDirectory());
            Path manifestFile = ShardedIndexFormat.resolveManifest(shardDir);
            if (Files.exists(manifestFile) && vi instanceof com.spectrayan.spector.index.AbstractHnswIndex writable) {
                try {
                    var diskIndex = ShardedDiskHnswIndex.open(shardDir);
                    int nodeCount = diskIndex.size();
                    log.info("Loading {} nodes from sharded disk index into writable HNSW...", nodeCount);

                    for (int i = 0; i < nodeCount; i++) {
                        String id = diskIndex.getId(i);
                        float[] vector = diskIndex.readVector(i);
                        int level = diskIndex.readLevel(i);

                        // Resolve store index from loaded ID mappings
                        int storeIndex = vs.indexOf(id);
                        if (storeIndex < 0) {
                            // Vector not in store yet — add it
                            storeIndex = vs.put(id, vector);
                        }

                        // Collect neighbor arrays
                        int[] layer0 = diskIndex.readNeighbors(i, 0);
                        int[][] upper = null;
                        if (level > 0) {
                            upper = new int[level][];
                            for (int l = 1; l <= level; l++) {
                                upper[l - 1] = diskIndex.readNeighbors(i, l);
                            }
                        }

                        writable.addPrebuilt(id, storeIndex, vector, level, layer0, upper);
                    }

                    // Restore graph state (entry point + max level)
                    if (nodeCount > 0) {
                        writable.restoreGraphState(diskIndex.entryPoint(), diskIndex.maxLevel());
                    }

                    diskIndex.close();
                    log.info("Loaded {} nodes into writable HNSW index from {} shards",
                            nodeCount, diskIndex.shardCount());
                } catch (IOException e) {
                    log.warn("Failed to load sharded disk index, starting fresh: {}", e.getMessage());
                }
            }

            // 2b. Load SpectorIndex structure
            Path specIndexDir = config.dataDirectory().resolve("index_spectrum");
            if (Files.exists(specIndexDir.resolve("meta.properties")) && vi instanceof com.spectrayan.spector.index.spectrum.SpectorIndex) {
                try {
                    vi = com.spectrayan.spector.index.spectrum.SpectorIndex.load(
                            specIndexDir, config.dimensions(),
                            ((com.spectrayan.spector.index.spectrum.SpectorIndex) vi).config(), vs);
                    log.info("Loaded SpectorIndex from disk: {} nodes", vi.size());
                } catch (IOException e) {
                    log.warn("Failed to load SpectorIndex from disk, starting fresh: {}", e.getMessage());
                }
            }

            // 3. Load DocumentStore
            Path docsFile = persistenceFiles.resolveDocuments(config.dataDirectory());
            if (Files.exists(docsFile)) {
                ds = DocumentStore.load(docsFile);
                log.info("Loaded DocumentStore from disk: {} documents", ds.size());
            } else {
                ds = new DocumentStore(config.capacity());
            }
        } else {
            ds = new DocumentStore(config.capacity());
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
