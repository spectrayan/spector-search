package com.spectrayan.spector.index;

import java.util.List;

/**
 * Interface for keyword-based text search indexes.
 */
public interface KeywordIndex extends AutoCloseable {

    /**
     * Indexes a document's text content.
     *
     * @param id      the document identifier
     * @param content the text content to index
     */
    void index(String id, String content);

    /**
     * Searches for documents matching the query text.
     *
     * @param query the search query
     * @param k     max results to return
     * @return array of scored results, sorted by relevance (best first)
     */
    ScoredResult[] search(String query, int k);

    /**
     * Returns the number of indexed documents.
     *
     * @return document count
     */
    int size();

    /**
     * Removes a document from the index.
     *
     * @param id the document identifier to remove
     */
    default void remove(String id) {
        // Default no-op; implementations may override for actual deletion.
    }
}
