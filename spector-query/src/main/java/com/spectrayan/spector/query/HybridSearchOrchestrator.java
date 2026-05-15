package com.spectrayan.spector.query;

import com.spectrayan.spector.index.KeywordIndex;
import com.spectrayan.spector.index.ScoredResult;
import com.spectrayan.spector.index.VectorIndex;
import com.spectrayan.spector.query.ranking.Reranker;
import com.spectrayan.spector.storage.DocumentStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Orchestrates hybrid search across keyword and vector indexes.
 *
 * <p>In {@link SearchQuery.SearchMode#HYBRID} mode, keyword and vector searches
 * are executed in parallel on virtual threads, then merged via
 * {@link ReciprocalRankFusion}.</p>
 *
 * <h3>Execution Model</h3>
 * <ul>
 *   <li>{@code KEYWORD} — delegates to BM25 index only</li>
 *   <li>{@code VECTOR} — delegates to HNSW index only</li>
 *   <li>{@code HYBRID} — fans out both in parallel, fuses via RRF</li>
 * </ul>
 *
 * <h3>Performance</h3>
 * <p>Uses a shared virtual-thread executor to avoid per-query lifecycle overhead.
 * Virtual threads are extremely cheap (~few hundred bytes each), so a shared
 * unbounded executor with per-task threads is optimal.</p>
 */
public class HybridSearchOrchestrator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchOrchestrator.class);

    private final KeywordIndex keywordIndex;
    private final VectorIndex vectorIndex;
    private final ExecutorService executor;
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
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
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
        executor.close();
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
     * <p>Uses the shared virtual-thread executor for lightweight parallelism.
     * Each sub-search runs on its own virtual thread for maximum concurrency.</p>
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
            Future<ScoredResult[]> keywordFuture = executor.submit(
                    () -> keywordIndex.search(query.text(), retrievalK));
            Future<ScoredResult[]> vectorFuture = executor.submit(
                    () -> vectorIndex.search(query.vector(), retrievalK));

            ScoredResult[] keywordResults = keywordFuture.get();
            ScoredResult[] vectorResults = vectorFuture.get();

            return ReciprocalRankFusion.fuse(
                    new ScoredResult[][]{keywordResults, vectorResults},
                    query.topK()
            );

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Hybrid search interrupted", e);
            return new ScoredResult[0];
        } catch (ExecutionException e) {
            log.error("Hybrid search failed", e.getCause());
            return new ScoredResult[0];
        }
    }
}

