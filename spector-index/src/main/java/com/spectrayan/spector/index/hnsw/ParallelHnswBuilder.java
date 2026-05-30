package com.spectrayan.spector.index;

import com.spectrayan.spector.commons.error.SpectorHnswBuildException;


import com.spectrayan.spector.config.HnswParams;
import java.util.Arrays;
import java.util.BitSet;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.core.similarity.SimilarityFunction;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Multi-threaded HNSW index builder using virtual threads.
 *
 * <p>For datasets exceeding {@link #PARALLEL_THRESHOLD} vectors, construction
 * is parallelized using {@link StructuredTaskScope} with virtual threads.
 * Level assignments are pre-computed sequentially to ensure determinism,
 * while layer-0 insertions are parallelized with fine-grained per-node
 * neighbor list locking.</p>
 *
 * <p>For smaller datasets, falls back to single-threaded sequential insertion.</p>
 *
 * <h3>Error Handling</h3>
 * If any virtual thread encounters an unrecoverable error during parallel
 * construction, the entire build is aborted, the partial graph is discarded,
 * and a {@link SpectorHnswBuildException} is thrown.
 *
 * @see HnswIndex
 * @see AbstractHnswIndex
 */
public class ParallelHnswBuilder {

    private static final Logger log = LoggerFactory.getLogger(ParallelHnswBuilder.class);

    /** Threshold for parallel construction. Below this, sequential build is used. */
    static final int PARALLEL_THRESHOLD = 10_000;

    /**
     * Builds an HNSW index from the given vectors.
     *
     * <p>If the number of vectors is below {@link #PARALLEL_THRESHOLD},
     * construction proceeds sequentially. Otherwise, virtual threads
     * parallelize layer-0 insertions.</p>
     *
     * @param vectors            the vectors to index (each must have the same dimensionality)
     * @param params             HNSW tuning parameters
     * @param similarityFunction the similarity/distance function
     * @return the constructed HNSW index
     * @throws SpectorHnswBuildException if parallel construction fails
     * @throws SpectorValidationException if vectors is null or empty, or dimensions are inconsistent
     */
    public HnswIndex build(float[][] vectors, HnswParams params, SimilarityFunction similarityFunction) {
        if (vectors == null || vectors.length == 0) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "Vectors array");
        }

        int dimensions = vectors[0].length;
        for (int i = 1; i < vectors.length; i++) {
            if (vectors[i].length != dimensions) {
                throw new SpectorValidationException(ErrorCode.DIMENSIONS_MISMATCH, dimensions, vectors[i].length);
            }
        }

        if (vectors.length < PARALLEL_THRESHOLD) {
            return buildSequential(vectors, params, similarityFunction);
        }
        return buildParallel(vectors, params, similarityFunction);
    }

    /**
     * Sequential build — simple insertion one vector at a time.
     */
    private HnswIndex buildSequential(float[][] vectors, HnswParams params, SimilarityFunction similarityFunction) {
        int dimensions = vectors[0].length;
        HnswIndex index = new HnswIndex(dimensions, vectors.length, similarityFunction, params);

        for (int i = 0; i < vectors.length; i++) {
            index.add(String.valueOf(i), i, vectors[i]);
        }

        log.info("Sequential HNSW build complete: {} vectors, dims={}", vectors.length, dimensions);
        return index;
    }

    /**
     * Parallel build using StructuredTaskScope with virtual threads.
     *
     * <p>Strategy:
     * <ol>
     *   <li>Pre-compute level assignments sequentially (deterministic)</li>
     *   <li>Insert upper-layer nodes sequentially (they are few)</li>
     *   <li>Parallelize layer-0-only insertions with fine-grained locking</li>
     * </ol>
     * </p>
     */
    private HnswIndex buildParallel(float[][] vectors, HnswParams params, SimilarityFunction similarityFunction) {
        int n = vectors.length;
        int dimensions = vectors[0].length;

        log.info("Starting parallel HNSW build: {} vectors, dims={}, M={}, efC={}",
                n, dimensions, params.m(), params.efConstruction());

        // Step 1: Pre-compute level assignments sequentially
        int[] levels = preComputeLevels(n, params);

        // Step 2: Create the parallel-aware index structure
        ParallelHnswGraph graph = new ParallelHnswGraph(dimensions, n, similarityFunction, params, vectors, levels);

        // Step 3: Insert the first node (entry point)
        graph.insertFirst();

        // Step 4: Insert upper-layer nodes sequentially (nodes with level > 0)
        // These are rare (~1/M fraction) and need sequential processing to maintain
        // entry point correctness
        for (int i = 1; i < n; i++) {
            if (levels[i] > 0) {
                graph.insertSequential(i);
            }
        }

        // Step 5: Parallelize layer-0 node insertions using StructuredTaskScope
        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.<Void>awaitAllSuccessfulOrThrow())) {
            for (int i = 1; i < n; i++) {
                if (levels[i] == 0) {
                    final int nodeIdx = i;
                    scope.fork(() -> {
                        graph.insertParallel(nodeIdx);
                        return null;
                    });
                }
            }

            scope.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SpectorHnswBuildException("Parallel HNSW build interrupted", e);
        } catch (Exception e) {
            throw new SpectorHnswBuildException(
                    "Parallel HNSW build failed: " + e.getMessage(), e);
        }

        // Step 6: Convert graph to HnswIndex
        HnswIndex result = graph.toHnswIndex();

        log.info("Parallel HNSW build complete: {} vectors, dims={}, maxLevel={}",
                n, dimensions, result.maxLevel());
        return result;
    }

    /**
     * Pre-computes level assignments for all nodes.
     * Uses the same probability distribution as the standard HNSW algorithm.
     */
    private int[] preComputeLevels(int n, HnswParams params) {
        int[] levels = new int[n];
        double levelMultiplier = params.levelMultiplier();

        for (int i = 0; i < n; i++) {
            double r = ThreadLocalRandom.current().nextDouble();
            levels[i] = Math.max(0, (int) (-Math.log(r) * levelMultiplier));
        }
        return levels;
    }

    // ─────────────── Inner graph for parallel construction ───────────────

    /**
     * Internal graph structure that supports fine-grained per-node locking
     * for parallel insertion.
     */
    private static final class ParallelHnswGraph {

        /** Shared empty neighbor array — Flyweight to avoid per-call allocations. */
        private static final int[] EMPTY_NEIGHBORS = new int[0];

        private final int dimensions;
        private final int capacity;
        private final SimilarityFunction similarityFunction;
        private final HnswParams params;
        private final float[][] vectors;
        private final int[] levels;

        // Graph structure (same as AbstractHnswIndex)
        private final int[][] neighbors;         // layer 0 neighbors
        private final int[][][] upperNeighbors;  // upper layer neighbors
        private volatile int entryPoint = -1;
        private volatile int maxLevel = -1;

        // Fine-grained per-node locks for neighbor list updates
        private final ReentrantLock[] nodeLocks;

        // Global lock for entry point updates (rare operation)
        private final ReentrantLock entryPointLock = new ReentrantLock();

        ParallelHnswGraph(int dimensions, int capacity, SimilarityFunction similarityFunction,
                          HnswParams params, float[][] vectors, int[] levels) {
            this.dimensions = dimensions;
            this.capacity = capacity;
            this.similarityFunction = similarityFunction;
            this.params = params;
            this.vectors = vectors;
            this.levels = levels;

            this.neighbors = new int[capacity][];
            this.upperNeighbors = new int[capacity][][];
            this.nodeLocks = new ReentrantLock[capacity];

            // Initialize node structures and locks
            for (int i = 0; i < capacity; i++) {
                nodeLocks[i] = new ReentrantLock();
                neighbors[i] = EMPTY_NEIGHBORS;
                if (levels[i] > 0) {
                    upperNeighbors[i] = new int[levels[i]][];
                    for (int l = 0; l < levels[i]; l++) {
                        upperNeighbors[i][l] = EMPTY_NEIGHBORS;
                    }
                }
            }
        }

        /** Insert the first node as entry point. */
        void insertFirst() {
            entryPoint = 0;
            maxLevel = levels[0];
        }

        /**
         * Sequential insertion for upper-layer nodes.
         * Must be called while no parallel insertions are active.
         */
        void insertSequential(int nodeIdx) {
            int level = levels[nodeIdx];
            float[] vector = vectors[nodeIdx];

            int currentNode = entryPoint;
            int currentMaxLevel = maxLevel;

            // Phase 1: Greedy descent through upper layers above node's level
            for (int lc = currentMaxLevel; lc > level; lc--) {
                currentNode = greedyClosest(vector, currentNode, lc);
            }

            // Phase 2: Insert at each layer from min(level, currentMaxLevel) down to 0
            for (int lc = Math.min(level, currentMaxLevel); lc >= 0; lc--) {
                int ef = params.efConstruction();
                NeighborQueue candidates = searchLayer(vector, currentNode, ef, lc);

                int maxConn = (lc == 0) ? params.maxLevel0Connections() : params.m();
                int[] selectedNeighbors = selectNeighbors(candidates, maxConn);

                setNeighborsLocked(nodeIdx, lc, selectedNeighbors);

                for (int neighbor : selectedNeighbors) {
                    addConnectionLocked(neighbor, nodeIdx, lc, maxConn);
                }

                if (!candidates.isEmpty()) {
                    currentNode = candidates.topIndex();
                }
            }

            // Update entry point if new node has higher level
            if (level > maxLevel) {
                entryPointLock.lock();
                try {
                    if (level > maxLevel) {
                        entryPoint = nodeIdx;
                        maxLevel = level;
                    }
                } finally {
                    entryPointLock.unlock();
                }
            }
        }

        /**
         * Parallel insertion for layer-0-only nodes.
         * Uses fine-grained per-node locking for neighbor list updates.
         */
        void insertParallel(int nodeIdx) {
            float[] vector = vectors[nodeIdx];

            int currentNode = entryPoint;
            int currentMaxLevel = maxLevel;

            // Phase 1: Greedy descent through upper layers to layer 0
            for (int lc = currentMaxLevel; lc > 0; lc--) {
                currentNode = greedyClosest(vector, currentNode, lc);
            }

            // Phase 2: Insert at layer 0 only
            int ef = params.efConstruction();
            NeighborQueue candidates = searchLayer(vector, currentNode, ef, 0);

            int maxConn = params.maxLevel0Connections();
            int[] selectedNeighbors = selectNeighbors(candidates, maxConn);

            setNeighborsLocked(nodeIdx, 0, selectedNeighbors);

            for (int neighbor : selectedNeighbors) {
                addConnectionLocked(neighbor, nodeIdx, 0, maxConn);
            }
        }

        /**
         * Set neighbors with per-node locking.
         */
        private void setNeighborsLocked(int nodeIdx, int layer, int[] nbrs) {
            nodeLocks[nodeIdx].lock();
            try {
                if (layer == 0) {
                    neighbors[nodeIdx] = nbrs;
                } else {
                    if (upperNeighbors[nodeIdx] == null) {
                        upperNeighbors[nodeIdx] = new int[layer][];
                    }
                    if (layer - 1 >= upperNeighbors[nodeIdx].length) {
                        upperNeighbors[nodeIdx] = Arrays.copyOf(upperNeighbors[nodeIdx], layer);
                    }
                    upperNeighbors[nodeIdx][layer - 1] = nbrs;
                }
            } finally {
                nodeLocks[nodeIdx].unlock();
            }
        }

        /**
         * Add a connection with fine-grained per-node locking.
         * Locks only the target node's neighbor list.
         */
        private void addConnectionLocked(int fromNode, int toNode, int layer, int maxConn) {
            nodeLocks[fromNode].lock();
            try {
                int[] currentNeighbors = getNeighbors(fromNode, layer);

                // Check for duplicate
                for (int n : currentNeighbors) {
                    if (n == toNode) return;
                }

                if (currentNeighbors.length < maxConn) {
                    int[] newNeighbors = new int[currentNeighbors.length + 1];
                    System.arraycopy(currentNeighbors, 0, newNeighbors, 0, currentNeighbors.length);
                    newNeighbors[currentNeighbors.length] = toNode;
                    setNeighborsInternal(fromNode, layer, newNeighbors);
                } else {
                    // Prune: keep the maxConn best neighbors
                    float[] fromVec = vectors[fromNode];
                    NeighborQueue queue = new NeighborQueue(maxConn + 1, false);
                    for (int n : currentNeighbors) {
                        queue.add(n, similarityFunction.computeForRanking(fromVec, vectors[n]));
                    }
                    queue.add(toNode, similarityFunction.computeForRanking(fromVec, vectors[toNode]));

                    ScoredResult[] best = queue.toSortedResults(null, similarityFunction.higherIsBetter());
                    int keepCount = Math.min(best.length, maxConn);
                    int[] pruned = new int[keepCount];
                    for (int i = 0; i < keepCount; i++) {
                        pruned[i] = best[i].index();
                    }
                    setNeighborsInternal(fromNode, layer, pruned);
                }
            } finally {
                nodeLocks[fromNode].unlock();
            }
        }

        /** Internal set without locking (caller holds lock). */
        private void setNeighborsInternal(int nodeIdx, int layer, int[] nbrs) {
            if (layer == 0) {
                neighbors[nodeIdx] = nbrs;
            } else {
                if (upperNeighbors[nodeIdx] == null) {
                    upperNeighbors[nodeIdx] = new int[layer][];
                }
                if (layer - 1 >= upperNeighbors[nodeIdx].length) {
                    upperNeighbors[nodeIdx] = Arrays.copyOf(upperNeighbors[nodeIdx], layer);
                }
                upperNeighbors[nodeIdx][layer - 1] = nbrs;
            }
        }

        /** Get neighbors (read without lock — arrays are replaced atomically). */
        private int[] getNeighbors(int nodeIdx, int layer) {
            if (layer == 0) {
                int[] n = neighbors[nodeIdx];
                return n != null ? n : EMPTY_NEIGHBORS;
            } else {
                int[][] upper = upperNeighbors[nodeIdx];
                if (upper == null || layer - 1 >= upper.length) return EMPTY_NEIGHBORS;
                int[] n = upper[layer - 1];
                return n != null ? n : EMPTY_NEIGHBORS;
            }
        }

        /** Greedy closest node at a given layer. */
        private int greedyClosest(float[] query, int startNode, int layer) {
            int current = startNode;
            float currentDist = similarityFunction.computeForRanking(query, vectors[current]);
            boolean improved = true;

            while (improved) {
                improved = false;
                int[] nbrs = getNeighbors(current, layer);
                for (int neighbor : nbrs) {
                    float dist = similarityFunction.computeForRanking(query, vectors[neighbor]);
                    if (isBetter(dist, currentDist)) {
                        current = neighbor;
                        currentDist = dist;
                        improved = true;
                    }
                }
            }
            return current;
        }

        /** Beam search at a specific layer. */
        private NeighborQueue searchLayer(float[] query, int entryNode, int ef, int layer) {
            BitSet visited = new BitSet(capacity);
            NeighborQueue candidates = new NeighborQueue(ef + 1, ef, maxHeap());
            NeighborQueue workQueue = new NeighborQueue(ef + 1, minHeap());

            float entryDist = similarityFunction.computeForRanking(query, vectors[entryNode]);
            candidates.add(entryNode, entryDist);
            workQueue.add(entryNode, entryDist);
            visited.set(entryNode);

            while (!workQueue.isEmpty()) {
                float currentDist = workQueue.topScore();
                int current = workQueue.poll();

                if (candidates.size() >= ef && !isBetter(currentDist, candidates.topScore())) {
                    break;
                }

                int[] nbrs = getNeighbors(current, layer);
                for (int neighbor : nbrs) {
                    if (!visited.get(neighbor)) {
                        visited.set(neighbor);
                        float dist = similarityFunction.computeForRanking(query, vectors[neighbor]);
                        if (candidates.size() < ef || isBetter(dist, candidates.topScore())) {
                            candidates.add(neighbor, dist);
                            workQueue.add(neighbor, dist);
                        }
                    }
                }
            }

            return candidates;
        }

        /** Select up to maxConn best neighbors from candidates. */
        private int[] selectNeighbors(NeighborQueue candidates, int maxConn) {
            ScoredResult[] sorted = candidates.toSortedResults(null, similarityFunction.higherIsBetter());
            int count = Math.min(sorted.length, maxConn);
            int[] result = new int[count];
            for (int i = 0; i < count; i++) {
                result[i] = sorted[i].index();
            }
            return result;
        }

        private boolean isBetter(float scoreA, float scoreB) {
            return similarityFunction.higherIsBetter()
                    ? scoreA > scoreB
                    : scoreA < scoreB;
        }

        private boolean minHeap() {
            return !similarityFunction.higherIsBetter();
        }

        private boolean maxHeap() {
            return similarityFunction.higherIsBetter();
        }

        /**
         * Converts this parallel graph structure into a standard HnswIndex.
         */
        HnswIndex toHnswIndex() {
            HnswIndex index = new HnswIndex(dimensions, capacity, similarityFunction, params);

            // Copy all node data into the index
            for (int i = 0; i < capacity; i++) {
                // Access protected fields via reflection-free approach:
                // We use the add method internals by directly setting fields
                index.ids[i] = String.valueOf(i);
                index.storeIndices[i] = i;
                index.nodeLevels[i] = levels[i];
                index.storeVector(i, vectors[i]);
                index.neighbors[i] = neighbors[i];
                index.upperNeighbors[i] = upperNeighbors[i];
            }

            index.nodeCount = capacity;
            index.entryPoint = entryPoint;
            index.maxLevel = maxLevel;

            return index;
        }
    }
}
