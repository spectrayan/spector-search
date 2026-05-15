package com.spectrayan.spector.query.ranking;

import com.spectrayan.spector.index.ScoredResult;
import com.spectrayan.spector.storage.DocumentStore;

/**
 * Service Provider Interface for re-ranking search results.
 *
 * <p>After initial retrieval (HNSW, BM25, or hybrid), a re-ranker can
 * refine the ordering using a more expensive but more accurate scoring
 * model — typically a cross-encoder LLM that considers query-document
 * pairs jointly.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   Reranker reranker = new LlmReranker(ollamaClient, config);
 *   ScoredResult[] refined = reranker.rerank(
 *       "what is HNSW?", candidates, docStore, 10);
 * }</pre>
 *
 * @see LlmReranker
 */
public interface Reranker {

    /**
     * Re-ranks a set of candidate results for a query.
     *
     * @param query      the original query text
     * @param candidates initial retrieval candidates (best-first)
     * @param docStore   document store for fetching document text
     * @param topK       number of results to return after re-ranking
     * @return re-ranked results (best-first), length ≤ topK
     */
    ScoredResult[] rerank(String query, ScoredResult[] candidates,
                           DocumentStore docStore, int topK);

    /**
     * Returns the name of the re-ranking model.
     *
     * @return model identifier
     */
    String modelName();
}
