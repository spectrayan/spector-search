package com.spectrayan.spector.server;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.commons.TextChunk;
import com.spectrayan.spector.embed.EmbeddingException;
import com.spectrayan.spector.embed.ParallelEmbeddingPipeline;
import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.engine.rag.ContextBuilder;
import com.spectrayan.spector.engine.rag.ContextResult;
import com.spectrayan.spector.engine.rag.ScoredChunk;
import com.spectrayan.spector.index.ScoredResult;
import com.spectrayan.spector.query.SearchQuery;
import com.spectrayan.spector.query.SearchResponse;

/**
 * Handler for the RAG (Retrieval-Augmented Generation) endpoint.
 *
 * <p>Wires together the existing components:</p>
 * <ul>
 *   <li>{@link SpectorEngine} — for vector/hybrid search</li>
 *   <li>{@link ParallelEmbeddingPipeline} — for query embedding</li>
 *   <li>{@link ContextBuilder} — for assembling context within token limits</li>
 * </ul>
 *
 * <p>Validates: Requirements 9.1, 9.2, 9.3, 9.4, 9.5</p>
 */
public class RagHandler {

    private static final Logger log = LoggerFactory.getLogger(RagHandler.class);

    private static final int MIN_QUERY_LENGTH = 1;
    private static final int MAX_QUERY_LENGTH = 2000;
    private static final int DEFAULT_TOP_K = 5;
    private static final int MIN_TOP_K = 1;
    private static final int MAX_TOP_K = 100;
    private static final int DEFAULT_TOKEN_LIMIT = 4096;
    private static final int MIN_TOKEN_LIMIT = 1;
    private static final int MAX_TOKEN_LIMIT = 8192;

    private final SpectorEngine engine;
    private final ContextBuilder contextBuilder;

    /**
     * Creates a RAG handler backed by the given engine.
     *
     * @param engine the Spector engine instance
     */
    public RagHandler(SpectorEngine engine) {
        this.engine = engine;
        this.contextBuilder = new ContextBuilder();
    }

    /**
     * Processes a RAG request and returns the assembled context with attributions.
     *
     * @param request the RAG request
     * @return a result containing either a successful response or an error
     */
    public RagResult handle(RagRequest request) {
        // Validate query (Requirement 9.5)
        if (request.query == null || request.query.isBlank()) {
            return RagResult.error(400, "A non-empty query is required");
        }
        if (request.query.length() > MAX_QUERY_LENGTH) {
            return RagResult.error(400,
                    "Query must not exceed " + MAX_QUERY_LENGTH + " characters");
        }

        // Resolve parameters with defaults (Requirement 9.2)
        int topK = resolveTopK(request.topK);
        int tokenLimit = resolveTokenLimit(request.tokenLimit);
        String searchMode = resolveSearchMode(request.searchMode);

        // Check embedding provider availability (Requirement 9.4)
        if (!engine.hasEmbeddingProvider()) {
            return RagResult.error(503, "Embedding service is unavailable");
        }

        // Embed the query
        float[] queryVector;
        try {
            queryVector = engine.embeddingProvider().embed(request.query).vector();
        } catch (EmbeddingException e) {
            log.warn("Embedding failed for RAG query: {}", e.getMessage());
            return RagResult.error(503, "Embedding service is unavailable");
        } catch (Exception e) {
            log.error("Unexpected error during query embedding", e);
            return RagResult.error(503, "Embedding service is unavailable");
        }

        // Search using the engine
        SearchResponse searchResponse;
        try {
            SearchQuery query = buildSearchQuery(request.query, queryVector, topK, searchMode);
            searchResponse = engine.search(query);
        } catch (Exception e) {
            log.error("Search failed for RAG query", e);
            return RagResult.error(500, "Search failed: " + e.getMessage());
        }

        // If no results, return empty context (Requirement 9.3)
        if (searchResponse.results() == null || searchResponse.results().length == 0) {
            RagResponse response = new RagResponse(
                    "",
                    List.of(),
                    "No matching documents were found"
            );
            return RagResult.success(response);
        }

        // Convert search results to ScoredChunks for context building
        List<ScoredChunk> scoredChunks = buildScoredChunks(searchResponse.results());

        // Build context within token limit (Requirement 9.1)
        ContextResult contextResult = contextBuilder.build(scoredChunks, tokenLimit);

        // Handle empty context after filtering
        if (contextResult.isEmpty()) {
            RagResponse response = new RagResponse(
                    "",
                    List.of(),
                    "No matching documents were found"
            );
            return RagResult.success(response);
        }

        // Map attributions to response format
        List<RagResponse.Attribution> attributions = contextResult.attributions().stream()
                .map(attr -> new RagResponse.Attribution(attr.documentId(), attr.chunkOffset()))
                .toList();

        RagResponse response = new RagResponse(
                contextResult.contextText(),
                attributions,
                null
        );
        return RagResult.success(response);
    }

    private int resolveTopK(Integer topK) {
        if (topK == null) return DEFAULT_TOP_K;
        return Math.max(MIN_TOP_K, Math.min(MAX_TOP_K, topK));
    }

    private int resolveTokenLimit(Integer tokenLimit) {
        if (tokenLimit == null) return DEFAULT_TOKEN_LIMIT;
        return Math.max(MIN_TOKEN_LIMIT, Math.min(MAX_TOKEN_LIMIT, tokenLimit));
    }

    private String resolveSearchMode(String mode) {
        if (mode == null || mode.isBlank()) return "vector";
        String normalized = mode.toLowerCase().trim();
        if ("hybrid".equals(normalized)) return "hybrid";
        return "vector";
    }

    private SearchQuery buildSearchQuery(String text, float[] vector, int topK, String searchMode) {
        if ("hybrid".equals(searchMode)) {
            return SearchQuery.hybrid(text, vector, topK);
        }
        return SearchQuery.vector(vector, topK);
    }

    /**
     * Converts search results into ScoredChunks for context assembly.
     *
     * <p>Each result is treated as a chunk whose content is retrieved from the
     * engine's document store. If the document content cannot be found, the
     * result is skipped.</p>
     */
    private List<ScoredChunk> buildScoredChunks(ScoredResult[] results) {
        List<ScoredChunk> chunks = new ArrayList<>(results.length);
        for (ScoredResult result : results) {
            String id = result.id();
            // Retrieve document content from the document store
            var document = engine.documentStore().get(id);
            if (document == null) {
                continue;
            }
            String content = document.content();
            if (content == null || content.isBlank()) {
                continue;
            }

            // Create a TextChunk from the document content
            int tokenCount = com.spectrayan.spector.commons.WordTokenizer.countTokens(content);
            TextChunk textChunk = new TextChunk(content, tokenCount, 0, content.length(), id);
            chunks.add(new ScoredChunk(textChunk, result.score()));
        }
        return chunks;
    }

    /**
     * Encapsulates either a successful RAG response or an error.
     */
    public record RagResult(int statusCode, RagResponse response, String errorMessage) {

        public static RagResult success(RagResponse response) {
            return new RagResult(200, response, null);
        }

        public static RagResult error(int statusCode, String message) {
            return new RagResult(statusCode, null, message);
        }

        public boolean isSuccess() {
            return errorMessage == null;
        }
    }
}
