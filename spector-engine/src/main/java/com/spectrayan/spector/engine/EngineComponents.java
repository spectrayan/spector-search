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
