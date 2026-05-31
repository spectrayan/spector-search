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
package com.spectrayan.spector.index.ivf;

import java.util.Arrays;

/**
 * Per-cell posting list for IVF-Flat indexes.
 *
 * <p>Stores raw float vectors, document IDs, and store indices for all vectors
 * assigned to a single IVF cell. Uses growable arrays internally.</p>
 */
public final class FlatPostingList {

    private static final int INITIAL_CAPACITY = 64;

    private String[] ids;
    private int[] storeIndices;
    private float[][] vectors;
    private int size;

    public FlatPostingList() {
        this.ids = new String[INITIAL_CAPACITY];
        this.storeIndices = new int[INITIAL_CAPACITY];
        this.vectors = new float[INITIAL_CAPACITY][];
        this.size = 0;
    }

    /**
     * Adds a vector entry to this posting list.
     *
     * @param id         document ID
     * @param storeIndex index in the vector store
     * @param vector     raw float vector
     */
    public void add(String id, int storeIndex, float[] vector) {
        if (size == ids.length) {
            grow();
        }
        ids[size] = id;
        storeIndices[size] = storeIndex;
        vectors[size] = vector;
        size++;
    }

    /** Returns the number of entries. */
    public int size() {
        return size;
    }

    /** Returns the document IDs array (may be larger than size). */
    public String[] ids() {
        return ids;
    }

    /** Returns the store indices array. */
    public int[] storeIndices() {
        return storeIndices;
    }

    /** Returns the raw vectors array. */
    public float[][] vectors() {
        return vectors;
    }

    private void grow() {
        int newCap = ids.length * 2;
        ids = Arrays.copyOf(ids, newCap);
        storeIndices = Arrays.copyOf(storeIndices, newCap);
        vectors = Arrays.copyOf(vectors, newCap);
    }
}
