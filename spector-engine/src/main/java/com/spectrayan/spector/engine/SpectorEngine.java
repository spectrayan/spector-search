package com.spectrayan.spector.engine;

import com.spectrayan.spector.commons.ContentExtractor;
import com.spectrayan.spector.commons.StreamingChunker;
import com.spectrayan.spector.commons.TextChunker;
import com.spectrayan.spector.commons.TokenChunker;
import com.spectrayan.spector.core.SimdCapability;
import com.spectrayan.spector.embed.EmbeddingProvider;
import com.spectrayan.spector.embed.EmbeddingResult;
import com.spectrayan.spector.index.BM25Index;
import com.spectrayan.spector.index.HnswIndex;
import com.spectrayan.spector.index.ScoredResult;
import com.spectrayan.spector.query.HybridSearchOrchestrator;
import com.spectrayan.spector.query.SearchQuery;
import com.spectrayan.spector.query.SearchResponse;
import com.spectrayan.spector.storage.Document;
import com.spectrayan.spector.storage.DocumentStore;
import com.spectrayan.spector.storage.InMemoryVectorStore;
import com.spectrayan.spector.storage.VectorStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Unified entry-point for the Spector Search engine.
 *
 * <p>Manages the lifecycle of all underlying components: vector store,
 * document store, HNSW index, BM25 index, and hybrid query orchestrator.
 * Provides a simple API for document ingestion and search.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   try (var engine = new SpectorEngine(config)) {
 *       engine.ingest("doc-1", "Hello world", embedding);
 *       SearchResponse response = engine.search(
 *           SearchQuery.hybrid("hello", queryEmbedding, 10));
 *   }
 * }</pre>
 */
public class SpectorEngine implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SpectorEngine.class);

    private final SpectorConfig config;
    private final VectorStore vectorStore;
    private final DocumentStore documentStore;
    private final HnswIndex vectorIndex;
    private final BM25Index keywordIndex;
    private final HybridSearchOrchestrator orchestrator;
    private final EmbeddingProvider embeddingProvider; // nullable
    private volatile boolean closed;

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
     * <p>When an embedding provider is set, documents can be ingested
     * with just text — vectors are generated automatically.</p>
     *
     * @param config   the engine configuration
     * @param provider the embedding provider (nullable)
     */
    public SpectorEngine(SpectorConfig config, EmbeddingProvider provider) {
        this.config = config;
        this.embeddingProvider = provider;
        this.closed = false;

        log.info("Initializing SpectorEngine: dims={}, capacity={}, similarity={}, embedding={}, {}",
                config.dimensions(), config.capacity(), config.similarityFunction(),
                provider != null ? provider.modelName() : "none",
                SimdCapability.report());

        this.vectorStore = new InMemoryVectorStore(config.dimensions(), config.capacity());
        this.documentStore = new DocumentStore(config.capacity());
        this.vectorIndex = new HnswIndex(
                config.dimensions(), config.capacity(),
                config.similarityFunction(), config.hnswParams());
        this.keywordIndex = new BM25Index();
        this.orchestrator = new HybridSearchOrchestrator(keywordIndex, vectorIndex);

        log.info("SpectorEngine initialized successfully");
    }

    /** Creates an engine with default configuration. */
    public SpectorEngine() {
        this(SpectorConfig.DEFAULT);
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

        // Store vector
        int storeIndex = vectorStore.put(id, vector);

        // Store document metadata
        documentStore.put(Document.of(id, content));

        // Index in both engines
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

    // ─────────────── Large Document Ingestion ───────────────

    /**
     * Ingests a large document by splitting it into overlapping chunks.
     *
     * <p>Each chunk gets its own keyword index entry with a chunk-specific ID
     * (e.g., "doc-1#chunk-0"). The vector for each chunk must be provided via
     * the {@code vectorProvider} function.</p>
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
     * Ingests structured content (XML, JSON, Java objects) by extracting text,
     * then optionally chunking for large documents.
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
     * <p>Only ~2× chunkSize characters are held in memory at any time,
     * making this suitable for multi-GB files.</p>
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

    /**
     * Convenience: keyword search.
     *
     * @param text query text
     * @param topK max results
     * @return search response
     */
    public SearchResponse keywordSearch(String text, int topK) {
        return search(SearchQuery.keyword(text, topK));
    }

    /**
     * Convenience: vector search.
     *
     * @param vector query vector
     * @param topK   max results
     * @return search response
     */
    public SearchResponse vectorSearch(float[] vector, int topK) {
        return search(SearchQuery.vector(vector, topK));
    }

    /**
     * Convenience: hybrid search.
     *
     * @param text   query text
     * @param vector query vector
     * @param topK   max results
     * @return search response
     */
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

    // ─────────────── Lifecycle ───────────────

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            try {
                vectorIndex.close();
                keywordIndex.close();
                vectorStore.close();
                documentStore.close();
                if (embeddingProvider != null) embeddingProvider.close();
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
}
