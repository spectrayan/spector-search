package com.spectrayan.spector.engine;

import com.spectrayan.spector.config.IndexType;
import com.spectrayan.spector.config.PersistenceFiles;
import com.spectrayan.spector.config.PersistenceMode;
import com.spectrayan.spector.config.SpectorConfig;
import com.spectrayan.spector.core.simd.SimdCapability;
import com.spectrayan.spector.core.similarity.SimilarityFunction;
import com.spectrayan.spector.embed.EmbeddingProvider;
import com.spectrayan.spector.gpu.GpuBatchSimilarity;
import com.spectrayan.spector.index.VectorIndex;
import com.spectrayan.spector.index.DiskHnswWriter;
import com.spectrayan.spector.index.HnswIndex;
import com.spectrayan.spector.query.HybridSearchOrchestrator;
import com.spectrayan.spector.index.KeywordIndex;
import com.spectrayan.spector.query.SearchQuery;
import com.spectrayan.spector.query.SearchResponse;
import com.spectrayan.spector.query.ranking.Reranker;
import com.spectrayan.spector.storage.DocumentStore;
import com.spectrayan.spector.storage.VectorStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Unified entry-point for the Spector Search engine.
 *
 * <p>Manages the lifecycle of all underlying components: vector store,
 * document store, HNSW index, BM25 index, hybrid query orchestrator,
 * optional GPU acceleration, and optional LLM re-ranking.
 * Provides a simple API for document ingestion and search.</p>
 *
 * <p>Delegates to {@link EngineIngestion} for ingestion and
 * {@link EngineSearch} for search operations.</p>
 *
 * <h3>Construction</h3>
 * <p>Use the fluent {@link Builder} for clean engine construction:</p>
 * <pre>{@code
 *   SpectorEngine engine = SpectorEngine.builder()
 *       .dimensions(384)
 *       .capacity(100_000)
 *       .similarity(SimilarityFunction.COSINE)
 *       .gpu(true)
 *       .reranker("http://localhost:11434", "llama3.2")
 *       .embeddingProvider(myProvider)
 *       .build();
 * }</pre>
 *
 * <h3>Design Patterns</h3>
 * <ul>
 *   <li><b>Facade</b> — unified API over 6+ subsystems</li>
 *   <li><b>Builder</b> — fluent construction via {@link Builder}</li>
 *   <li><b>Abstract Factory</b> — component assembly via {@link EngineComponentFactory}</li>
 *   <li><b>Delegation</b> — ingestion → {@link EngineIngestion}, search → {@link EngineSearch}</li>
 * </ul>
 */
public class SpectorEngine implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SpectorEngine.class);

    private final SpectorConfig config;
    private final VectorStore vectorStore;
    private final DocumentStore documentStore;
    private final VectorIndex vectorIndex;
    private final KeywordIndex keywordIndex;
    private final EmbeddingProvider embeddingProvider; // nullable
    private final Reranker reranker; // nullable
    private final GpuBatchSimilarity gpuBatchSimilarity; // nullable
    private final PersistenceFiles persistenceFiles;
    private volatile boolean closed;

    // Delegates
    private final EngineIngestion ingestion;
    private final EngineSearch search;

    // ─────────────── Construction ───────────────

    /**
     * Creates and initializes a new engine with the given configuration.
     *
     * @param config the engine configuration
     */
    public SpectorEngine(SpectorConfig config) {
        this(config, null);
    }

    /**
     * Creates an engine with configuration and an embedding provider.
     *
     * @param config   the engine configuration
     * @param provider the embedding provider (nullable)
     */
    public SpectorEngine(SpectorConfig config, EmbeddingProvider provider) {
        this(config, provider, new EngineComponentFactory());
    }

    /**
     * Creates an engine with a custom component factory (for testing/extensibility).
     *
     * @param config   the engine configuration
     * @param provider the embedding provider (nullable)
     * @param factory  component factory for assembling subsystems
     */
    public SpectorEngine(SpectorConfig config, EmbeddingProvider provider,
                         EngineComponentFactory factory) {
        this.config = config;
        this.embeddingProvider = provider;
        this.persistenceFiles = PersistenceFiles.DEFAULTS;
        this.closed = false;

        log.info("Initializing SpectorEngine: dims={}, capacity={}, similarity={}, " +
                        "quantization={}, persistence={}, indexType={}, embedding={}, " +
                        "gpu={}, reranker={}, {}",
                config.dimensions(), config.capacity(), config.similarityFunction(),
                config.quantization(), config.persistenceMode(), config.indexType(),
                provider != null ? provider.modelName() : "none",
                config.gpuEnabled() ? "enabled" : "disabled",
                config.rerankerEnabled() ? config.rerankerModel() : "disabled",
                SimdCapability.report());

        // ── Assemble components via Abstract Factory ──
        EngineComponents components = factory.create(config);

        this.vectorStore = components.vectorStore();
        this.documentStore = components.documentStore();
        this.vectorIndex = components.vectorIndex();
        this.keywordIndex = components.keywordIndex();
        this.reranker = components.reranker();
        this.gpuBatchSimilarity = components.gpuBatch() instanceof GpuBatchSimilarity gpu
                ? gpu : null;

        // ── Wire orchestrator with optional re-ranker ──
        var orchestrator = new HybridSearchOrchestrator(
                keywordIndex, vectorIndex, reranker, documentStore);

        // ── Create delegates ──
        this.ingestion = new EngineIngestion(config, vectorStore, documentStore,
                vectorIndex, keywordIndex, embeddingProvider);
        this.search = new EngineSearch(config, orchestrator, embeddingProvider, gpuBatchSimilarity);

        log.info("SpectorEngine initialized successfully");
    }

    /** Creates an engine with default configuration. */
    public SpectorEngine() {
        this(SpectorConfig.DEFAULT);
    }

    /** Returns a new fluent {@link Builder} for constructing an engine. */
    public static Builder builder() {
        return new Builder();
    }

    // ─────────────── Ingestion (delegated) ───────────────

    /** Ingests a single document with its text content and vector embedding. */
    public void ingest(String id, String content, float[] vector) {
        ensureOpen();
        ingestion.ingest(id, content, vector);
    }

    /** Ingests a document with title, content, and vector. */
    public void ingest(String id, String title, String content, float[] vector) {
        ensureOpen();
        ingestion.ingest(id, title, content, vector);
    }

    /** Ingests a batch of documents. */
    public void ingestBatch(String[] ids, String[] contents, float[][] vectors) {
        ensureOpen();
        ingestion.ingestBatch(ids, contents, vectors);
    }

    /** Deletes a document by ID from all indexes. */
    public boolean delete(String id) {
        ensureOpen();
        return ingestion.delete(id);
    }

    /** Ingests a large document by splitting it into overlapping chunks. */
    public int ingestChunked(String id, String content,
                             java.util.function.Function<String, float[]> vectorProvider) {
        ensureOpen();
        return ingestion.ingestChunked(id, content, vectorProvider);
    }

    /** Ingests a large document with a custom chunker configuration. */
    public int ingestChunked(String id, String content,
                             java.util.function.Function<String, float[]> vectorProvider,
                             com.spectrayan.spector.commons.TextChunker chunker) {
        ensureOpen();
        return ingestion.ingestChunked(id, content, vectorProvider, chunker);
    }

    /** Ingests structured content (XML, JSON, Java objects) by extracting text. */
    public void ingestStructured(String id, String content, float[] vector) {
        ensureOpen();
        ingestion.ingestStructured(id, content, vector);
    }

    /** Ingests a large file using streaming chunking with bounded memory. */
    public int ingestFile(java.nio.file.Path path, String documentId,
                          java.util.function.Function<String, float[]> vectorProvider,
                          int chunkSize, int overlap) throws java.io.IOException {
        ensureOpen();
        return ingestion.ingestFile(path, documentId, vectorProvider, chunkSize, overlap);
    }

    /** Ingests a large document using token-level chunking. */
    public int ingestTokenChunked(String id, String content,
                                  java.util.function.Function<String, float[]> vectorProvider,
                                  int maxTokens, int overlapTokens) {
        ensureOpen();
        return ingestion.ingestTokenChunked(id, content, vectorProvider, maxTokens, overlapTokens);
    }

    /** Ingests a document with automatic embedding generation. */
    public void ingest(String id, String content) {
        ensureOpen();
        ingestion.ingest(id, content);
    }

    /** Ingests a document with title and automatic embedding. */
    public void ingest(String id, String title, String content) {
        ensureOpen();
        ingestion.ingest(id, title, content);
    }

    /** Auto-embed chunked ingestion for large documents. */
    public int ingestChunkedAuto(String id, String content) {
        ensureOpen();
        return ingestion.ingestChunkedAuto(id, content);
    }

    /** Auto-embed file ingestion with streaming. */
    public int ingestFileAuto(java.nio.file.Path path, String documentId,
                              int chunkSize, int overlap) throws java.io.IOException {
        ensureOpen();
        return ingestion.ingestFileAuto(path, documentId, chunkSize, overlap);
    }

    // ─────────────── Search (delegated) ───────────────

    /** Executes a search query. */
    public SearchResponse search(SearchQuery query) {
        ensureOpen();
        return search.search(query);
    }

    /** Convenience: keyword search. */
    public SearchResponse keywordSearch(String text, int topK) {
        ensureOpen();
        return search.keywordSearch(text, topK);
    }

    /** Convenience: vector search. */
    public SearchResponse vectorSearch(float[] vector, int topK) {
        ensureOpen();
        return search.vectorSearch(vector, topK);
    }

    /** Convenience: hybrid search. */
    public SearchResponse hybridSearch(String text, float[] vector, int topK) {
        ensureOpen();
        return search.hybridSearch(text, vector, topK);
    }

    /** Auto-embed search: embeds the query text and performs hybrid search. */
    public SearchResponse search(String text, int topK) {
        ensureOpen();
        return search.search(text, topK);
    }

    // ─────────────── GPU-Accelerated Batch Operations ───────────────

    /** Computes batch cosine similarities using GPU if available, CPU SIMD otherwise. */
    public float[] batchCosineSimilarity(float[] query, float[] database, int n, int dims) {
        ensureOpen();
        return search.batchCosineSimilarity(query, database, n, dims);
    }

    /** Returns whether GPU acceleration is active. */
    public boolean isGpuActive() {
        return search.isGpuActive();
    }

    // ─────────────── Accessors ───────────────

    /** Returns the engine configuration. */
    public SpectorConfig config() { return config; }

    /** Returns the number of indexed documents. */
    public int documentCount() { return vectorStore.size(); }

    /** Returns the document store. */
    public DocumentStore documentStore() { return documentStore; }

    /** Returns the vector store. */
    public VectorStore vectorStore() { return vectorStore; }

    /** Returns the underlying vector index (for ANN pre-filtering by Memory). */
    public VectorIndex index() { return vectorIndex; }

    /** Returns the embedding provider, or null if none configured. */
    public EmbeddingProvider embeddingProvider() { return embeddingProvider; }

    /** Returns true if an embedding provider is configured. */
    public boolean hasEmbeddingProvider() { return embeddingProvider != null; }

    /** Returns the active re-ranker, or null if none configured. */
    public Reranker reranker() { return reranker; }

    /** Returns true if LLM re-ranking is active. */
    public boolean isRerankerActive() { return reranker != null; }

    // ─────────────── Lifecycle ───────────────

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            try {
                // Persist to disk if configured
                if (config.persistenceMode() == PersistenceMode.DISK) {
                    // HNSW index
                    if (vectorIndex instanceof HnswIndex hnswIdx && hnswIdx.size() > 0) {
                        try {
                            Path indexFile = persistenceFiles.resolveIndex(config.dataDirectory());
                            DiskHnswWriter.write(hnswIdx, indexFile);
                            log.info("HNSW index persisted to {}", indexFile);
                        } catch (IOException e) {
                            log.error("Failed to persist HNSW index to disk", e);
                        }
                    }

                    // Document store
                    try {
                        Path docsFile = persistenceFiles.resolveDocuments(config.dataDirectory());
                        documentStore.save(docsFile);
                        log.info("DocumentStore persisted to {} ({} docs)", docsFile, documentStore.size());
                    } catch (Exception e) {
                        log.error("Failed to persist DocumentStore to disk", e);
                    }

                    // Vector store ID mappings
                    if (vectorStore instanceof com.spectrayan.spector.storage.MappedVectorStore mvs) {
                        try {
                            Path idFile = persistenceFiles.resolveIdMappings(config.dataDirectory());
                            mvs.saveIdMappings(idFile);
                            log.info("Vector store ID mappings persisted to {}", idFile);
                        } catch (Exception e) {
                            log.error("Failed to persist vector store ID mappings", e);
                        }
                    }
                }

                search.orchestrator().close();
                vectorIndex.close();
                keywordIndex.close();
                vectorStore.close();
                documentStore.close();
                if (embeddingProvider != null) embeddingProvider.close();
                if (gpuBatchSimilarity != null) gpuBatchSimilarity.close();
            } catch (Exception e) {
                log.warn("Error during engine shutdown", e);
            }
            log.info("SpectorEngine closed");
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("SpectorEngine is closed");
    }

    // ═════════════════════════════════════════════════════════════════
    //  Builder Pattern
    // ═════════════════════════════════════════════════════════════════

    /**
     * Fluent builder for constructing {@link SpectorEngine} instances.
     */
    public static final class Builder {

        private SpectorConfig config = SpectorConfig.DEFAULT;
        private EmbeddingProvider embeddingProvider;
        private EngineComponentFactory componentFactory;

        Builder() {}

        public Builder dimensions(int dims) { this.config = config.withDimensions(dims); return this; }
        public Builder capacity(int capacity) { this.config = config.withCapacity(capacity); return this; }
        public Builder similarity(SimilarityFunction sf) { this.config = config.withSimilarityFunction(sf); return this; }
        public Builder quantization(com.spectrayan.spector.core.quantization.QuantizationType qt) { this.config = config.withQuantization(qt); return this; }
        public Builder vasq() { this.config = config.withVasq(); return this; }
        public Builder vasq(int oversamplingFactor) { this.config = config.withVasq(oversamplingFactor); return this; }
        public Builder vasq4() { this.config = config.withVasq4(); return this; }
        public Builder vasq4(int oversamplingFactor) { this.config = config.withVasq4(oversamplingFactor); return this; }
        public Builder persistence(PersistenceMode mode, Path directory) { this.config = config.withPersistence(mode, directory); return this; }
        public Builder ivfPq() { this.config = config.withIvfPq(); return this; }
        public Builder ivfPq(int nlist, int nprobe, int subspaces) { this.config = config.withIvfPq(nlist, nprobe, subspaces); return this; }
        public Builder spectrum() { this.config = config.withSpectrum(); return this; }
        public Builder spectrum(int nCentroids, int nProbe, int shardThreshold) { this.config = config.withSpectrum(nCentroids, nProbe, shardThreshold); return this; }
        public Builder gpu(boolean enabled) { this.config = config.withGpu(enabled); return this; }
        public Builder reranker(String ollamaUrl, String model) { this.config = config.withReranker(ollamaUrl, model); return this; }
        public Builder reranker(String ollamaUrl, String model, int maxCandidates) { this.config = config.withReranker(ollamaUrl, model, maxCandidates); return this; }
        public Builder embeddingProvider(EmbeddingProvider provider) { this.embeddingProvider = provider; return this; }
        public Builder componentFactory(EngineComponentFactory factory) { this.componentFactory = factory; return this; }
        public Builder config(SpectorConfig config) { this.config = config; return this; }
        public SpectorConfig config() { return this.config; }

        /** Builds and returns a fully initialized {@link SpectorEngine}. */
        public SpectorEngine build() {
            EngineComponentFactory factory = componentFactory != null
                    ? componentFactory : new EngineComponentFactory();
            return new SpectorEngine(config, embeddingProvider, factory);
        }
    }
}
