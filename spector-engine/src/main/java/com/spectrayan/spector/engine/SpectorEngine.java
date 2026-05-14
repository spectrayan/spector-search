package com.spectrayan.spector.engine;

import com.spectrayan.spector.commons.ContentExtractor;
import com.spectrayan.spector.commons.TextChunker;
import com.spectrayan.spector.core.SimdCapability;
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
    private volatile boolean closed;

    /**
     * Creates and initializes a new engine with the given configuration.
     *
     * @param config the engine configuration
     */
    public SpectorEngine(SpectorConfig config) {
        this.config = config;
        this.closed = false;

        log.info("Initializing SpectorEngine: dims={}, capacity={}, similarity={}, {}",
                config.dimensions(), config.capacity(), config.similarityFunction(),
                SimdCapability.report());

        // Initialize storage
        this.vectorStore = new InMemoryVectorStore(config.dimensions(), config.capacity());
        this.documentStore = new DocumentStore(config.capacity());

        // Initialize indexes
        this.vectorIndex = new HnswIndex(
                config.dimensions(),
                config.capacity(),
                config.similarityFunction(),
                config.hnswParams());
        this.keywordIndex = new BM25Index();

        // Initialize query orchestrator
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

    // ─────────────── Accessors ───────────────

    /** Returns the engine configuration. */
    public SpectorConfig config() { return config; }

    /** Returns the number of indexed documents. */
    public int documentCount() { return vectorStore.size(); }

    /** Returns the document store. */
    public DocumentStore documentStore() { return documentStore; }

    /** Returns the vector store. */
    public VectorStore vectorStore() { return vectorStore; }

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
            } catch (Exception e) {
                log.warn("Error during engine shutdown", e);
            }
            log.info("SpectorEngine closed");
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("SpectorEngine is closed");
    }
}
