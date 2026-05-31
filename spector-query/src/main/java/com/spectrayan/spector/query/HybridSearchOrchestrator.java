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
package com.spectrayan.spector.query;

import com.spectrayan.spector.commons.concurrent.ConcurrentExecutionException;
import com.spectrayan.spector.commons.concurrent.ConcurrentTasks;
import com.spectrayan.spector.index.KeywordIndex;
import com.spectrayan.spector.index.ScoredResult;
import com.spectrayan.spector.index.VectorIndex;
import com.spectrayan.spector.query.ranking.Reranker;
import com.spectrayan.spector.storage.DocumentStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Orchestrates hybrid search across keyword and vector indexes.
 *
 * <p>In {@link SearchQuery.SearchMode#HYBRID} mode, keyword and vector searches
 * are executed in parallel using {@link ConcurrentTasks}, then merged via
 * {@link ReciprocalRankFusion}.</p>
 *
 * <h3>Execution Model</h3>
 * <ul>
 *   <li>{@code KEYWORD} — delegates to BM25 index only</li>
 *   <li>{@code VECTOR} — delegates to HNSW index only</li>
 *   <li>{@code HYBRID} — fans out both in parallel, fuses via RRF</li>
 * </ul>
 *
 * <h3>Concurrency</h3>
 * <p>Uses {@link ConcurrentTasks#forkJoinAll} which provides dual-mode concurrency:
 * structured concurrency (JEP 505) with automatic cancellation by default, or
 * classic virtual-thread executor when structured concurrency is disabled via
 * {@code -Dspector.concurrency.structured=false}.</p>
 */
public class HybridSearchOrchestrator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchOrchestrator.class);

    private final KeywordIndex keywordIndex;
    private final VectorIndex vectorIndex;
    private final Reranker reranker;       // nullable
    private final DocumentStore docStore;  // nullable, needed for re-ranking

    /**
     * Creates a hybrid search orchestrator.
     *
     * @param keywordIndex the BM25 keyword index (may be null if vector-only)
     * @param vectorIndex  the HNSW vector index (may be null if keyword-only)
     */
    public HybridSearchOrchestrator(KeywordIndex keywordIndex, VectorIndex vectorIndex) {
        this(keywordIndex, vectorIndex, null, null);
    }

    /**
     * Creates a hybrid search orchestrator with optional LLM re-ranking.
     *
     * @param keywordIndex the BM25 keyword index (may be null)
     * @param vectorIndex  the HNSW vector index (may be null)
     * @param reranker     optional LLM re-ranker (may be null)
     * @param docStore     document store for re-ranker context (may be null)
     */
    public HybridSearchOrchestrator(KeywordIndex keywordIndex, VectorIndex vectorIndex,
                                     Reranker reranker, DocumentStore docStore) {
        this.keywordIndex = keywordIndex;
        this.vectorIndex = vectorIndex;
        this.reranker = reranker;
        this.docStore = docStore;
    }

    /**
     * Executes a search query.
     *
     * @param query the search query
     * @return the search response with fused results
     */
    public SearchResponse search(SearchQuery query) {
        long startTime = System.nanoTime();

        ScoredResult[] results = switch (query.mode()) {
            case KEYWORD -> executeKeywordSearch(query);
            case VECTOR -> executeVectorSearch(query);
            case HYBRID -> executeHybridSearch(query);
        };

        // Optional LLM re-ranking pass
        if (reranker != null && query.text() != null && results.length > 0) {
            try {
                results = reranker.rerank(query.text(), results, docStore, query.topK());
                log.debug("Re-ranked {} results with {}", results.length, reranker.modelName());
            } catch (Exception e) {
                log.warn("Re-ranking failed, using original order: {}", e.getMessage());
            }
        }

        long elapsed = (System.nanoTime() - startTime) / 1_000_000;

        log.debug("Search completed: mode={}, results={}, timeMs={}",
                query.mode(), results.length, elapsed);

        return new SearchResponse(results, results.length, elapsed, query.mode());
    }

    @Override
    public void close() {
        // No executor to close — ConcurrentTasks manages scope per-call
    }

    // ─────────────── Mode handlers ───────────────

    private ScoredResult[] executeKeywordSearch(SearchQuery query) {
        if (keywordIndex == null || query.text() == null) {
            return new ScoredResult[0];
        }
        return keywordIndex.search(query.text(), query.topK());
    }

    private ScoredResult[] executeVectorSearch(SearchQuery query) {
        if (vectorIndex == null || query.vector() == null) {
            return new ScoredResult[0];
        }
        return vectorIndex.search(query.vector(), query.topK());
    }

    /**
     * Executes hybrid search: parallel fan-out → RRF fusion.
     *
     * <p>Uses {@link ConcurrentTasks#forkJoin2} for zero-allocation parallel execution.
     * In structured concurrency mode, if either sub-search fails, the other is
     * automatically cancelled — preventing thread leaks.</p>
     */
    private ScoredResult[] executeHybridSearch(SearchQuery query) {
        boolean hasKeyword = keywordIndex != null && query.text() != null;
        boolean hasVector = vectorIndex != null && query.vector() != null;

        if (!hasKeyword && !hasVector) return new ScoredResult[0];
        if (!hasKeyword) return executeVectorSearch(query);
        if (!hasVector) return executeKeywordSearch(query);

        // Expand retrieval window for better fusion
        int retrievalK = Math.max(query.topK() * 2, 50);

        try {
            var pair = ConcurrentTasks.forkJoin2(
                    () -> keywordIndex.search(query.text(), retrievalK),
                    () -> vectorIndex.search(query.vector(), retrievalK)
            );

            return ReciprocalRankFusion.fuse(
                    new ScoredResult[][]{pair.first(), pair.second()},
                    query.topK()
            );

        } catch (ConcurrentExecutionException e) {
            log.error("Hybrid search failed", e.getCause());
            return new ScoredResult[0];
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Hybrid search interrupted", e);
            return new ScoredResult[0];
        }
    }
}
