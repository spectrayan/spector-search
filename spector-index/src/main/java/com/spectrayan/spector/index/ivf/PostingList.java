package com.spectrayan.spector.index.ivf;

import java.util.Arrays;

/**
 * Per-cluster posting list for IVF indexes.
 *
 * <p>Stores PQ codes, document IDs, and store indices for all vectors
 * assigned to a single IVF cluster. Uses growable arrays internally.</p>
 */
public final class PostingList {

    private static final int INITIAL_CAPACITY = 64;

    private String[] ids;
    private int[] storeIndices;
    private byte[][] codes;
    private int size;

    public PostingList() {
        this.ids = new String[INITIAL_CAPACITY];
        this.storeIndices = new int[INITIAL_CAPACITY];
        this.codes = new byte[INITIAL_CAPACITY][];
        this.size = 0;
    }

    /**
     * Adds a vector entry to this posting list.
     *
     * @param id         document ID
     * @param storeIndex index in the vector store
     * @param code       PQ code for this vector
     */
    public void add(String id, int storeIndex, byte[] code) {
        if (size == ids.length) {
            grow();
        }
        ids[size] = id;
        storeIndices[size] = storeIndex;
        codes[size] = code;
        size++;
    }

    /** Returns the number of entries. */
    public int size() { return size; }

    /** Returns the document IDs array (may be larger than size). */
    public String[] ids() { return ids; }

    /** Returns the store indices array. */
    public int[] storeIndices() { return storeIndices; }

    /** Returns the PQ codes array. */
    public byte[][] codes() { return codes; }

    /**
     * Finds a document ID by its store index.
     *
     * @param storeIndex the store index to look up
     * @return the document ID, or null if not found
     */
    public String findId(int storeIndex) {
        for (int i = 0; i < size; i++) {
            if (storeIndices[i] == storeIndex) {
                return ids[i];
            }
        }
        return null;
    }

    private void grow() {
        int newCap = ids.length * 2;
        ids = Arrays.copyOf(ids, newCap);
        storeIndices = Arrays.copyOf(storeIndices, newCap);
        codes = Arrays.copyOf(codes, newCap);
    }
}
