package com.spectrayan.spector.ingestion;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.commons.ContentExtractor;
import com.spectrayan.spector.commons.StreamingChunker;
import com.spectrayan.spector.commons.TextChunker;
import com.spectrayan.spector.commons.TokenChunker;
import com.spectrayan.spector.embed.EmbeddingProvider;
import com.spectrayan.spector.embed.ParallelEmbeddingPipeline;
import com.spectrayan.spector.embed.EmbedConfig;
import com.spectrayan.spector.embed.PipelineEmbeddingResult;

/**
 * Standalone ingestion pipeline that orchestrates: document → chunk → embed → store → index.
 *
 * <p>Uses virtual threads and structured concurrency for parallel embedding calls
 * without introducing Project Reactor or other reactive frameworks. The pipeline
 * writes through an {@link IngestionTarget} abstraction, decoupling it from the
 * engine internals.</p>
 *
 * <h3>Design Decisions</h3>
 * <ul>
 *   <li>CPU-bound chunking runs on the caller's (virtual) thread</li>
 *   <li>I/O-bound embedding uses {@link ParallelEmbeddingPipeline} with virtual threads</li>
 *   <li>Store/index writes are synchronous (already optimized with SIMD/Panama)</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   var pipeline = new IngestionPipeline(target, embeddingProvider);
 *   IngestionResult result = pipeline.ingest("doc-1", "Hello world");
 *   IngestionResult chunked = pipeline.ingestChunked("doc-2", longText);
 * }</pre>
 */
public class IngestionPipeline {

    private static final Logger log = LoggerFactory.getLogger(IngestionPipeline.class);

    private final IngestionTarget target;
    private final EmbeddingProvider embeddingProvider; // nullable for manual-vector mode
    private final ParallelEmbeddingPipeline parallelPipeline; // nullable

    /**
     * Creates a pipeline with an embedding provider for auto-embed operations.
     *
     * @param target             the storage/index target
     * @param embeddingProvider  embedding provider (nullable for manual-vector mode)
     */
    public IngestionPipeline(IngestionTarget target, EmbeddingProvider embeddingProvider) {
        this.target = target;
        this.embeddingProvider = embeddingProvider;
        this.parallelPipeline = embeddingProvider != null
                ? new ParallelEmbeddingPipeline(embeddingProvider) : null;
    }

    /**
     * Creates a pipeline for manual-vector ingestion only (no auto-embedding).
     *
     * @param target the storage/index target
     */
    public IngestionPipeline(IngestionTarget target) {
        this(target, null);
    }

    // ─────────────── Single Document Ingestion ───────────────

    /**
     * Ingests a single document with a pre-computed vector.
     *
     * @param id      document ID
     * @param content text content
     * @param vector  embedding vector
     * @return ingestion result
     */
    public IngestionResult ingest(String id, String content, float[] vector) {
        long start = System.nanoTime();
        doIngest(id, "", content, vector);
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        return IngestionResult.single(id, elapsed);
    }

    /**
     * Ingests a single document with title and pre-computed vector.
     */
    public IngestionResult ingest(String id, String title, String content, float[] vector) {
        long start = System.nanoTime();
        doIngest(id, title, content, vector);
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        return IngestionResult.single(id, elapsed);
    }

    /**
     * Ingests a single document with auto-embedding.
     *
     * @param id      document ID
     * @param content text content (will be embedded automatically)
     * @return ingestion result
     * @throws IllegalStateException if no embedding provider is configured
     */
    public IngestionResult ingest(String id, String content) {
        requireEmbeddingProvider();
        long start = System.nanoTime();
        float[] vector = embeddingProvider.embed(content).vector();
        doIngest(id, "", content, vector);
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        return IngestionResult.single(id, elapsed);
    }

    // ─────────────── Chunked Ingestion ───────────────

    /**
     * Ingests a large document by splitting into chunks with auto-embedding.
     * Uses parallel embedding via virtual threads.
     *
     * @param id      document ID
     * @param content full document text
     * @return ingestion result with chunk count
     */
    public IngestionResult ingestChunked(String id, String content) {
        return ingestChunked(id, content, new TextChunker());
    }

    /**
     * Ingests a large document with a custom chunker and auto-embedding.
     *
     * @param id      document ID
     * @param content full document text
     * @param chunker configured chunker
     * @return ingestion result
     */
    public IngestionResult ingestChunked(String id, String content, TextChunker chunker) {
        requireEmbeddingProvider();
        long start = System.nanoTime();

        // Store parent document metadata
        target.storeDocument(id, "", content);

        var chunks = chunker.chunk(id, content);
        List<String> texts = chunks.stream().map(TextChunker.Chunk::text).toList();

        // Parallel embedding using virtual threads
        List<PipelineEmbeddingResult> embeddings = parallelPipeline.embed(texts, EmbedConfig.DEFAULT);

        List<String> failures = new ArrayList<>();
        int stored = 0;

        for (int i = 0; i < chunks.size(); i++) {
            var chunk = chunks.get(i);
            var embedding = embeddings.get(i);

            if (embedding.success()) {
                int storeIndex = target.storeVector(chunk.chunkId(), embedding.embedding());
                target.indexVector(chunk.chunkId(), storeIndex, embedding.embedding());
                target.indexKeywords(chunk.chunkId(), chunk.text());
                stored++;
            } else {
                failures.add(chunk.chunkId());
                log.warn("Embedding failed for chunk '{}': {}", chunk.chunkId(), embedding.error());
            }
        }

        long elapsed = (System.nanoTime() - start) / 1_000_000;
        log.info("Ingested '{}' as {} chunks ({} failed) in {}ms", id, stored, failures.size(), elapsed);
        return IngestionResult.chunked(id, stored, failures, elapsed);
    }

    /**
     * Ingests a large document with a manual vector provider function.
     *
     * @param id             document ID
     * @param content        full document text
     * @param vectorProvider function mapping chunk text → embedding vector
     * @param chunker        configured chunker
     * @return ingestion result
     */
    public IngestionResult ingestChunked(String id, String content,
                                         Function<String, float[]> vectorProvider,
                                         TextChunker chunker) {
        long start = System.nanoTime();

        target.storeDocument(id, "", content);
        var chunks = chunker.chunk(id, content);
        List<String> failures = new ArrayList<>();
        int stored = 0;

        for (var chunk : chunks) {
            try {
                float[] vector = vectorProvider.apply(chunk.text());
                int storeIndex = target.storeVector(chunk.chunkId(), vector);
                target.indexVector(chunk.chunkId(), storeIndex, vector);
                target.indexKeywords(chunk.chunkId(), chunk.text());
                stored++;
            } catch (Exception e) {
                failures.add(chunk.chunkId());
                log.warn("Ingestion failed for chunk '{}': {}", chunk.chunkId(), e.getMessage());
            }
        }

        long elapsed = (System.nanoTime() - start) / 1_000_000;
        log.info("Ingested '{}' as {} chunks ({} failed) in {}ms", id, stored, failures.size(), elapsed);
        return IngestionResult.chunked(id, stored, failures, elapsed);
    }

    // ─────────────── Token-Level Chunked Ingestion ───────────────

    /**
     * Ingests with token-level chunking and auto-embedding.
     *
     * @param id            document ID
     * @param content       full document text
     * @param maxTokens     max tokens per chunk
     * @param overlapTokens overlap tokens between chunks
     * @return ingestion result
     */
    public IngestionResult ingestTokenChunked(String id, String content,
                                              int maxTokens, int overlapTokens) {
        requireEmbeddingProvider();
        long start = System.nanoTime();

        target.storeDocument(id, "", content);
        var chunker = new TokenChunker(maxTokens, overlapTokens);
        var chunks = chunker.chunk(id, content);
        List<String> texts = chunks.stream().map(TextChunker.Chunk::text).toList();

        List<PipelineEmbeddingResult> embeddings = parallelPipeline.embed(texts, EmbedConfig.DEFAULT);

        List<String> failures = new ArrayList<>();
        int stored = 0;

        for (int i = 0; i < chunks.size(); i++) {
            var chunk = chunks.get(i);
            var embedding = embeddings.get(i);

            if (embedding.success()) {
                int storeIndex = target.storeVector(chunk.chunkId(), embedding.embedding());
                target.indexVector(chunk.chunkId(), storeIndex, embedding.embedding());
                target.indexKeywords(chunk.chunkId(), chunk.text());
                stored++;
            } else {
                failures.add(chunk.chunkId());
            }
        }

        long elapsed = (System.nanoTime() - start) / 1_000_000;
        return IngestionResult.chunked(id, stored, failures, elapsed);
    }

    // ─────────────── Streaming File Ingestion ───────────────

    /**
     * Ingests a large file using streaming chunking with bounded memory.
     *
     * @param path       path to the text file
     * @param documentId parent document ID
     * @param chunkSize  target chunk size in characters
     * @param overlap    overlap between chunks
     * @return ingestion result
     * @throws IOException if the file cannot be read
     */
    public IngestionResult ingestFile(Path path, String documentId,
                                      int chunkSize, int overlap) throws IOException {
        requireEmbeddingProvider();
        long start = System.nanoTime();
        int count = 0;
        List<String> failures = new ArrayList<>();

        try (var stream = StreamingChunker.chunkFile(path, documentId, chunkSize, overlap)) {
            var iter = stream.iterator();
            while (iter.hasNext()) {
                var chunk = iter.next();
                try {
                    float[] vector = embeddingProvider.embed(chunk.text()).vector();
                    int storeIndex = target.storeVector(chunk.chunkId(), vector);
                    target.indexVector(chunk.chunkId(), storeIndex, vector);
                    target.indexKeywords(chunk.chunkId(), chunk.text());
                    count++;
                } catch (Exception e) {
                    failures.add(chunk.chunkId());
                    log.warn("Streaming ingestion failed for chunk '{}': {}", chunk.chunkId(), e.getMessage());
                }
            }
        }

        long elapsed = (System.nanoTime() - start) / 1_000_000;
        log.info("Stream-ingested '{}' → {} chunks ({} failed) in {}ms",
                path.getFileName(), count, failures.size(), elapsed);
        return IngestionResult.chunked(documentId, count, failures, elapsed);
    }

    /**
     * Ingests a large file with a manual vector provider.
     */
    public IngestionResult ingestFile(Path path, String documentId,
                                      Function<String, float[]> vectorProvider,
                                      int chunkSize, int overlap) throws IOException {
        long start = System.nanoTime();
        int count = 0;
        List<String> failures = new ArrayList<>();

        try (var stream = StreamingChunker.chunkFile(path, documentId, chunkSize, overlap)) {
            var iter = stream.iterator();
            while (iter.hasNext()) {
                var chunk = iter.next();
                try {
                    float[] vector = vectorProvider.apply(chunk.text());
                    int storeIndex = target.storeVector(chunk.chunkId(), vector);
                    target.indexVector(chunk.chunkId(), storeIndex, vector);
                    target.indexKeywords(chunk.chunkId(), chunk.text());
                    count++;
                } catch (Exception e) {
                    failures.add(chunk.chunkId());
                }
            }
        }

        long elapsed = (System.nanoTime() - start) / 1_000_000;
        return IngestionResult.chunked(documentId, count, failures, elapsed);
    }

    // ─────────────── Structured Content Ingestion ───────────────

    /**
     * Ingests structured content (XML, JSON) by extracting text first.
     *
     * @param id      document ID
     * @param content structured content
     * @param vector  pre-computed vector for the extracted text
     * @return ingestion result
     */
    public IngestionResult ingestStructured(String id, String content, float[] vector) {
        String extracted = ContentExtractor.extract(content);
        return ingest(id, extracted, vector);
    }

    // ─────────────── Batch Ingestion ───────────────

    /**
     * Ingests a batch of documents with pre-computed vectors.
     *
     * @param ids      document IDs
     * @param contents text contents
     * @param vectors  embedding vectors
     * @return list of individual results
     */
    public List<IngestionResult> ingestBatch(String[] ids, String[] contents, float[][] vectors) {
        List<IngestionResult> results = new ArrayList<>(ids.length);
        for (int i = 0; i < ids.length; i++) {
            results.add(ingest(ids[i], contents[i], vectors[i]));
        }
        return results;
    }

    // ─────────────── Internal ───────────────

    private void doIngest(String id, String title, String content, float[] vector) {
        int storeIndex = target.storeVector(id, vector);
        target.storeDocument(id, title, content);
        target.indexVector(id, storeIndex, vector);
        target.indexKeywords(id, title.isEmpty() ? content : title + " " + content);
    }

    private void requireEmbeddingProvider() {
        if (embeddingProvider == null) {
            throw new IllegalStateException(
                    "No EmbeddingProvider configured. Use IngestionPipeline(target, provider) " +
                    "or supply vectors manually.");
        }
    }

    /** Returns true if an embedding provider is configured. */
    public boolean hasEmbeddingProvider() {
        return embeddingProvider != null;
    }
}
