package org.springframework.ai.vectorstore.spector.rag;

import com.spectrayan.spector.rag.ChunkAttribution;

import java.util.List;

/**
 * Result of a RAG retrieval operation from {@link SpectorRagService}.
 *
 * @param documents    the scored documents matching the query, ordered by descending relevance
 * @param contextText  the assembled context string from matched documents
 * @param attributions source attribution entries for each included chunk
 */
public record RetrievalResult(
        List<ScoredDocument> documents,
        String contextText,
        List<ChunkAttribution> attributions
) {

    public RetrievalResult {
        if (documents == null) {
            throw new IllegalArgumentException("documents must not be null");
        }
        if (contextText == null) {
            throw new IllegalArgumentException("contextText must not be null");
        }
        if (attributions == null) {
            throw new IllegalArgumentException("attributions must not be null");
        }
        documents = List.copyOf(documents);
        attributions = List.copyOf(attributions);
    }

    /**
     * Creates an empty retrieval result indicating no relevant documents were found.
     */
    public static RetrievalResult empty() {
        return new RetrievalResult(List.of(), "", List.of());
    }

    /**
     * Returns true if no documents were found meeting the similarity threshold.
     */
    public boolean isEmpty() {
        return documents.isEmpty();
    }
}
