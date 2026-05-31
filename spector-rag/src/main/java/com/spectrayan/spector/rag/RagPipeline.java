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
package com.spectrayan.spector.rag;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.commons.TextChunk;
import com.spectrayan.spector.commons.WordTokenizer;
import com.spectrayan.spector.embed.EmbeddingProvider;
import com.spectrayan.spector.index.ScoredResult;
import com.spectrayan.spector.query.HybridSearchOrchestrator;
import com.spectrayan.spector.query.SearchQuery;
import com.spectrayan.spector.query.SearchResponse;
import com.spectrayan.spector.storage.Document;
import com.spectrayan.spector.storage.DocumentStore;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.commons.error.SpectorServerException;

/**
 * Full RAG pipeline orchestrator: query → embed → retrieve → assemble context.
 *
 * <p>Coordinates the end-to-end RAG flow using synchronous calls on virtual threads.
 * No reactive framework needed — virtual threads handle the I/O-bound embedding call
 * efficiently while the search and assembly steps are CPU-bound and fast.</p>
 *
 * <h3>Pipeline Steps</h3>
 * <ol>
 *   <li>Validate request</li>
 *   <li>Embed the query text via {@link EmbeddingProvider}</li>
 *   <li>Search via {@link HybridSearchOrchestrator} (vector or hybrid mode)</li>
 *   <li>Fetch document content from {@link DocumentStore}</li>
 *   <li>Assemble context via {@link ContextBuilder} within token budget</li>
 *   <li>Return {@link RagResponse} with attributions</li>
 * </ol>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   var pipeline = new RagPipeline(searchOrchestrator, documentStore, embeddingProvider);
 *   RagResponse response = pipeline.execute(new RagRequest("What is HNSW?"));
 * }</pre>
 */
public class RagPipeline {

    private static final Logger log = LoggerFactory.getLogger(RagPipeline.class);

    private static final int MAX_QUERY_LENGTH = 2000;

    private final HybridSearchOrchestrator searchOrchestrator;
    private final DocumentStore documentStore;
    private final EmbeddingProvider embeddingProvider;
    private final ContextBuilder contextBuilder;

    /**
     * Creates a RAG pipeline.
     *
     * @param searchOrchestrator the hybrid search orchestrator
     * @param documentStore      document store for retrieving content
     * @param embeddingProvider  embedding provider for query vectorization
     */
    public RagPipeline(HybridSearchOrchestrator searchOrchestrator,
                       DocumentStore documentStore,
                       EmbeddingProvider embeddingProvider) {
        this.searchOrchestrator = searchOrchestrator;
        this.documentStore = documentStore;
        this.embeddingProvider = embeddingProvider;
        this.contextBuilder = new ContextBuilder();
    }

    /**
     * Executes the full RAG pipeline for the given request.
     *
     * @param request the RAG request
     * @return RAG response with assembled context and attributions
     * @throws SpectorValidationException if the request is invalid
     * @throws SpectorServerException     if the embedding provider is unavailable
     */
    public RagResponse execute(RagRequest request) {
        long start = System.nanoTime();

        // Validate
        validate(request);

        int topK = request.resolvedTopK();
        int tokenLimit = request.resolvedTokenLimit();
        String searchMode = request.resolvedSearchMode();

        // Embed the query
        float[] queryVector;
        try {
            queryVector = embeddingProvider.embed(request.query()).vector();
        } catch (Exception e) {
            log.warn("Embedding failed for RAG query: {}", e.getMessage());
            throw new SpectorServerException(ErrorCode.EMBEDDING_UNAVAILABLE, e);
        }

        // Search
        SearchQuery query = buildSearchQuery(request.query(), queryVector, topK, searchMode);
        SearchResponse searchResponse = searchOrchestrator.search(query);

        long elapsed = (System.nanoTime() - start) / 1_000_000;

        // No results
        if (searchResponse.results() == null || searchResponse.results().length == 0) {
            return RagResponse.empty(elapsed);
        }

        // Convert to scored chunks
        List<ScoredChunk> scoredChunks = toScoredChunks(searchResponse.results());

        if (scoredChunks.isEmpty()) {
            return RagResponse.empty(elapsed);
        }

        // Assemble context
        ContextResult contextResult = contextBuilder.build(scoredChunks, tokenLimit);

        elapsed = (System.nanoTime() - start) / 1_000_000;

        if (contextResult.isEmpty()) {
            return RagResponse.empty(elapsed);
        }

        // Map attributions
        List<RagResponse.Attribution> attributions = contextResult.attributions().stream()
                .map(attr -> new RagResponse.Attribution(attr.documentId(), attr.chunkOffset()))
                .toList();

        return new RagResponse(contextResult.contextText(), attributions, null, elapsed);
    }

    private void validate(RagRequest request) {
        if (request.query() == null || request.query().isBlank()) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "query");
        }
        if (request.query().length() > MAX_QUERY_LENGTH) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "query.length", 1, MAX_QUERY_LENGTH, 0);
        }
    }

    private SearchQuery buildSearchQuery(String text, float[] vector, int topK, String searchMode) {
        if ("hybrid".equals(searchMode)) {
            return SearchQuery.hybrid(text, vector, topK);
        }
        return SearchQuery.vector(vector, topK);
    }

    private List<ScoredChunk> toScoredChunks(ScoredResult[] results) {
        List<ScoredChunk> chunks = new ArrayList<>(results.length);
        for (ScoredResult result : results) {
            Document document = documentStore.get(result.id());
            if (document == null) continue;

            String content = document.content();
            if (content == null || content.isBlank()) continue;

            int tokenCount = WordTokenizer.countTokens(content);
            TextChunk textChunk = new TextChunk(content, tokenCount, 0, content.length(), result.id());
            chunks.add(new ScoredChunk(textChunk, result.score()));
        }
        return chunks;
    }
}