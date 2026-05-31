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
package com.spectrayan.spector.index;

import java.util.Arrays;
import java.util.Comparator;
import com.spectrayan.spector.commons.error.SpectorInternalException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * A bounded priority queue for HNSW candidate tracking during search and construction.
 *
 * <p>Internally backed by a simple array-based binary heap. Supports both min-heap
 * and max-heap configurations. When used as a max-heap with a bound, it efficiently
 * tracks the top-K nearest neighbors by evicting the worst candidate when full.</p>
 */
public final class NeighborQueue {

    private int[] indices;
    private float[] scores;
    private int size;
    private final int maxSize;
    private final boolean minHeap; // true = min-heap (smallest on top), false = max-heap

    /**
     * Creates an unbounded neighbor queue.
     *
     * @param initialCapacity initial array size
     * @param minHeap         true for min-heap, false for max-heap
     */
    public NeighborQueue(int initialCapacity, boolean minHeap) {
        this(initialCapacity, Integer.MAX_VALUE, minHeap);
    }

    /**
     * Creates a bounded neighbor queue.
     *
     * @param initialCapacity initial array size
     * @param maxSize         maximum number of elements (0 = unlimited)
     * @param minHeap         true for min-heap, false for max-heap
     */
    public NeighborQueue(int initialCapacity, int maxSize, boolean minHeap) {
        this.indices = new int[initialCapacity];
        this.scores = new float[initialCapacity];
        this.size = 0;
        this.maxSize = maxSize;
        this.minHeap = minHeap;
    }

    /**
     * Inserts a candidate. If bounded and full, the worst element is evicted
     * only if the new candidate is better.
     *
     * @param index the vector index
     * @param score the similarity/distance score
     * @return true if the candidate was inserted
     */
    public boolean add(int index, float score) {
        if (size < maxSize) {
            insertAndSiftUp(index, score);
            return true;
        }
        // Bounded and full — check if better than worst (top of heap)
        if (isBetterThanTop(score)) {
            // Replace top and sift down
            indices[0] = index;
            scores[0] = score;
            siftDown(0);
            return true;
        }
        return false;
    }

    /** Returns the score at the top of the heap (worst in a max-heap of top-K). */
    public float topScore() {
        if (size == 0) throw new SpectorInternalException(ErrorCode.EMPTY_COLLECTION, "queue");
        return scores[0];
    }

    /** Returns the index at the top of the heap. */
    public int topIndex() {
        if (size == 0) throw new SpectorInternalException(ErrorCode.EMPTY_COLLECTION, "queue");
        return indices[0];
    }

    /** Removes and returns the top element. */
    public int poll() {
        if (size == 0) throw new SpectorInternalException(ErrorCode.EMPTY_COLLECTION, "queue");
        int result = indices[0];
        size--;
        if (size > 0) {
            indices[0] = indices[size];
            scores[0] = scores[size];
            siftDown(0);
        }
        return result;
    }

    /** Returns the queue size. */
    public int size() {
        return size;
    }

    /** Returns true if the queue is empty. */
    public boolean isEmpty() {
        return size == 0;
    }

    /** Clears all elements. */
    public void clear() {
        size = 0;
    }

    /**
     * Returns all results as a sorted array (best first).
     *
     * @param ids         optional ID lookup array (index → id), may be null
     * @param higherIsBetter true if higher scores are better
     * @return sorted array of scored results
     */
    public ScoredResult[] toSortedResults(String[] ids, boolean higherIsBetter) {
        ScoredResult[] results = new ScoredResult[size];
        for (int i = 0; i < size; i++) {
            String id = ids != null ? ids[indices[i]] : String.valueOf(indices[i]);
            results[i] = new ScoredResult(id, indices[i], scores[i]);
        }
        if (higherIsBetter) {
            Arrays.sort(results); // descending by score
        } else {
            Arrays.sort(results, ScoredResult::compareAscending);
        }
        return results;
    }

    /**
     * Returns all indices in heap order (not sorted).
     */
    public int[] indicesUnsorted() {
        return Arrays.copyOf(indices, size);
    }

    // ─────────────── Heap internals ───────────────

    private boolean isBetterThanTop(float score) {
        // For max-heap tracking top-K nearest: new score must be LESS than worst (top)
        // For min-heap tracking top-K farthest: new score must be GREATER than top
        if (minHeap) {
            return score > scores[0]; // min-heap: smaller is "better" → replace if larger
        } else {
            return score < scores[0]; // max-heap: larger is "better" → replace if smaller
        }
    }

    private void insertAndSiftUp(int index, float score) {
        if (size == indices.length) {
            grow();
        }
        indices[size] = index;
        scores[size] = score;
        siftUp(size);
        size++;
    }

    private void siftUp(int k) {
        while (k > 0) {
            int parent = (k - 1) >>> 1;
            if (shouldSwap(k, parent)) {
                swap(k, parent);
                k = parent;
            } else {
                break;
            }
        }
    }

    private void siftDown(int k) {
        int half = size >>> 1;
        while (k < half) {
            int child = (k << 1) + 1;
            int right = child + 1;
            if (right < size && shouldSwap(right, child)) {
                child = right;
            }
            if (shouldSwap(child, k)) {
                swap(k, child);
                k = child;
            } else {
                break;
            }
        }
    }

    /** Returns true if element at position a should be above element at position b. */
    private boolean shouldSwap(int a, int b) {
        if (minHeap) {
            return scores[a] < scores[b]; // min-heap: smaller floats up
        } else {
            return scores[a] > scores[b]; // max-heap: larger floats up
        }
    }

    private void swap(int i, int j) {
        int ti = indices[i]; indices[i] = indices[j]; indices[j] = ti;
        float ts = scores[i]; scores[i] = scores[j]; scores[j] = ts;
    }

    private void grow() {
        int newCap = Math.max(indices.length * 2, 16);
        indices = Arrays.copyOf(indices, newCap);
        scores = Arrays.copyOf(scores, newCap);
    }
}
