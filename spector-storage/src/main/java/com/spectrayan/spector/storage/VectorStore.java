package com.spectrayan.spector.storage;

/**
 * Abstraction for storing and retrieving dense float vectors by string ID.
 *
 * <p>Implementations may use on-heap arrays, off-heap Panama {@code MemorySegment}s,
 * or memory-mapped files. All implementations must be safe for concurrent reads
 * from virtual threads when using a shared arena.</p>
 */
public interface VectorStore extends AutoCloseable {

    /**
     * Stores a vector under the given ID, replacing any existing entry.
     *
     * @param id     unique identifier for the vector
     * @param vector the float array (must match the store's configured dimensions)
     * @return the internal integer index assigned to this vector
     * @throws IllegalArgumentException if vector dimensions don't match
     * @throws IllegalStateException    if the store is full or closed
     */
    int put(String id, float[] vector);

    /**
     * Retrieves the vector for the given ID.
     *
     * @param id the vector identifier
     * @return a copy of the stored float array, or {@code null} if not found
     */
    float[] get(String id);

    /**
     * Retrieves the vector at the given internal index.
     *
     * @param index the internal integer index (returned by {@link #put})
     * @return a copy of the stored float array
     * @throws IndexOutOfBoundsException if index is invalid
     */
    float[] getByIndex(int index);

    /**
     * Retrieves the vector at the given internal index into an existing buffer.
     *
     * @param index     the internal integer index
     * @param dst       destination array
     * @param dstOffset offset into destination
     * @throws IndexOutOfBoundsException if index is invalid
     */
    void getByIndex(int index, float[] dst, int dstOffset);

    /**
     * Returns the internal index for a given ID, or -1 if not found.
     *
     * @param id the vector identifier
     * @return internal index or -1
     */
    int indexOf(String id);

    /**
     * Returns the number of vectors currently stored.
     *
     * @return vector count
     */
    int size();

    /**
     * Returns the dimensionality of vectors in this store.
     *
     * @return number of float elements per vector
     */
    int dimensions();

    /**
     * Returns the maximum capacity of this store.
     *
     * @return maximum number of vectors
     */
    int capacity();

    /**
     * Returns whether this store has been closed.
     *
     * @return true if closed
     */
    boolean isClosed();
}
