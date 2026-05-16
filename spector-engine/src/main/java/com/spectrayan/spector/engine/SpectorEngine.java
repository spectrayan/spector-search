package com.spectrayan.spector.engine;

import com.spectrayan.spector.commons.ContentExtractor;
import com.spectrayan.spector.commons.StreamingChunker;
import com.spectrayan.spector.commons.TextChunker;
import com.spectrayan.spector.commons.TokenChunker;
import com.spectrayan.spector.core.SimilarityFunction;
import com.spectrayan.spector.core.SimdCapability;
import com.spectrayan.spector.embed.EmbeddingProvider;
import com.spectrayan.spector.gpu.GpuBatchSimilarity;
import com.spectrayan.spector.index.BM25Index;
import com.spectrayan.spector.index.DiskHnswWriter;
import com.spectrayan.spector.index.HnswIndex;
import com.spectrayan.spector.index.KeywordIndex;
import com.spectrayan.spector.index.ScoredResult;
import com.spectrayan.spector.index.VectorIndex;
import com.spectrayan.spector.index.ivf.IvfPqIndex;
import com.spectrayan.spector.query.HybridSearchOrchestrator;
import com.spectrayan.spector.query.SearchQuery;
import com.spectrayan.spector.query.SearchResponse;
import com.spectrayan.spector.query.ranking.Reranker;
import com.spectrayan.spector.storage.Document;
import com.spectrayan.spector.storage.DocumentStore;
import com.spectrayan.spector.storage.PersistenceMode;
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
 * <h3>Legacy Construction</h3>
 * <pre>{@code
 *   try (var engine = new SpectorEngine(config)) {
 *       engine.ingest("doc-1", "Hello world", embedding);
 *       SearchResponse response = engine.search(
 *           SearchQuery.hybrid("hello", queryEmbedding, 10));
 *   }
 * }</pre>
 *
 * <h3>Design Patterns</h3>
 * <ul>
 *   <li><b>Facade</b> — unified API over 6+ subsystems</li>
 *   <li><b>Builder</b> — fluent construction via {@link Builder}</li>
 *   <li><b>Abstract Factory</b> — component assembly via {@link EngineComponentFactory}</li>
 *   <li><b>Factory Method</b> — index/store creation via {@link VectorIndexFactory}/{@link VectorStoreFactory}</li>
 * </ul>
 */
public class SpectorEngine implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SpectorEngine.class);

    private final SpectorConfig config;
    private final VectorStore vectorStore;
    private final DocumentStore documentStore;
    private final VectorIndex vectorIndex;
    private final KeywordIndex keywordIndex;
    private final HybridSearchOrchestrator orchestrator;
    private final EmbeddingProvider embeddingProvider; // nullable
    private final GpuBatchSimilarity gpuBatchSimilarity; // nullable
    private final Reranker reranker; // nullable
    private volatile boolean closed;

    // IVF-PQ training state — buffers vectors until enough for training
    private java.util.List<float[]> ivfTrainingBuffer;
    private java.util.List<String> ivfTrainingIds;
    private java.util.List<String> ivfTrainingContents;
    private volatile boolean ivfTrained;

    // ─────────────── Construction ───────────────

    /**
     * Creates and initializes a new engine with the given configuration.
     *
     * <p>Components are assembled by {@link EngineComponentFactory} which
     * uses {@link VectorIndexFactory} and {@link VectorStoreFactory} to
     * create the appropriate implementations based on configuration.</p>
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
        this.closed = false;
        this.ivfTrained = false;

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

        // ── IVF-PQ training buffer initialization ──
        if (config.indexType() == IndexType.IVF_PQ) {
            int minTrainingSamples = Math.max(config.effectiveNlist() * 40, 256);
            this.ivfTrainingBuffer = new java.util.ArrayList<>(minTrainingSamples);
            this.ivfTrainingIds = new java.util.ArrayList<>(minTrainingSamples);
            this.ivfTrainingContents = new java.util.ArrayList<>(minTrainingSamples);
            log.info("IVF-PQ index created (untrained). Will auto-train after {} vectors.",
                    minTrainingSamples);
        }

        // ── Wire orchestrator with optional re-ranker ──
        this.orchestrator = new HybridSearchOrchestrator(
                keywordIndex, vectorIndex, reranker, documentStore);

        log.info("SpectorEngine initialized successfully");
    }

    /** Creates an engine with default configuration. */
    public SpectorEngine() {
        this(SpectorConfig.DEFAULT);
    }

    /**
     * Returns a new fluent {@link Builder} for constructing an engine.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    // ─────────────── Ingestion ───────────────

    /**
     * Ingests a single document with its text content and vector embedding.
     *
     * @param id       unique document identifier
     * @param content  text content for keyword search
     * @param vector   embedding vector for semantic search
     */
    public void ingest(String id, String content, float[] vector) {
        ensureOpen();

        // IVF-PQ auto-training: buffer vectors until we have enough to train
        if (config.indexType() == IndexType.IVF_PQ && !ivfTrained) {
            ivfTrainingBuffer.add(vector.clone());
            ivfTrainingIds.add(id);
            ivfTrainingContents.add(content);

            int minSamples = Math.max(config.effectiveNlist() * 40, 256);
            if (ivfTrainingBuffer.size() >= minSamples) {
                trainAndFlushIvfPq();
            } else {
                // Still buffering — store document metadata for keyword search
                documentStore.put(Document.of(id, content));
                keywordIndex.index(id, content);
                return;
            }
            return;
        }

        // Normal ingestion path
        int storeIndex = vectorStore.put(id, vector);
        documentStore.put(Document.of(id, content));
        vectorIndex.add(id, storeIndex, vector);
        keywordIndex.index(id, content);
    }

    /**
     * Ingests a document with title, content, and vector.
     *
     * @param id       unique document identifier
     * @param title    document title
     * @param content  text content for keyword search
     * @param vector   embedding vector for semantic search
     */
    public void ingest(String id, String title, String content, float[] vector) {
        ensureOpen();

        int storeIndex = vectorStore.put(id, vector);
        documentStore.put(Document.of(id, title, content));
        vectorIndex.add(id, storeIndex, vector);
        keywordIndex.index(id, title + " " + content);
    }

    /**
     * Ingests a batch of documents.
     *
     * @param ids      document IDs
     * @param contents text contents
     * @param vectors  embedding vectors
     */
    public void ingestBatch(String[] ids, String[] contents, float[][] vectors) {
        ensureOpen();
        for (int i = 0; i < ids.length; i++) {
            ingest(ids[i], contents[i], vectors[i]);
        }
    }

    /**
     * Deletes a document by ID from all indexes.
     *
     * <p>Removes the document from the document store and keyword index.
     * Note: vector index entries are not removed (HNSW does not support
     * point deletion); they become orphaned and will not appear in
     * results because the document store lookup will return null.</p>
     *
     * @param id document identifier to delete
     * @return true if the document existed and was removed
     */
    public boolean delete(String id) {
        ensureOpen();
        Document removed = documentStore.remove(id);
        if (removed != null) {
            keywordIndex.remove(id);
            log.debug("Deleted document '{}'", id);
            return true;
        }
        return false;
    }

    // ─────────────── Large Document Ingestion ───────────────

    /**
     * Ingests a large document by splitting it into overlapping chunks.
     *
     * @param id            document ID
     * @param content       full document text
     * @param vectorProvider function mapping chunk text to an embedding vector
     * @return number of chunks ingested
     */
    public int ingestChunked(String id, String content,
                             java.util.function.Function<String, float[]> vectorProvider) {
        return ingestChunked(id, content, vectorProvider, new TextChunker());
    }

    /**
     * Ingests a large document with a custom chunker configuration.
     *
     * @param id            document ID
     * @param content       full document text
     * @param vectorProvider function mapping chunk text to an embedding vector
     * @param chunker       configured TextChunker
     * @return number of chunks ingested
     */
    public int ingestChunked(String id, String content,
                             java.util.function.Function<String, float[]> vectorProvider,
                             TextChunker chunker) {
        ensureOpen();

        // Store the full document metadata
        documentStore.put(Document.of(id, content));

        var chunks = chunker.chunk(id, content);
        for (var chunk : chunks) {
            float[] vector = vectorProvider.apply(chunk.text());
            int storeIndex = vectorStore.put(chunk.chunkId(), vector);
            vectorIndex.add(chunk.chunkId(), storeIndex, vector);
            keywordIndex.index(chunk.chunkId(), chunk.text());
        }

        log.info("Ingested '{}' as {} chunks (chunkSize={}, overlap={})",
                id, chunks.size(), chunker.chunkSize(), chunker.overlap());
        return chunks.size();
    }

    /**
     * Ingests structured content (XML, JSON, Java objects) by extracting text.
     *
     * @param id            document ID
     * @param content       structured content (XML, JSON, or plain text)
     * @param vector        embedding vector (for the extracted text)
     */
    public void ingestStructured(String id, String content, float[] vector) {
        String extracted = ContentExtractor.extract(content);
        ingest(id, extracted, vector);
    }

    /**
     * Ingests a large file using streaming chunking with bounded memory.
     *
     * @param path           path to the text file
     * @param documentId     parent document ID
     * @param vectorProvider function mapping chunk text to an embedding vector
     * @param chunkSize      target chunk size in characters
     * @param overlap        overlap between chunks in characters
     * @return number of chunks ingested
     * @throws java.io.IOException if the file cannot be read
     */
    public int ingestFile(java.nio.file.Path path, String documentId,
                          java.util.function.Function<String, float[]> vectorProvider,
                          int chunkSize, int overlap) throws java.io.IOException {
        ensureOpen();
        int count = 0;

        try (var stream = StreamingChunker.chunkFile(path, documentId, chunkSize, overlap)) {
            var iter = stream.iterator();
            while (iter.hasNext()) {
                var chunk = iter.next();
                float[] vector = vectorProvider.apply(chunk.text());
                int storeIndex = vectorStore.put(chunk.chunkId(), vector);
                vectorIndex.add(chunk.chunkId(), storeIndex, vector);
                keywordIndex.index(chunk.chunkId(), chunk.text());
                count++;
            }
        }

        log.info("Streaming-ingested file '{}' as {} chunks (chunkSize={}, overlap={})",
                path.getFileName(), count, chunkSize, overlap);
        return count;
    }

    /**
     * Ingests a large document using token-level chunking for precise token limits.
     *
     * @param id            document ID
     * @param content       full document text
     * @param vectorProvider function mapping chunk text to an embedding vector
     * @param maxTokens     maximum tokens per chunk
     * @param overlapTokens overlap tokens between chunks
     * @return number of chunks ingested
     */
    public int ingestTokenChunked(String id, String content,
                                  java.util.function.Function<String, float[]> vectorProvider,
                                  int maxTokens, int overlapTokens) {
        ensureOpen();

        var chunker = new TokenChunker(maxTokens, overlapTokens);
        documentStore.put(Document.of(id, content));

        var chunks = chunker.chunk(id, content);
        for (var chunk : chunks) {
            float[] vector = vectorProvider.apply(chunk.text());
            int storeIndex = vectorStore.put(chunk.chunkId(), vector);
            vectorIndex.add(chunk.chunkId(), storeIndex, vector);
            keywordIndex.index(chunk.chunkId(), chunk.text());
        }

        log.info("Token-chunked '{}' into {} chunks (maxTokens={}, overlap={})",
                id, chunks.size(), maxTokens, overlapTokens);
        return chunks.size();
    }

    // ─────────────── Auto-Embed Ingestion ───────────────

    /**
     * Ingests a document with automatic embedding generation.
     * Requires an {@link EmbeddingProvider} to be configured.
     *
     * @param id      unique document identifier
     * @param content text content
     * @throws IllegalStateException if no embedding provider is configured
     */
    public void ingest(String id, String content) {
        ensureOpen();
        requireEmbeddingProvider();
        float[] vector = embeddingProvider.embed(content).vector();
        ingest(id, content, vector);
    }

    /**
     * Ingests a document with title and automatic embedding.
     *
     * @param id      unique document identifier
     * @param title   document title
     * @param content text content
     */
    public void ingest(String id, String title, String content) {
        ensureOpen();
        requireEmbeddingProvider();
        float[] vector = embeddingProvider.embed(title + " " + content).vector();
        ingest(id, title, content, vector);
    }

    /**
     * Auto-embed chunked ingestion for large documents.
     *
     * @param id      document ID
     * @param content full document text
     * @return number of chunks ingested
     */
    public int ingestChunkedAuto(String id, String content) {
        requireEmbeddingProvider();
        return ingestChunked(id, content, text -> embeddingProvider.embed(text).vector());
    }

    /**
     * Auto-embed file ingestion with streaming.
     *
     * @param path       path to the text file
     * @param documentId parent document ID
     * @param chunkSize  target chunk size in characters
     * @param overlap    overlap between chunks
     * @return number of chunks ingested
     * @throws java.io.IOException if the file cannot be read
     */
    public int ingestFileAuto(java.nio.file.Path path, String documentId,
                              int chunkSize, int overlap) throws java.io.IOException {
        requireEmbeddingProvider();
        return ingestFile(path, documentId,
                text -> embeddingProvider.embed(text).vector(), chunkSize, overlap);
    }

    // ─────────────── Search ───────────────

    /**
     * Executes a search query.
     *
     * @param query the search query
     * @return the search response
     */
    public SearchResponse search(SearchQuery query) {
        ensureOpen();
        return orchestrator.search(query);
    }

    /** Convenience: keyword search. */
    public SearchResponse keywordSearch(String text, int topK) {
        return search(SearchQuery.keyword(text, topK));
    }

    /** Convenience: vector search. */
    public SearchResponse vectorSearch(float[] vector, int topK) {
        return search(SearchQuery.vector(vector, topK));
    }

    /** Convenience: hybrid search. */
    public SearchResponse hybridSearch(String text, float[] vector, int topK) {
        return search(SearchQuery.hybrid(text, vector, topK));
    }

    /**
     * Auto-embed search: embeds the query text and performs hybrid search.
     *
     * @param text query text
     * @param topK max results
     * @return search response
     */
    public SearchResponse search(String text, int topK) {
        ensureOpen();
        requireEmbeddingProvider();
        float[] queryVector = embeddingProvider.embed(text).vector();
        return hybridSearch(text, queryVector, topK);
    }

    // ─────────────── GPU-Accelerated Batch Operations ───────────────

    /**
     * Computes batch cosine similarities using GPU if available, CPU SIMD otherwise.
     *
     * @param query    query vector
     * @param database flat database vectors (N × D)
     * @param n        number of database vectors
     * @param dims     vector dimensionality
     * @return array of N similarity scores
     */
    public float[] batchCosineSimilarity(float[] query, float[] database, int n, int dims) {
        ensureOpen();
        if (gpuBatchSimilarity != null) {
            return gpuBatchSimilarity.batchCosineSimilarity(query, database, n, dims);
        }
        // CPU SIMD fallback
        float[] results = new float[n];
        for (int i = 0; i < n; i++) {
            float[] vec = new float[dims];
            System.arraycopy(database, i * dims, vec, 0, dims);
            results[i] = config.similarityFunction().compute(query, vec);
        }
        return results;
    }

    /** Returns whether GPU acceleration is active. */
    public boolean isGpuActive() {
        return gpuBatchSimilarity != null;
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
                if (config.persistenceMode() == PersistenceMode.DISK
                        && vectorIndex instanceof HnswIndex hnswIdx
                        && hnswIdx.size() > 0) {
                    try {
                        Path indexFile = config.dataDirectory().resolve("index.spct");
                        DiskHnswWriter.write(hnswIdx, indexFile);
                        log.info("HNSW index persisted to {}", indexFile);
                    } catch (IOException e) {
                        log.error("Failed to persist HNSW index to disk", e);
                    }
                }

                orchestrator.close();
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

    private void requireEmbeddingProvider() {
        if (embeddingProvider == null) {
            throw new IllegalStateException(
                    "No EmbeddingProvider configured. Use SpectorEngine(config, provider) or supply vectors manually.");
        }
    }

    /**
     * Trains the IVF-PQ index on buffered vectors and flushes all buffered documents into the index.
     */
    private void trainAndFlushIvfPq() {
        if (!(vectorIndex instanceof IvfPqIndex ivfPq)) return;

        float[][] trainingData = ivfTrainingBuffer.toArray(float[][]::new);
        log.info("Auto-training IVF-PQ with {} vectors...", trainingData.length);
        ivfPq.train(trainingData);

        // Flush all buffered vectors into the index
        for (int i = 0; i < ivfTrainingBuffer.size(); i++) {
            float[] vec = ivfTrainingBuffer.get(i);
            String id = ivfTrainingIds.get(i);
            String content = ivfTrainingContents.get(i);

            int storeIndex = vectorStore.put(id, vec);
            documentStore.put(Document.of(id, content));
            vectorIndex.add(id, storeIndex, vec);
            keywordIndex.index(id, content);
        }

        // Clear buffers
        ivfTrainingBuffer = null;
        ivfTrainingIds = null;
        ivfTrainingContents = null;
        ivfTrained = true;
        log.info("IVF-PQ training complete. {} vectors indexed.", ivfPq.size());
    }

    // ═════════════════════════════════════════════════════════════════
    //  Builder Pattern
    // ═════════════════════════════════════════════════════════════════

    /**
     * Fluent builder for constructing {@link SpectorEngine} instances.
     *
     * <p>Provides a readable, type-safe API for configuring the engine:</p>
     * <pre>{@code
     *   SpectorEngine engine = SpectorEngine.builder()
     *       .dimensions(768)
     *       .capacity(500_000)
     *       .similarity(SimilarityFunction.DOT_PRODUCT)
     *       .quantization(QuantizationType.SCALAR_INT8)
     *       .persistence(PersistenceMode.DISK, Path.of("/data"))
     *       .gpu(true)
     *       .reranker("http://localhost:11434", "llama3.2", 30)
     *       .embeddingProvider(new OllamaEmbeddingProvider(...))
     *       .build();
     * }</pre>
     */
    public static final class Builder {

        private SpectorConfig config = SpectorConfig.DEFAULT;
        private EmbeddingProvider embeddingProvider;
        private EngineComponentFactory componentFactory;

        Builder() {}

        /** Sets vector dimensionality (default: 384). */
        public Builder dimensions(int dims) {
            this.config = config.withDimensions(dims);
            return this;
        }

        /** Sets max document capacity (default: 100,000). */
        public Builder capacity(int capacity) {
            this.config = config.withCapacity(capacity);
            return this;
        }

        /** Sets the similarity function (default: COSINE). */
        public Builder similarity(SimilarityFunction sf) {
            this.config = config.withSimilarityFunction(sf);
            return this;
        }

        /** Sets quantization type (default: NONE). */
        public Builder quantization(com.spectrayan.spector.core.QuantizationType qt) {
            this.config = config.withQuantization(qt);
            return this;
        }

        /** Sets persistence mode and data directory. */
        public Builder persistence(PersistenceMode mode, Path directory) {
            this.config = config.withPersistence(mode, directory);
            return this;
        }

        /** Switches to IVF-PQ index with auto parameters. */
        public Builder ivfPq() {
            this.config = config.withIvfPq();
            return this;
        }

        /** Switches to IVF-PQ index with explicit parameters. */
        public Builder ivfPq(int nlist, int nprobe, int subspaces) {
            this.config = config.withIvfPq(nlist, nprobe, subspaces);
            return this;
        }

        /** Enables or disables GPU acceleration. */
        public Builder gpu(boolean enabled) {
            this.config = config.withGpu(enabled);
            return this;
        }

        /** Enables LLM re-ranking with default max candidates. */
        public Builder reranker(String ollamaUrl, String model) {
            this.config = config.withReranker(ollamaUrl, model);
            return this;
        }

        /** Enables LLM re-ranking with explicit max candidates. */
        public Builder reranker(String ollamaUrl, String model, int maxCandidates) {
            this.config = config.withReranker(ollamaUrl, model, maxCandidates);
            return this;
        }

        /** Sets the embedding provider for auto-embed ingestion and search. */
        public Builder embeddingProvider(EmbeddingProvider provider) {
            this.embeddingProvider = provider;
            return this;
        }

        /** Sets a custom component factory (for testing). */
        public Builder componentFactory(EngineComponentFactory factory) {
            this.componentFactory = factory;
            return this;
        }

        /** Sets the full config directly (advanced). */
        public Builder config(SpectorConfig config) {
            this.config = config;
            return this;
        }

        /**
         * Builds and returns a fully initialized {@link SpectorEngine}.
         *
         * @return a new engine instance
         */
        public SpectorEngine build() {
            EngineComponentFactory factory = componentFactory != null
                    ? componentFactory : new EngineComponentFactory();
            return new SpectorEngine(config, embeddingProvider, factory);
        }
    }
}
