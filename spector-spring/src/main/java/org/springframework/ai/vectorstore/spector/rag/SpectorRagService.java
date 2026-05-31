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
package org.springframework.ai.vectorstore.spector.rag;

import com.spectrayan.spector.commons.TextChunk;
import com.spectrayan.spector.commons.WordTokenizer;
import com.spectrayan.spector.rag.ContextBuilder;
import com.spectrayan.spector.rag.ContextResult;
import com.spectrayan.spector.rag.ScoredChunk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.spector.SpectorVectorStore;
import org.springframework.ai.vectorstore.spector.SpectorVectorStoreException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Spring AI RAG service that integrates Spector vector retrieval with
 * context assembly for retrieval-augmented generation.
 *
 * <p>Delegates vector retrieval to {@link SpectorVectorStore} and context assembly
 * to {@link ContextBuilder}. Supports configurable topK, similarity threshold,
 * and token limit via {@link RagConfig}.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   var ragService = new SpectorRagService(vectorStore, contextBuilder);
 *   RetrievalResult result = ragService.retrieve(queryEmbedding, RagConfig.defaults());
 * }</pre>
 */
public class SpectorRagService {

    private static final Logger LOG = LoggerFactory.getLogger(SpectorRagService.class);

    private final SpectorVectorStore vectorStore;
    private final ContextBuilder contextBuilder;

    /**
     * Creates a SpectorRagService with the given vector store and context builder.
     *
     * @param vectorStore    the vector store for similarity search
     * @param contextBuilder the context builder for assembling retrieval context
     * @throws SpectorValidationException if vectorStore or contextBuilder is null
     */
    public SpectorRagService(SpectorVectorStore vectorStore, ContextBuilder contextBuilder) {
        if (vectorStore == null) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "vectorStore");
        }
        if (contextBuilder == null) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "contextBuilder");
        }
        this.vectorStore = vectorStore;
        this.contextBuilder = contextBuilder;
    }

    /**
     * Retrieves documents relevant to the given query embedding using the provided configuration.
     *
     * <p>Performs a similarity search through the vector store, filters results by the
     * similarity threshold, and assembles a context string within the configured token limit.</p>
     *
     * @param queryEmbedding the query vector embedding to search for
     * @param config         the RAG configuration (topK, threshold, tokenLimit)
     * @return the retrieval result containing scored documents, context text, and attributions
     * @throws SpectorValidationException if queryEmbedding is null/empty or config is null
     * @throws SpectorRagServiceException if a dependency (vector store or context builder) fails
     */
    public RetrievalResult retrieve(float[] queryEmbedding, RagConfig config) {
        if (queryEmbedding == null || queryEmbedding.length == 0) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "queryEmbedding");
        }
        if (config == null) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "config");
        }

        List<Document> searchResults;
        try {
            searchResults = vectorStore.similaritySearch(
                    queryEmbedding, config.topK(), config.similarityThreshold(), null);
        } catch (SpectorVectorStoreException e) {
            throw new SpectorRagServiceException(
                    "Vector store unavailable: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new SpectorRagServiceException(
                    "Failed to perform similarity search: " + e.getMessage(), e);
        }

        if (searchResults == null || searchResults.isEmpty()) {
            LOG.debug("No documents found meeting similarity threshold {}", config.similarityThreshold());
            return RetrievalResult.empty();
        }

        // Convert Spring AI Documents to ScoredChunks for context assembly
        List<ScoredChunk> scoredChunks = new ArrayList<>(searchResults.size());
        List<ScoredDocument> scoredDocuments = new ArrayList<>(searchResults.size());

        for (Document doc : searchResults) {
            float score = extractScore(doc);

            // Clamp score to [0.0, 1.0] range
            score = Math.max(0.0f, Math.min(1.0f, score));

            String docId = doc.getId() != null ? doc.getId() : "unknown";
            String content = doc.getText() != null ? doc.getText() : "";
            int chunkOffset = extractChunkOffset(doc);

            scoredDocuments.add(new ScoredDocument(docId, content, score, chunkOffset));

            // Create a TextChunk for context building
            TextChunk textChunk = new TextChunk(
                    content,
                    countTokens(content),
                    chunkOffset,
                    chunkOffset + content.length(),
                    docId
            );
            scoredChunks.add(new ScoredChunk(textChunk, score));
        }

        // Assemble context using ContextBuilder
        ContextResult contextResult;
        try {
            // Use a token limit that fits the ContextBuilder's valid range [256, 131072]
            int effectiveTokenLimit = Math.max(256, config.tokenLimit());
            contextResult = contextBuilder.build(scoredChunks, effectiveTokenLimit);
        } catch (Exception e) {
            throw new SpectorRagServiceException(
                    "Failed to assemble context: " + e.getMessage(), e);
        }

        return new RetrievalResult(
                scoredDocuments,
                contextResult.contextText(),
                contextResult.attributions()
        );
    }

    /**
     * Extracts the relevance score from a Spring AI Document.
     */
    private float extractScore(Document doc) {
        // First try Document.getScore()
        Double score = doc.getScore();
        if (score != null) {
            return score.floatValue();
        }
        // Fallback to metadata
        Map<String, Object> metadata = doc.getMetadata();
        if (metadata != null) {
            Object scoreObj = metadata.get("score");
            if (scoreObj instanceof Number num) {
                return num.floatValue();
            }
        }
        return 0.0f;
    }

    /**
     * Extracts the chunk offset from a Spring AI Document's metadata, defaulting to 0.
     */
    private int extractChunkOffset(Document doc) {
        Map<String, Object> metadata = doc.getMetadata();
        if (metadata != null) {
            Object offsetObj = metadata.get("chunkOffset");
            if (offsetObj instanceof Number num) {
                return num.intValue();
            }
        }
        return 0;
    }

    /**
     * Counts tokens using the same method as the Chunking Engine and ContextBuilder.
     */
    private int countTokens(String text) {
        return WordTokenizer.countTokens(text);
    }
}
