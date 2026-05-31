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
package com.spectrayan.spector.metrics;

import com.spectrayan.spector.config.SpectorConfig;
import com.spectrayan.spector.embed.EmbeddingProvider;
import com.spectrayan.spector.engine.EngineIngestionTarget;
import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.index.VectorIndex;
import com.spectrayan.spector.query.SearchQuery;
import com.spectrayan.spector.query.SearchResponse;
import com.spectrayan.spector.query.ranking.Reranker;
import com.spectrayan.spector.storage.DocumentStore;
import com.spectrayan.spector.storage.VectorStore;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * Metered decorator for {@link SpectorEngine}.
 *
 * <p>Wraps a delegate engine and records Micrometer metrics for all
 * coarse-grained operations (search, ingest, delete). Accessor methods
 * are passed through without instrumentation overhead.</p>
 *
 * <h3>Metrics Registered</h3>
 * <table>
 *   <tr><th>Name</th><th>Type</th><th>Description</th></tr>
 *   <tr><td>{@code spector.engine.search.duration}</td><td>Timer</td><td>Search query latency</td></tr>
 *   <tr><td>{@code spector.engine.search.total}</td><td>Counter</td><td>Total search queries</td></tr>
 *   <tr><td>{@code spector.engine.ingest.duration}</td><td>Timer</td><td>Single-doc ingest latency</td></tr>
 *   <tr><td>{@code spector.engine.ingest.total}</td><td>Counter</td><td>Total ingested documents</td></tr>
 *   <tr><td>{@code spector.engine.delete.total}</td><td>Counter</td><td>Total deletions</td></tr>
 *   <tr><td>{@code spector.engine.documents}</td><td>Gauge</td><td>Current document count</td></tr>
 * </table>
 *
 * @see SpectorEngine
 */
public class MeteredSpectorEngine implements SpectorEngine {

    public static final String METRIC_SEARCH_DURATION = "spector.engine.search.duration";
    public static final String METRIC_INGEST_DURATION = "spector.engine.ingest.duration";
    public static final String METRIC_BATCH_INGEST_DURATION = "spector.engine.ingest.batch.duration";
    public static final String METRIC_SEARCH_TOTAL = "spector.engine.search.total";
    public static final String METRIC_INGEST_TOTAL = "spector.engine.ingest.total";
    public static final String METRIC_DELETE_TOTAL = "spector.engine.delete.total";
    public static final String METRIC_ERRORS_TOTAL = "spector.engine.errors.total";
    public static final String METRIC_DOCUMENTS = "spector.engine.documents";

    private final SpectorEngine delegate;

    // ── Timers ──
    private final Timer searchTimer;
    private final Timer ingestTimer;
    private final Timer batchIngestTimer;

    // ── Counters ──
    private final Counter searchCounter;
    private final Counter ingestCounter;
    private final Counter deleteCounter;
    private final Counter errorCounter;

    /**
     * Creates a metered engine wrapping the given delegate.
     *
     * @param delegate the actual engine implementation
     * @param registry the meter registry to register metrics with
     */
    public MeteredSpectorEngine(SpectorEngine delegate, MeterRegistry registry) {
        this.delegate = delegate;

        // Timers
        this.searchTimer = Timer.builder(METRIC_SEARCH_DURATION)
                .description("Time spent executing search queries")
                .register(registry);
        this.ingestTimer = Timer.builder(METRIC_INGEST_DURATION)
                .description("Time spent ingesting a single document")
                .register(registry);
        this.batchIngestTimer = Timer.builder(METRIC_BATCH_INGEST_DURATION)
                .description("Time spent in batch ingestion")
                .register(registry);

        // Counters
        this.searchCounter = Counter.builder(METRIC_SEARCH_TOTAL)
                .description("Total search queries executed")
                .register(registry);
        this.ingestCounter = Counter.builder(METRIC_INGEST_TOTAL)
                .description("Total documents ingested")
                .register(registry);
        this.deleteCounter = Counter.builder(METRIC_DELETE_TOTAL)
                .description("Total documents deleted")
                .register(registry);
        this.errorCounter = Counter.builder(METRIC_ERRORS_TOTAL)
                .description("Total engine errors")
                .register(registry);

        // Gauges
        Gauge.builder(METRIC_DOCUMENTS, delegate, SpectorEngine::documentCount)
                .description("Current number of indexed documents")
                .register(registry);
    }

    /**
     * Returns the underlying delegate engine.
     */
    public SpectorEngine unwrap() {
        return delegate;
    }

    // ─────────────── Ingestion (metered) ───────────────

    @Override
    public void ingest(String id, String content, float[] vector) {
        ingestCounter.increment();
        ingestTimer.record(() -> delegate.ingest(id, content, vector));
    }

    @Override
    public void ingest(String id, String title, String content, float[] vector) {
        ingestCounter.increment();
        ingestTimer.record(() -> delegate.ingest(id, title, content, vector));
    }

    @Override
    public void ingestBatch(String[] ids, String[] contents, float[][] vectors) {
        ingestCounter.increment(ids.length);
        batchIngestTimer.record(() -> delegate.ingestBatch(ids, contents, vectors));
    }

    @Override
    public boolean delete(String id) {
        deleteCounter.increment();
        return delegate.delete(id);
    }

    @Override
    public int ingestChunked(String id, String content,
                             Function<String, float[]> vectorProvider) {
        return ingestTimer.record(() -> {
            int chunks = delegate.ingestChunked(id, content, vectorProvider);
            ingestCounter.increment(chunks);
            return chunks;
        });
    }

    @Override
    public int ingestChunked(String id, String content,
                             Function<String, float[]> vectorProvider,
                             com.spectrayan.spector.commons.TextChunker chunker) {
        return ingestTimer.record(() -> {
            int chunks = delegate.ingestChunked(id, content, vectorProvider, chunker);
            ingestCounter.increment(chunks);
            return chunks;
        });
    }

    @Override
    public void ingestStructured(String id, String content, float[] vector) {
        ingestCounter.increment();
        ingestTimer.record(() -> delegate.ingestStructured(id, content, vector));
    }

    @Override
    public int ingestFile(Path path, String documentId,
                          Function<String, float[]> vectorProvider,
                          int chunkSize, int overlap) throws IOException {
        // Timer.record doesn't handle checked exceptions, so manual timing
        Timer.Sample sample = Timer.start();
        try {
            int chunks = delegate.ingestFile(path, documentId, vectorProvider, chunkSize, overlap);
            ingestCounter.increment(chunks);
            return chunks;
        } catch (IOException e) {
            errorCounter.increment();
            throw e;
        } finally {
            sample.stop(ingestTimer);
        }
    }

    @Override
    public int ingestTokenChunked(String id, String content,
                                  Function<String, float[]> vectorProvider,
                                  int maxTokens, int overlapTokens) {
        return ingestTimer.record(() -> {
            int chunks = delegate.ingestTokenChunked(id, content, vectorProvider, maxTokens, overlapTokens);
            ingestCounter.increment(chunks);
            return chunks;
        });
    }

    @Override
    public void ingest(String id, String content) {
        ingestCounter.increment();
        ingestTimer.record(() -> delegate.ingest(id, content));
    }

    @Override
    public void ingest(String id, String title, String content) {
        ingestCounter.increment();
        ingestTimer.record(() -> delegate.ingest(id, title, content));
    }

    @Override
    public int ingestChunkedAuto(String id, String content) {
        return ingestTimer.record(() -> {
            int chunks = delegate.ingestChunkedAuto(id, content);
            ingestCounter.increment(chunks);
            return chunks;
        });
    }

    @Override
    public int ingestFileAuto(Path path, String documentId,
                              int chunkSize, int overlap) throws IOException {
        Timer.Sample sample = Timer.start();
        try {
            int chunks = delegate.ingestFileAuto(path, documentId, chunkSize, overlap);
            ingestCounter.increment(chunks);
            return chunks;
        } catch (IOException e) {
            errorCounter.increment();
            throw e;
        } finally {
            sample.stop(ingestTimer);
        }
    }

    // ─────────────── Search (metered) ───────────────

    @Override
    public SearchResponse search(SearchQuery query) {
        searchCounter.increment();
        return searchTimer.record(() -> delegate.search(query));
    }

    @Override
    public SearchResponse keywordSearch(String text, int topK) {
        searchCounter.increment();
        return searchTimer.record(() -> delegate.keywordSearch(text, topK));
    }

    @Override
    public SearchResponse vectorSearch(float[] vector, int topK) {
        searchCounter.increment();
        return searchTimer.record(() -> delegate.vectorSearch(vector, topK));
    }

    @Override
    public SearchResponse hybridSearch(String text, float[] vector, int topK) {
        searchCounter.increment();
        return searchTimer.record(() -> delegate.hybridSearch(text, vector, topK));
    }

    @Override
    public SearchResponse search(String text, int topK) {
        searchCounter.increment();
        return searchTimer.record(() -> delegate.search(text, topK));
    }

    // ─────────────── GPU (pass-through) ───────────────

    @Override
    public float[] batchCosineSimilarity(float[] query, float[] database, int n, int dims) {
        return delegate.batchCosineSimilarity(query, database, n, dims);
    }

    @Override
    public boolean isGpuActive() { return delegate.isGpuActive(); }

    // ─────────────── Accessors (pass-through) ───────────────

    @Override public SpectorConfig config() { return delegate.config(); }
    @Override public int documentCount() { return delegate.documentCount(); }
    @Override public DocumentStore documentStore() { return delegate.documentStore(); }
    @Override public VectorStore vectorStore() { return delegate.vectorStore(); }
    @Override public VectorIndex index() { return delegate.index(); }
    @Override public EmbeddingProvider embeddingProvider() { return delegate.embeddingProvider(); }
    @Override public boolean hasEmbeddingProvider() { return delegate.hasEmbeddingProvider(); }
    @Override public Reranker reranker() { return delegate.reranker(); }
    @Override public boolean isRerankerActive() { return delegate.isRerankerActive(); }
    @Override public EngineIngestionTarget target() { return delegate.target(); }

    // ─────────────── Lifecycle ───────────────

    @Override
    public void close() {
        delegate.close();
    }
}
