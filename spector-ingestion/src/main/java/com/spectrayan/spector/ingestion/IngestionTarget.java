package com.spectrayan.spector.ingestion;

/**
 * Abstraction for the storage and indexing operations that ingestion writes to.
 *
 * <p>This decouples the ingestion pipeline from the concrete engine implementation,
 * allowing the pipeline to be tested independently and used in different contexts
 * (e.g., bulk offline ingestion, real-time ingestion, or rebuilding indexes).</p>
 */
public interface IngestionTarget {

    /**
     * Stores a vector and returns its storage index.
     *
     * @param id     document or chunk ID
     * @param vector the embedding vector
     * @return the storage index assigned to this vector
     */
    int storeVector(String id, float[] vector);

    /**
     * Stores document metadata.
     *
     * @param id      document ID
     * @param title   document title (may be empty)
     * @param content document text content
     */
    void storeDocument(String id, String title, String content);

    /**
     * Adds a vector to the ANN index.
     *
     * @param id         document or chunk ID
     * @param storeIndex the storage index from {@link #storeVector}
     * @param vector     the embedding vector
     */
    void indexVector(String id, int storeIndex, float[] vector);

    /**
     * Indexes text content for keyword (BM25) search.
     *
     * @param id      document or chunk ID
     * @param content text to index
     */
    void indexKeywords(String id, String content);
}
