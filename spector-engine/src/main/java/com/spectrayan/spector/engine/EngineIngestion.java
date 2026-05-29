package com.spectrayan.spector.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.commons.ContentExtractor;
import com.spectrayan.spector.commons.StreamingChunker;
import com.spectrayan.spector.commons.TextChunker;
import com.spectrayan.spector.commons.TokenChunker;
import com.spectrayan.spector.config.IndexType;
import com.spectrayan.spector.config.SpectorConfig;
import com.spectrayan.spector.embed.EmbeddingProvider;
import com.spectrayan.spector.index.VectorIndex;
import com.spectrayan.spector.index.HnswIndex;
import com.spectrayan.spector.index.ivf.IvfPqIndex;
import com.spectrayan.spector.index.spectrum.SpectorIndex;
import com.spectrayan.spector.index.KeywordIndex;
import com.spectrayan.spector.storage.Document;
import com.spectrayan.spector.storage.DocumentStore;
import com.spectrayan.spector.storage.VectorStore;

/**
 * Handles all document ingestion logic for the Spector engine.
 *
 * <p>Extracted from {@link SpectorEngine} to decompose the god class into
 * focused, single-responsibility components. Manages:</p>
 * <ul>
 *   <li>Single document ingestion (with/without embedding)</li>
 *   <li>Batch ingestion</li>
 *   <li>Chunked ingestion (character-level, token-level, streaming)</li>
 *   <li>Structured content extraction</li>
 *   <li>IVF-PQ and Spectrum auto-training buffers</li>
 * </ul>
 */
final class EngineIngestion {

    private static final Logger log = LoggerFactory.getLogger(EngineIngestion.class);

    private final SpectorConfig config;
    private final VectorStore vectorStore;
    private final DocumentStore documentStore;
    private final VectorIndex vectorIndex;
    private final KeywordIndex keywordIndex;
    private final EmbeddingProvider embeddingProvider; // nullable

    // IVF-PQ training state
    private List<float[]> ivfTrainingBuffer;
    private List<String> ivfTrainingIds;
    private List<String> ivfTrainingContents;
    private volatile boolean ivfTrained;

    // Spectrum training state
    private List<float[]> spectrumTrainingBuffer;
    private List<String> spectrumTrainingIds;
    private List<String> spectrumTrainingContents;
    private volatile boolean spectrumTrained;

    EngineIngestion(SpectorConfig config, VectorStore vectorStore, DocumentStore documentStore,
                    VectorIndex vectorIndex, KeywordIndex keywordIndex,
                    EmbeddingProvider embeddingProvider) {
        this.config = config;
        this.vectorStore = vectorStore;
        this.documentStore = documentStore;
        this.vectorIndex = vectorIndex;
        this.keywordIndex = keywordIndex;
        this.embeddingProvider = embeddingProvider;
        this.ivfTrained = false;
        this.spectrumTrained = false;

        // IVF-PQ training buffer initialization
        if (config.indexType() == IndexType.IVF_PQ) {
            int minTrainingSamples = Math.max(config.effectiveNlist() * 40, 256);
            this.ivfTrainingBuffer = new ArrayList<>(minTrainingSamples);
            this.ivfTrainingIds = new ArrayList<>(minTrainingSamples);
            this.ivfTrainingContents = new ArrayList<>(minTrainingSamples);
            log.info("IVF-PQ index created (untrained). Will auto-train after {} vectors.",
                    minTrainingSamples);
        }

        // Spectrum training buffer initialization
        if (config.indexType() == IndexType.SPECTRUM) {
            int minTrainingSamples = Math.max(config.effectiveSpectrumNCentroids() * 40, 256);
            this.spectrumTrainingBuffer = new ArrayList<>(minTrainingSamples);
            this.spectrumTrainingIds = new ArrayList<>(minTrainingSamples);
            this.spectrumTrainingContents = new ArrayList<>(minTrainingSamples);
            log.info("Spectrum index created (untrained). Will auto-train after {} vectors.",
                    minTrainingSamples);
        }
    }

    // ─────────────── Core Ingestion ───────────────

    /**
     * Ingests a single document with its text content and vector embedding.
     */
    void ingest(String id, String content, float[] vector) {
        // IVF-PQ auto-training: buffer vectors until we have enough to train
        if (config.indexType() == IndexType.IVF_PQ && !ivfTrained) {
            ivfTrainingBuffer.add(vector.clone());
            ivfTrainingIds.add(id);
            ivfTrainingContents.add(content);

            int minSamples = Math.max(config.effectiveNlist() * 40, 256);
            if (ivfTrainingBuffer.size() >= minSamples) {
                trainAndFlushIvfPq();
            } else {
                documentStore.put(Document.of(id, content));
                keywordIndex.index(id, content);
                return;
            }
            return;
        }

        // Spectrum auto-training: buffer vectors until we have enough to train
        if (config.indexType() == IndexType.SPECTRUM && !spectrumTrained) {
            spectrumTrainingBuffer.add(vector.clone());
            spectrumTrainingIds.add(id);
            spectrumTrainingContents.add(content);

            int minSamples = Math.max(config.effectiveSpectrumNCentroids() * 40, 256);
            if (spectrumTrainingBuffer.size() >= minSamples) {
                trainAndFlushSpectrum();
            } else {
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
     */
    void ingest(String id, String title, String content, float[] vector) {
        int storeIndex = vectorStore.put(id, vector);
        documentStore.put(Document.of(id, title, content));
        vectorIndex.add(id, storeIndex, vector);
        keywordIndex.index(id, title + " " + content);
    }

    /**
     * Ingests a batch of documents.
     */
    void ingestBatch(String[] ids, String[] contents, float[][] vectors) {
        for (int i = 0; i < ids.length; i++) {
            ingest(ids[i], contents[i], vectors[i]);
        }
    }

    /**
     * Deletes a document by ID from all indexes.
     */
    boolean delete(String id) {
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
     */
    int ingestChunked(String id, String content,
                      Function<String, float[]> vectorProvider) {
        return ingestChunked(id, content, vectorProvider, new TextChunker());
    }

    /**
     * Ingests a large document with a custom chunker configuration.
     */
    int ingestChunked(String id, String content,
                      Function<String, float[]> vectorProvider,
                      TextChunker chunker) {
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
     * Ingests structured content by extracting text first.
     */
    void ingestStructured(String id, String content, float[] vector) {
        String extracted = ContentExtractor.extract(content);
        ingest(id, extracted, vector);
    }

    /**
     * Ingests a large file using streaming chunking with bounded memory.
     */
    int ingestFile(java.nio.file.Path path, String documentId,
                   Function<String, float[]> vectorProvider,
                   int chunkSize, int overlap) throws java.io.IOException {
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
     * Ingests a large document using token-level chunking.
     */
    int ingestTokenChunked(String id, String content,
                           Function<String, float[]> vectorProvider,
                           int maxTokens, int overlapTokens) {
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

    /** Ingests a document with automatic embedding generation. */
    void ingest(String id, String content) {
        requireEmbeddingProvider();
        float[] vector = embeddingProvider.embed(content).vector();
        ingest(id, content, vector);
    }

    /** Ingests a document with title and automatic embedding. */
    void ingest(String id, String title, String content) {
        requireEmbeddingProvider();
        float[] vector = embeddingProvider.embed(title + " " + content).vector();
        ingest(id, title, content, vector);
    }

    /** Auto-embed chunked ingestion. */
    int ingestChunkedAuto(String id, String content) {
        requireEmbeddingProvider();
        return ingestChunked(id, content, text -> embeddingProvider.embed(text).vector());
    }

    /** Auto-embed file ingestion. */
    int ingestFileAuto(java.nio.file.Path path, String documentId,
                       int chunkSize, int overlap) throws java.io.IOException {
        requireEmbeddingProvider();
        return ingestFile(path, documentId,
                text -> embeddingProvider.embed(text).vector(), chunkSize, overlap);
    }

    // ─────────────── Training ───────────────

    private void trainAndFlushIvfPq() {
        if (!(vectorIndex instanceof IvfPqIndex ivfPq)) return;

        float[][] trainingData = ivfTrainingBuffer.toArray(float[][]::new);
        log.info("Auto-training IVF-PQ with {} vectors...", trainingData.length);
        ivfPq.train(trainingData);

        for (int i = 0; i < ivfTrainingBuffer.size(); i++) {
            float[] vec = ivfTrainingBuffer.get(i);
            String id = ivfTrainingIds.get(i);
            String content = ivfTrainingContents.get(i);
            int storeIndex = vectorStore.put(id, vec);
            documentStore.put(Document.of(id, content));
            vectorIndex.add(id, storeIndex, vec);
            keywordIndex.index(id, content);
        }

        ivfTrainingBuffer = null;
        ivfTrainingIds = null;
        ivfTrainingContents = null;
        ivfTrained = true;
        log.info("IVF-PQ training complete. {} vectors indexed.", ivfPq.size());
    }

    private void trainAndFlushSpectrum() {
        if (!(vectorIndex instanceof SpectorIndex spectrumIdx)) return;

        float[][] trainingData = spectrumTrainingBuffer.toArray(float[][]::new);
        log.info("Auto-training Spectrum with {} vectors...", trainingData.length);
        spectrumIdx.train(trainingData);

        for (int i = 0; i < spectrumTrainingBuffer.size(); i++) {
            float[] vec = spectrumTrainingBuffer.get(i);
            String bufferedId = spectrumTrainingIds.get(i);
            String content = spectrumTrainingContents.get(i);
            int storeIndex = vectorStore.put(bufferedId, vec);
            documentStore.put(Document.of(bufferedId, content));
            vectorIndex.add(bufferedId, storeIndex, vec);
            keywordIndex.index(bufferedId, content);
        }

        spectrumTrainingBuffer = null;
        spectrumTrainingIds = null;
        spectrumTrainingContents = null;
        spectrumTrained = true;
        log.info("Spectrum training complete. {} vectors indexed.", spectrumIdx.size());
    }

    private void requireEmbeddingProvider() {
        if (embeddingProvider == null) {
            throw new IllegalStateException(
                    "No EmbeddingProvider configured. Use SpectorEngine(config, provider) or supply vectors manually.");
        }
    }
}
