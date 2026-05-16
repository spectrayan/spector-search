package com.spectrayan.spector.engine;

import com.spectrayan.spector.index.KeywordIndex;
import com.spectrayan.spector.index.VectorIndex;
import com.spectrayan.spector.query.ranking.Reranker;
import com.spectrayan.spector.storage.DocumentStore;
import com.spectrayan.spector.storage.VectorStore;

/**
 * Immutable container for the assembled engine components.
 *
 * <p>Produced by {@link EngineComponentFactory} as part of the Abstract
 * Factory pattern. Groups all subsystems required by {@link SpectorEngine}
 * into a single transferable unit.</p>
 *
 * @param vectorStore   off-heap vector storage
 * @param documentStore document metadata store
 * @param vectorIndex   ANN vector index (HNSW, QuantizedHNSW, or IVF-PQ)
 * @param keywordIndex  BM25 keyword index
 * @param reranker      LLM re-ranker (nullable)
 * @param gpuBatch      GPU batch similarity (nullable)
 */
public record EngineComponents(
        VectorStore vectorStore,
        DocumentStore documentStore,
        VectorIndex vectorIndex,
        KeywordIndex keywordIndex,
        Reranker reranker,
        Object gpuBatch  // GpuBatchSimilarity — Object to avoid hard dependency
) implements AutoCloseable {

    @Override
    public void close() throws Exception {
        vectorIndex.close();
        keywordIndex.close();
        vectorStore.close();
        documentStore.close();
        if (gpuBatch instanceof AutoCloseable ac) {
            ac.close();
        }
    }
}
