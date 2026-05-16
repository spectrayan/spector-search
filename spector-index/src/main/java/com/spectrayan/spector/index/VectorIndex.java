package com.spectrayan.spector.index;

import com.spectrayan.spector.core.SimilarityFunction;

/**
 * Interface for a vector similarity index.
 *
 * <p>Implementations provide approximate or exact nearest-neighbor search
 * over dense float vectors. The index references vectors stored in a
 * separate {@code VectorStore}.</p>
 */
public interface VectorIndex extends AutoCloseable {

    /**
     * Adds a vector to the index.
     *
     * <p>Read-only implementations (e.g., {@code DiskHnswIndex}) will throw
     * {@link UnsupportedOperationException}. Callers should check
     * {@link #isReadOnly()} before invoking this method.</p>
     *
     * @param id          the vector identifier
     * @param storeIndex  the internal index in the VectorStore
     * @param vector      the float vector data
     * @throws UnsupportedOperationException if this index is read-only
     */
    void add(String id, int storeIndex, float[] vector);

    /**
     * Searches for the k nearest neighbors to the query vector.
     *
     * @param query the query vector
     * @param k     number of results to return
     * @return array of scored results, sorted best-first
     */
    ScoredResult[] search(float[] query, int k);

    /**
     * Returns the number of vectors in the index.
     *
     * @return vector count
     */
    int size();

    /**
     * Returns the similarity function used by this index.
     *
     * @return the similarity function
     */
    SimilarityFunction similarityFunction();

    /**
     * Returns whether this index is read-only.
     *
     * <p>Read-only indexes (e.g., memory-mapped disk indexes) do not support
     * {@link #add} and will throw {@link UnsupportedOperationException}.</p>
     *
     * @return {@code true} if mutation is not supported
     */
    default boolean isReadOnly() {
        return false;
    }
}
