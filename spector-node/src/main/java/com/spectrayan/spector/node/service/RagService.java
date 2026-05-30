package com.spectrayan.spector.node.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.spectrayan.spector.commons.TextChunk;
import com.spectrayan.spector.commons.WordTokenizer;
import com.spectrayan.spector.embed.EmbeddingException;
import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.node.api.dto.RagRequest;
import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.commons.error.SpectorApiException;
import com.spectrayan.spector.query.SearchQuery;
import com.spectrayan.spector.query.SearchResponse;
import com.spectrayan.spector.rag.ContextBuilder;
import com.spectrayan.spector.rag.ContextResult;
import com.spectrayan.spector.rag.ScoredChunk;

/**
 * RAG (Retrieval-Augmented Generation) service facade.
 *
 * <p>Wires together the full RAG pipeline:</p>
 * <ol>
 *   <li>Validate and parse request</li>
 *   <li>Embed the query text</li>
 *   <li>Search for relevant chunks (vector or hybrid)</li>
 *   <li>Assemble context within token limits</li>
 *   <li>Return context + attributions</li>
 * </ol>
 */
public class RagService {

    private final SpectorEngine engine;
    private final ContextBuilder contextBuilder;

    public RagService(SpectorEngine engine) {
        this.engine = engine;
        this.contextBuilder = new ContextBuilder();
    }

    /**
     * Executes the RAG pipeline.
     *
     * @param request the RAG request
     * @return a map suitable for JSON serialization
     */
    public Map<String, Object> retrieveContext(RagRequest request) throws com.spectrayan.spector.commons.error.SpectorException {
        request.validate();

        if (!engine.hasEmbeddingProvider()) {
            throw SpectorApiException.serviceUnavailable(ErrorCode.EMBEDDING_UNAVAILABLE, "No embedding provider configured");
        }

        // 1. Embed query
        float[] queryVector;
        try {
            queryVector = engine.embeddingProvider().embed(request.query).vector();
        } catch (EmbeddingException e) {
            throw SpectorApiException.serviceUnavailable(ErrorCode.EMBEDDING_UNAVAILABLE, e.getMessage());
        }

        // 2. Search
        int topK = request.resolvedTopK();
        SearchQuery query = request.isHybrid()
                ? SearchQuery.hybrid(request.query, queryVector, topK)
                : SearchQuery.vector(queryVector, topK);

        SearchResponse searchResponse = engine.search(query);

        if (searchResponse.results() == null || searchResponse.results().length == 0) {
            return emptyContext();
        }

        // 3. Build scored chunks
        List<ScoredChunk> scoredChunks = Arrays.stream(searchResponse.results())
                .map(r -> {
                    var doc = engine.documentStore().get(r.id());
                    if (doc == null || doc.content() == null || doc.content().isBlank()) return null;
                    int tokens = WordTokenizer.countTokens(doc.content());
                    var chunk = new TextChunk(doc.content(), tokens, 0, doc.content().length(), r.id());
                    return new ScoredChunk(chunk, r.score());
                })
                .filter(Objects::nonNull)
                .toList();

        // 4. Assemble context
        ContextResult contextResult = contextBuilder.build(
                new ArrayList<>(scoredChunks), request.resolvedTokenLimit());

        if (contextResult.isEmpty()) {
            return emptyContext();
        }

        // 5. Build response
        var attributions = contextResult.attributions().stream()
                .map(a -> Map.<String, Object>of(
                        "documentId", a.documentId(),
                        "chunkOffset", a.chunkOffset()))
                .toList();

        return Map.of(
                "context", contextResult.contextText(),
                "attributions", attributions
        );
    }

    private static Map<String, Object> emptyContext() {
        return Map.of(
                "context", "",
                "attributions", List.of(),
                "message", "No matching documents were found"
        );
    }
}
