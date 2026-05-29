package com.spectrayan.spector.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.config.SpectorConfig;
import com.spectrayan.spector.core.similarity.SimilarityFunction;
import com.spectrayan.spector.embed.EmbeddingProvider;
import com.spectrayan.spector.gpu.GpuBatchSimilarity;
import com.spectrayan.spector.index.VectorIndex;
import com.spectrayan.spector.query.HybridSearchOrchestrator;
import com.spectrayan.spector.query.SearchQuery;
import com.spectrayan.spector.query.SearchResponse;

/**
 * Handles all search logic for the Spector engine.
 *
 * <p>Extracted from {@link SpectorEngine} to decompose the god class into
 * focused, single-responsibility components. Manages:</p>
 * <ul>
 *   <li>Hybrid, keyword, and vector search via {@link HybridSearchOrchestrator}</li>
 *   <li>Auto-embed search (text → embedding → hybrid search)</li>
 *   <li>GPU-accelerated batch similarity (with CPU fallback)</li>
 * </ul>
 */
final class EngineSearch {

    private static final Logger log = LoggerFactory.getLogger(EngineSearch.class);

    private final SpectorConfig config;
    private final HybridSearchOrchestrator orchestrator;
    private final EmbeddingProvider embeddingProvider; // nullable
    private final GpuBatchSimilarity gpuBatchSimilarity; // nullable

    EngineSearch(SpectorConfig config, HybridSearchOrchestrator orchestrator,
                 EmbeddingProvider embeddingProvider, GpuBatchSimilarity gpuBatchSimilarity) {
        this.config = config;
        this.orchestrator = orchestrator;
        this.embeddingProvider = embeddingProvider;
        this.gpuBatchSimilarity = gpuBatchSimilarity;
    }

    // ─────────────── Search ───────────────

    /** Executes a search query. */
    SearchResponse search(SearchQuery query) {
        return orchestrator.search(query);
    }

    /** Convenience: keyword search. */
    SearchResponse keywordSearch(String text, int topK) {
        return search(SearchQuery.keyword(text, topK));
    }

    /** Convenience: vector search. */
    SearchResponse vectorSearch(float[] vector, int topK) {
        return search(SearchQuery.vector(vector, topK));
    }

    /** Convenience: hybrid search. */
    SearchResponse hybridSearch(String text, float[] vector, int topK) {
        return search(SearchQuery.hybrid(text, vector, topK));
    }

    /**
     * Auto-embed search: embeds the query text and performs hybrid search.
     */
    SearchResponse search(String text, int topK) {
        requireEmbeddingProvider();
        float[] queryVector = embeddingProvider.embed(text).vector();
        return hybridSearch(text, queryVector, topK);
    }

    // ─────────────── GPU-Accelerated Batch Operations ───────────────

    /**
     * Computes batch cosine similarities using GPU if available, CPU SIMD otherwise.
     */
    float[] batchCosineSimilarity(float[] query, float[] database, int n, int dims) {
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
    boolean isGpuActive() {
        return gpuBatchSimilarity != null;
    }

    HybridSearchOrchestrator orchestrator() {
        return orchestrator;
    }

    private void requireEmbeddingProvider() {
        if (embeddingProvider == null) {
            throw new IllegalStateException(
                    "No EmbeddingProvider configured. Use SpectorEngine(config, provider) or supply vectors manually.");
        }
    }
}
