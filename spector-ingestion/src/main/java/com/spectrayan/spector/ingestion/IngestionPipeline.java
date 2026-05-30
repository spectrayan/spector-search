package com.spectrayan.spector.ingestion;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.commons.StreamingChunker;
import com.spectrayan.spector.commons.TextChunker;
import com.spectrayan.spector.embed.EmbeddingProvider;
import com.spectrayan.spector.embed.EmbedConfig;
import com.spectrayan.spector.embed.ParallelEmbeddingPipeline;
import com.spectrayan.spector.embed.PipelineEmbeddingResult;
import com.spectrayan.spector.commons.error.SpectorInternalException;
import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.commons.error.SpectorValidationException;

/**
 * Unified ingestion pipeline: chunk → embed → store.
 *
 * <p>Configured via a {@link Builder} and exposes a single {@link #ingest}
 * entry point. The pipeline decides the strategy (direct, chunked, streaming)
 * based on builder configuration and content characteristics.</p>
 *
 * <h3>Strategy Selection</h3>
 * <ul>
 *   <li><b>Direct</b>: content ≤ chunkThreshold or no chunker configured</li>
 *   <li><b>Chunked</b>: content > chunkThreshold and chunker configured</li>
 *   <li><b>Streaming</b>: file path provided — reads lazily via {@link StreamingChunker}</li>
 *   <li><b>Pre-embedded</b>: vector provided — skips embedding entirely</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   var pipeline = IngestionPipeline.builder()
 *       .target(engineTarget)
 *       .embeddingProvider(embedder)
 *       .chunking(new TextChunker(800, 100))
 *       .chunkThreshold(800)
 *       .build();
 *
 *   IngestionResult result = pipeline.ingest("doc-1", content);
 * }</pre>
 *
 * @see IngestionTarget
 * @see Builder
 */
public class IngestionPipeline {

    private static final Logger log = LoggerFactory.getLogger(IngestionPipeline.class);

    private final IngestionTarget target;
    private final EmbeddingProvider embeddingProvider; // nullable for pre-embedded mode
    private final ParallelEmbeddingPipeline parallelPipeline; // nullable
    private final TextChunker chunker;   // nullable (no chunking if absent)
    private final int chunkThreshold;    // auto-chunk if content length exceeds this

    private IngestionPipeline(Builder builder) {
        this.target = builder.target;
        this.embeddingProvider = builder.embeddingProvider;
        this.chunker = builder.chunker;
        this.chunkThreshold = builder.chunkThreshold;

        // Initialize parallel embedding pipeline if provider is available
        this.parallelPipeline = builder.embeddingProvider != null
                ? new ParallelEmbeddingPipeline(builder.embeddingProvider) : null;

        log.info("IngestionPipeline created: chunker={}, chunkThreshold={}, hasEmbedder={}, target={}",
                chunker != null ? chunker.getClass().getSimpleName() : "none",
                chunkThreshold,
                embeddingProvider != null,
                target.getClass().getSimpleName());
    }

    /** Creates a new builder. */
    public static Builder builder() {
        return new Builder();
    }

    // ═══════════════════════════════════════════════════════════════
    // PUBLIC API — single ingest() method with overloads
    // ═══════════════════════════════════════════════════════════════

    /**
     * Ingests text content with auto-embedding.
     *
     * <p>The pipeline automatically selects the strategy based on configuration:
     * <ul>
     *   <li>If content length > chunkThreshold and a chunker is configured → chunk then embed</li>
     *   <li>Otherwise → embed the entire text as a single document</li>
     * </ul>
     *
     * @param id      document ID
     * @param content text content
     * @return ingestion result
     * @throws SpectorValidationException if no embedding provider is configured
     */
    public IngestionResult ingest(String id, String content) {
        requireEmbeddingProvider();
        long start = System.nanoTime();

        if (shouldChunk(content)) {
            return chunkAndIngest(id, content, start);
        }
        return directIngest(id, content, start);
    }

    /**
     * Ingests text content with a pre-computed embedding vector.
     *
     * <p>Skips embedding entirely — the provided vector is passed directly
     * to the target. No chunking is applied (pre-embedded implies the
     * caller has already handled chunking if needed).</p>
     *
     * @param id      document ID
     * @param content text content
     * @param vector  pre-computed embedding vector
     * @return ingestion result
     */
    public IngestionResult ingest(String id, String content, float[] vector) {
        long start = System.nanoTime();

        target.ingest(id, content, vector);

        long elapsed = (System.nanoTime() - start) / 1_000_000;
        return IngestionResult.single(id, elapsed);
    }

    /**
     * Ingests a file by streaming its content chunk-by-chunk.
     *
     * <p>Uses {@link StreamingChunker} for bounded-memory file processing.
     * Each chunk is embedded and stored independently — the full file content
     * is never held in memory.</p>
     *
     * @param file       path to the text file
     * @param documentId parent document ID
     * @return ingestion result
     * @throws IOException if the file cannot be read
     */
    public IngestionResult ingest(Path file, String documentId) throws IOException {
        requireEmbeddingProvider();
        long start = System.nanoTime();

        int chunkSize = chunker != null ? chunker.chunkSize() : 800;
        int overlap = chunker != null ? chunker.overlap() : 100;

        int count = 0;
        List<String> failures = new ArrayList<>();

        try (var stream = StreamingChunker.chunkFile(file, documentId, chunkSize, overlap)) {
            var iter = stream.iterator();
            while (iter.hasNext()) {
                var chunk = iter.next();
                try {
                    float[] vector = embeddingProvider.embed(chunk.text()).vector();
                    target.ingest(chunk.chunkId(), chunk.text(), vector);
                    count++;
                } catch (Exception e) {
                    failures.add(chunk.chunkId());
                    log.warn("Streaming ingestion failed for chunk '{}': {}",
                            chunk.chunkId(), e.getMessage());
                }
            }
        }

        target.storeParentMetadata(documentId, count);
        target.onBatchComplete();

        long elapsed = (System.nanoTime() - start) / 1_000_000;
        log.info("Stream-ingested '{}' → {} chunks ({} failed) in {}ms",
                file.getFileName(), count, failures.size(), elapsed);
        return IngestionResult.chunked(documentId, count, failures, elapsed);
    }

    // ═══════════════════════════════════════════════════════════════
    // INTERNAL STRATEGIES — selected by ingest() based on config
    // ═══════════════════════════════════════════════════════════════

    /**
     * Direct single-document ingestion: embed → store.
     */
    private IngestionResult directIngest(String id, String content, long startNanos) {
        float[] vector = embeddingProvider.embed(content).vector();
        target.ingest(id, content, vector);
        target.storeParentMetadata(id, 1);

        long elapsed = (System.nanoTime() - startNanos) / 1_000_000;
        return IngestionResult.single(id, elapsed);
    }

    /**
     * Chunked ingestion with parallel embedding.
     *
     * <p>Splits content into chunks via the configured chunker, embeds all
     * chunks in parallel using virtual threads, then stores each chunk.</p>
     */
    private IngestionResult chunkAndIngest(String id, String content, long startNanos) {
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
                target.ingest(chunk.chunkId(), chunk.text(), embedding.embedding());
                stored++;
            } else {
                failures.add(chunk.chunkId());
                log.warn("Embedding failed for chunk '{}': {}", chunk.chunkId(), embedding.error());
            }
        }

        target.storeParentMetadata(id, stored);
        target.onBatchComplete();

        long elapsed = (System.nanoTime() - startNanos) / 1_000_000;
        log.info("Ingested '{}' as {} chunks ({} failed) in {}ms",
                id, stored, failures.size(), elapsed);
        return IngestionResult.chunked(id, stored, failures, elapsed);
    }

    // ═══════════════════════════════════════════════════════════════
    // INTERNAL HELPERS
    // ═══════════════════════════════════════════════════════════════

    private boolean shouldChunk(String content) {
        return chunker != null && content.length() > chunkThreshold;
    }

    private void requireEmbeddingProvider() {
        if (embeddingProvider == null) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "No EmbeddingProvider configured. Use builder().embeddingProvider(provider) " + "or use ingest(id, content, vector) with a pre-computed vector.");
        }
    }

    /** Returns true if an embedding provider is configured. */
    public boolean hasEmbeddingProvider() {
        return embeddingProvider != null;
    }

    /** Returns the configured chunker (nullable). */
    public TextChunker chunker() {
        return chunker;
    }

    // ═══════════════════════════════════════════════════════════════
    // BUILDER
    // ═══════════════════════════════════════════════════════════════

    /**
     * Builder for {@link IngestionPipeline}.
     *
     * <p>Required: {@link #target(IngestionTarget)}. All other fields are optional.</p>
     */
    public static final class Builder {
        private IngestionTarget target;
        private EmbeddingProvider embeddingProvider;
        private TextChunker chunker;
        private int chunkThreshold = 800;

        private Builder() {}

        /** Sets the target that receives ingested chunks. Required. */
        public Builder target(IngestionTarget target) {
            this.target = target;
            return this;
        }

        /** Sets the embedding provider for auto-embedding. */
        public Builder embeddingProvider(EmbeddingProvider embeddingProvider) {
            this.embeddingProvider = embeddingProvider;
            return this;
        }

        /**
         * Sets the chunker for splitting large documents.
         *
         * <p>If not set, all content is ingested as a single document.</p>
         */
        public Builder chunking(TextChunker chunker) {
            this.chunker = chunker;
            return this;
        }

        /**
         * Sets the content length threshold for auto-chunking.
         *
         * <p>Content shorter than this is ingested directly; longer content
         * is split using the configured chunker.</p>
         *
         * @param threshold content length in characters (default: 800)
         */
        public Builder chunkThreshold(int threshold) {
            this.chunkThreshold = threshold;
            return this;
        }

        /**
         * Builds the pipeline.
         *
         * @return configured ingestion pipeline
         * @throws SpectorValidationException if no target is set
         */
        public IngestionPipeline build() {
            if (target == null) {
                throw new SpectorInternalException(ErrorCode.ARGUMENT_NULL, "IngestionTarget");
            }
            return new IngestionPipeline(this);
        }
    }
}