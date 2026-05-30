package com.spectrayan.spector.engine;

import com.spectrayan.spector.config.SpectorConfig;
import com.spectrayan.spector.embed.EmbeddingProvider;
import com.spectrayan.spector.index.VectorIndex;
import com.spectrayan.spector.query.SearchQuery;
import com.spectrayan.spector.query.SearchResponse;
import com.spectrayan.spector.query.ranking.Reranker;
import com.spectrayan.spector.storage.DocumentStore;
import com.spectrayan.spector.storage.VectorStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * Primary interface for the Spector engine.
 *
 * <p>Provides a unified API for document ingestion, search, and lifecycle
 * management. Implementations include {@link DefaultSpectorEngine} (the
 * standard implementation) and metered decorators for observability.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   SpectorEngine engine = DefaultSpectorEngine.builder()
 *       .dimensions(384)
 *       .capacity(100_000)
 *       .build();
 *
 *   engine.ingest("doc-1", "Hello world", vectorData);
 *   SearchResponse response = engine.search(SearchQuery.keyword("hello", 10));
 * }</pre>
 *
 * @see DefaultSpectorEngine
 */
public interface SpectorEngine extends AutoCloseable {

    // ─────────────── Ingestion ───────────────

    /** Ingests a single document with its text content and vector embedding. */
    void ingest(String id, String content, float[] vector);

    /** Ingests a document with title, content, and vector. */
    void ingest(String id, String title, String content, float[] vector);

    /** Ingests a batch of documents. */
    void ingestBatch(String[] ids, String[] contents, float[][] vectors);

    /** Deletes a document by ID from all indexes. */
    boolean delete(String id);

    /** Ingests a large document by splitting it into overlapping chunks. */
    int ingestChunked(String id, String content,
                      Function<String, float[]> vectorProvider);

    /** Ingests a large document with a custom chunker configuration. */
    int ingestChunked(String id, String content,
                      Function<String, float[]> vectorProvider,
                      com.spectrayan.spector.commons.TextChunker chunker);

    /** Ingests structured content (XML, JSON, Java objects) by extracting text. */
    void ingestStructured(String id, String content, float[] vector);

    /** Ingests a large file using streaming chunking with bounded memory. */
    int ingestFile(Path path, String documentId,
                   Function<String, float[]> vectorProvider,
                   int chunkSize, int overlap) throws IOException;

    /** Ingests a large document using token-level chunking. */
    int ingestTokenChunked(String id, String content,
                           Function<String, float[]> vectorProvider,
                           int maxTokens, int overlapTokens);

    /** Ingests a document with automatic embedding generation. */
    void ingest(String id, String content);

    /** Ingests a document with title and automatic embedding. */
    void ingest(String id, String title, String content);

    /** Auto-embed chunked ingestion for large documents. */
    int ingestChunkedAuto(String id, String content);

    /** Auto-embed file ingestion with streaming. */
    int ingestFileAuto(Path path, String documentId,
                       int chunkSize, int overlap) throws IOException;

    // ─────────────── Search ───────────────

    /** Executes a search query. */
    SearchResponse search(SearchQuery query);

    /** Convenience: keyword search. */
    SearchResponse keywordSearch(String text, int topK);

    /** Convenience: vector search. */
    SearchResponse vectorSearch(float[] vector, int topK);

    /** Convenience: hybrid search. */
    SearchResponse hybridSearch(String text, float[] vector, int topK);

    /** Auto-embed search: embeds the query text and performs hybrid search. */
    SearchResponse search(String text, int topK);

    // ─────────────── GPU-Accelerated Batch Operations ───────────────

    /** Computes batch cosine similarities using GPU if available, CPU SIMD otherwise. */
    float[] batchCosineSimilarity(float[] query, float[] database, int n, int dims);

    /** Returns whether GPU acceleration is active. */
    boolean isGpuActive();

    // ─────────────── Accessors ───────────────

    /** Returns the engine configuration. */
    SpectorConfig config();

    /** Returns the number of indexed documents. */
    int documentCount();

    /** Returns the document store. */
    DocumentStore documentStore();

    /** Returns the vector store. */
    VectorStore vectorStore();

    /** Returns the underlying vector index (for ANN pre-filtering by Memory). */
    VectorIndex index();

    /** Returns the embedding provider, or null if none configured. */
    EmbeddingProvider embeddingProvider();

    /** Returns true if an embedding provider is configured. */
    boolean hasEmbeddingProvider();

    /** Returns the active re-ranker, or null if none configured. */
    Reranker reranker();

    /** Returns true if LLM re-ranking is active. */
    boolean isRerankerActive();

    /** Returns the engine's ingestion target for use with the unified IngestionPipeline. */
    EngineIngestionTarget target();

    /** Closes the engine and releases all resources. */
    @Override
    void close();

    /** Returns a new fluent {@link DefaultSpectorEngine.Builder} for constructing an engine. */
    static DefaultSpectorEngine.Builder builder() {
        return DefaultSpectorEngine.builder();
    }
}
