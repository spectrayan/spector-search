package com.spectrayan.spector.index;

import java.util.Arrays;
import java.util.BitSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.core.similarity.SimilarityFunction;

/**
 * Abstract base class for HNSW (Hierarchical Navigable Small World) indexes.
 *
 * <p>Encapsulates the complete HNSW graph structure and traversal algorithms,
 * delegating only the distance computation and vector storage to concrete
 * subclasses via the Template Method pattern.</p>
 *
 * <h3>Template Methods (subclass hooks)</h3>
 * <ul>
 *   <li>{@link #computeDistance(float[], int)} — distance from query to stored node</li>
 *   <li>{@link #getNodeVector(int)} — retrieves the float32 vector for a node (used in pruning)</li>
 *   <li>{@link #storeVector(int, float[])} — stores the vector data for a newly added node</li>
 * </ul>
 *
 * <h3>Design Decisions</h3>
 * <ul>
 *   <li>Uses {@link ReentrantLock} (not {@code synchronized}) to avoid virtual thread pinning.</li>
 *   <li>Neighbor arrays are plain {@code int[]} — reads are safe without synchronization
 *       since arrays are replaced atomically (volatile write).</li>
 * </ul>
 *
 * @see HnswIndex
 * @see QuantizedHnswIndex
 */
public abstract class AbstractHnswIndex implements VectorIndex {

    private static final Logger log = LoggerFactory.getLogger(AbstractHnswIndex.class);

    protected final HnswParams params;
    protected final SimilarityFunction similarityFunction;
    protected final int dimensions;

    // ── Node storage (parallel arrays for cache locality) ──
    protected final int capacity;
    protected volatile int nodeCount;
    protected final String[] ids;
    protected final int[] storeIndices;
    protected final int[][] neighbors;         // neighbors[nodeIndex] = neighbor indices at layer 0
    protected final int[][][] upperNeighbors;  // upperNeighbors[nodeIndex][layer-1] = neighbor indices
    protected final int[] nodeLevels;          // max layer for each node

    // ── Graph state ──
    protected volatile int entryPoint = -1;
    protected volatile int maxLevel = -1;

    // ── Concurrency ──
    protected final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    protected final ReentrantReadWriteLock.WriteLock writeLock = rwLock.writeLock();
    protected final ReentrantReadWriteLock.ReadLock readLock = rwLock.readLock();

    // ── Pre-allocated scratch for addConnection() pruning (accessed under writeLock only) ──
    //
    // addConnection() is always called under writeLock (from add()), so only ONE thread
    // accesses these arrays at a time. A plain instance field is correct — no ThreadLocal needed.
    //
    // Size = max(maxLevel0Connections, m) + 2 covers all pruning cases:
    //   layer 0: at most maxLevel0Connections current neighbors + 1 new = maxLevel0Connections + 1
    //   upper:   at most m current neighbors + 1 new = m + 1
    private final float[] pruneScores;
    private final int[]   pruneIndices;

    // ── Pre-allocated unvisited buffer for searchLayer() (per-thread via ThreadLocal) ──
    //
    // searchLayer() is called from:
    //   add()    — under writeLock (single writer, serialized)
    //   search() — under readLock  (concurrent readers, each needs its own buffer)
    //
    // ThreadLocal gives each virtual thread its own buffer, eliminating the per-call
    // allocation AND the Arrays.copyOf fallback that occurred when the buffer was too small.
    // Buffer size = max(maxLevel0Connections, m) * 2 covers the realistic worst case.
    private final ThreadLocal<int[]> unvisitedBufLocal;

    /**
     * Creates the HNSW graph structure.
     *
     * @param dimensions         vector dimensionality
     * @param capacity           max number of vectors
     * @param similarityFunction distance/similarity metric
     * @param params             HNSW tuning parameters
     */
    protected AbstractHnswIndex(int dimensions, int capacity,
                                 SimilarityFunction similarityFunction, HnswParams params) {
        this.dimensions = dimensions;
        this.capacity = capacity;
        this.similarityFunction = similarityFunction;
        this.params = params;
        this.nodeCount = 0;

        this.ids = new String[capacity];
        this.storeIndices = new int[capacity];
        this.neighbors = new int[capacity][];
        this.upperNeighbors = new int[capacity][][];
        this.nodeLevels = new int[capacity];

        // Pre-allocated pruning scratch for addConnection() — sized for the larger of the two maxConn values
        int maxPruneSize = Math.max(params.maxLevel0Connections(), params.m()) + 2;
        this.pruneScores  = new float[maxPruneSize];
        this.pruneIndices = new int[maxPruneSize];

        // Per-thread unvisited buffer for searchLayer() — prevents per-search allocation
        final int unvisitedInitSize = Math.max(params.maxLevel0Connections(), params.m()) * 2;
        this.unvisitedBufLocal = ThreadLocal.withInitial(() -> new int[unvisitedInitSize]);
    }

    // ─────────────── Template methods (subclass hooks) ───────────────

    /**
     * Computes the distance/similarity between a query vector and a stored node.
     *
     * @param query   the query vector
     * @param nodeIdx the internal node index
     * @return distance or similarity score
     */
    protected abstract float computeDistance(float[] query, int nodeIdx);

    /**
     * Returns the float32 vector for the given node.
     *
     * <p>Used during graph construction for neighbor pruning, where exact
     * distances between stored nodes are required.</p>
     *
     * @param nodeIdx the internal node index
     * @return the stored float32 vector
     */
    protected abstract float[] getNodeVector(int nodeIdx);

    /**
     * Stores the vector data for a newly inserted node.
     *
     * <p>Subclasses may store float32, quantize to int8, or both.</p>
     *
     * @param nodeIdx the internal node index
     * @param vector  the original float32 vector
     */
    protected abstract void storeVector(int nodeIdx, float[] vector);

    // ─────────────── VectorIndex implementation ───────────────

    @Override
    public void add(String id, int storeIndex, float[] vector) {
        if (vector.length != dimensions) {
            throw new IllegalArgumentException("Expected " + dimensions + " dims, got " + vector.length);
        }

        writeLock.lock();
        try {
            if (nodeCount >= capacity) {
                throw new IllegalStateException("Index is full: capacity=" + capacity);
            }

            int nodeIdx = nodeCount;
            int level = randomLevel();

            // Store node metadata
            ids[nodeIdx] = id;
            storeIndices[nodeIdx] = storeIndex;
            nodeLevels[nodeIdx] = level;
            neighbors[nodeIdx] = new int[0];
            if (level > 0) {
                upperNeighbors[nodeIdx] = new int[level][];
                for (int l = 0; l < level; l++) {
                    upperNeighbors[nodeIdx][l] = new int[0];
                }
            }

            // Delegate vector storage to subclass
            storeVector(nodeIdx, vector);

            nodeCount++;

            if (entryPoint == -1) {
                // First node
                entryPoint = nodeIdx;
                maxLevel = level;
                return;
            }

            // ── Insert into graph ──
            int currentNode = entryPoint;
            int currentMaxLevel = maxLevel;

            // Phase 1: Greedy descent through upper layers
            for (int lc = currentMaxLevel; lc > level; lc--) {
                currentNode = greedyClosest(vector, currentNode, lc);
            }

            // Phase 2: Insert at each layer from min(level, currentMaxLevel) down to 0
            for (int lc = Math.min(level, currentMaxLevel); lc >= 0; lc--) {
                int ef = params.efConstruction();
                NeighborQueue candidates = searchLayer(vector, currentNode, ef, lc);

                int maxConn = (lc == 0) ? params.maxLevel0Connections() : params.m();
                int[] selectedNeighbors = selectNeighbors(candidates, maxConn);

                setNeighbors(nodeIdx, lc, selectedNeighbors);

                for (int neighbor : selectedNeighbors) {
                    addConnection(neighbor, nodeIdx, lc, maxConn);
                }

                if (!candidates.isEmpty()) {
                    currentNode = candidates.topIndex();
                }
            }

            // Update entry point if new node has higher level
            if (level > maxLevel) {
                entryPoint = nodeIdx;
                maxLevel = level;
            }

        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public ScoredResult[] search(float[] query, int k) {
        if (query.length != dimensions) {
            throw new IllegalArgumentException("Expected " + dimensions + " dims, got " + query.length);
        }
        if (nodeCount == 0) {
            return new ScoredResult[0];
        }

        readLock.lock();
        try {
            int ef = Math.max(k, params.efSearch());
            int currentNode = entryPoint;

            // Phase 1: Greedy descent through upper layers
            for (int lc = maxLevel; lc > 0; lc--) {
                currentNode = greedyClosest(query, currentNode, lc);
            }

            // Phase 2: Search at layer 0 with ef candidates
            NeighborQueue candidates = searchLayer(query, currentNode, ef, 0);

            // Extract top-K results
            boolean higherIsBetter = similarityFunction.higherIsBetter();
            ScoredResult[] results = candidates.toSortedResults(ids, higherIsBetter);

            // Trim to k
            if (results.length > k) {
                results = Arrays.copyOf(results, k);
            }
            return results;
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public int size() {
        return nodeCount;
    }

    @Override
    public SimilarityFunction similarityFunction() {
        return similarityFunction;
    }

    @Override
    public void close() {
        // No external resources to close by default
    }

    // ─────────────── Graph operations ───────────────

    /**
     * Greedy search: find the single closest node to the query at the given layer.
     */
    protected int greedyClosest(float[] query, int startNode, int layer) {
        int current = startNode;
        float currentDist = computeDistance(query, current);
        boolean improved = true;

        while (improved) {
            improved = false;
            int[] nbrs = getNeighbors(current, layer);
            for (int neighbor : nbrs) {
                float dist = computeDistance(query, neighbor);
                if (isBetter(dist, currentDist)) {
                    current = neighbor;
                    currentDist = dist;
                    improved = true;
                }
            }
        }
        return current;
    }

    /**
     * Beam search at a specific layer — returns candidates as a max-heap
     * (worst score on top for bounded eviction).
     *
     * <p>Optimized: batches unvisited neighbor collection before computing
     * distances, improving cache prefetch behavior for the vector store.</p>
     */
    protected NeighborQueue searchLayer(float[] query, int entryNode, int ef, int layer) {
        int currentNodeCount = nodeCount;
        BitSet visited = new BitSet(currentNodeCount);
        NeighborQueue candidates = new NeighborQueue(ef + 1, ef, maxHeap());
        NeighborQueue workQueue = new NeighborQueue(ef + 1, minHeap());

        float entryDist = computeDistance(query, entryNode);
        candidates.add(entryNode, entryDist);
        workQueue.add(entryNode, entryDist);
        visited.set(entryNode);

        // Use the pre-allocated per-thread buffer — no allocation per call.
        // If a node somehow has more neighbors than the buffer size (extremely unlikely
        // with well-configured params), we fall back to a local array just that once.
        int[] unvisitedBuf = unvisitedBufLocal.get();

        while (!workQueue.isEmpty()) {
            float currentDist = workQueue.topScore();
            int current = workQueue.poll();

            if (candidates.size() >= ef && !isBetter(currentDist, candidates.topScore())) {
                break;
            }

            int[] nbrs = getNeighbors(current, layer);

            // Batch: collect unvisited neighbors first
            int unvisitedCount = 0;
            for (int neighbor : nbrs) {
                if (!visited.get(neighbor)) {
                    visited.set(neighbor);
                    if (unvisitedCount >= unvisitedBuf.length) {
                        // Rare: node has more neighbors than our buffer. Grow the ThreadLocal buffer.
                        int[] grown = new int[unvisitedBuf.length * 2];
                        System.arraycopy(unvisitedBuf, 0, grown, 0, unvisitedCount);
                        unvisitedBufLocal.set(grown);
                        unvisitedBuf = grown;
                    }
                    unvisitedBuf[unvisitedCount++] = neighbor;
                }
            }

            // Compute distances for all unvisited neighbors in a tight loop
            for (int i = 0; i < unvisitedCount; i++) {
                int neighbor = unvisitedBuf[i];
                float dist = computeDistance(query, neighbor);
                if (candidates.size() < ef || isBetter(dist, candidates.topScore())) {
                    candidates.add(neighbor, dist);
                    workQueue.add(neighbor, dist);
                }
            }
        }

        return candidates;
    }

    /**
     * Selects up to maxConn best neighbors from the candidate queue.
     */
    protected int[] selectNeighbors(NeighborQueue candidates, int maxConn) {
        ScoredResult[] sorted = candidates.toSortedResults(null, similarityFunction.higherIsBetter());
        int count = Math.min(sorted.length, maxConn);
        int[] result = new int[count];
        for (int i = 0; i < count; i++) {
            result[i] = sorted[i].index();
        }
        return result;
    }

    /**
     * Adds a bidirectional connection, pruning if over capacity.
     */
    protected void addConnection(int fromNode, int toNode, int layer, int maxConn) {
        int[] currentNeighbors = getNeighbors(fromNode, layer);

        for (int n : currentNeighbors) {
            if (n == toNode) return;
        }

        if (currentNeighbors.length < maxConn) {
            // Neighbor list not yet full — extend it by one.
            // This allocation is structurally unavoidable: the graph stores int[] per node.
            int[] newNeighbors = new int[currentNeighbors.length + 1];
            System.arraycopy(currentNeighbors, 0, newNeighbors, 0, currentNeighbors.length);
            newNeighbors[currentNeighbors.length] = toNode;
            setNeighbors(fromNode, layer, newNeighbors);
        } else {
            // Neighbor list full: must prune. Find the best maxConn from (currentNeighbors + toNode).
            //
            // Uses pre-allocated pruneScores/pruneIndices instance fields (safe under writeLock).
            // In-place insertion sort over maxConn+1 elements (typically 17 or 33) — zero allocation,
            // O(maxConn²) = O(289) for M=16 which is negligible vs distance computation.
            float[] fromVec = getNodeVector(fromNode);
            boolean higherIsBetter = similarityFunction.higherIsBetter();

            // Fill pre-allocated scratch: score each current neighbor and the new candidate
            int pruneSize = 0;
            for (int n : currentNeighbors) {
                pruneScores[pruneSize]  = similarityFunction.compute(fromVec, getNodeVector(n));
                pruneIndices[pruneSize] = n;
                pruneSize++;
            }
            pruneScores[pruneSize]  = similarityFunction.compute(fromVec, getNodeVector(toNode));
            pruneIndices[pruneSize] = toNode;
            pruneSize++;

            // In-place insertion sort: best-first order (descending for similarity, ascending for distance)
            for (int i = 1; i < pruneSize; i++) {
                float sc  = pruneScores[i];
                int   idx = pruneIndices[i];
                int j = i - 1;
                while (j >= 0 && (higherIsBetter ? pruneScores[j] < sc : pruneScores[j] > sc)) {
                    pruneScores[j + 1]  = pruneScores[j];
                    pruneIndices[j + 1] = pruneIndices[j];
                    j--;
                }
                pruneScores[j + 1]  = sc;
                pruneIndices[j + 1] = idx;
            }

            // Keep the best maxConn (the sorted head of pruneIndices)
            int[] pruned = new int[maxConn];  // unavoidable: graph structure requires a stored int[]
            System.arraycopy(pruneIndices, 0, pruned, 0, maxConn);
            setNeighbors(fromNode, layer, pruned);
        }
    }

    // ─────────────── Helpers ───────────────

    protected int[] getNeighbors(int nodeIdx, int layer) {
        if (layer == 0) {
            int[] n = neighbors[nodeIdx];
            return n != null ? n : new int[0];
        } else {
            int[][] upper = upperNeighbors[nodeIdx];
            if (upper == null || layer - 1 >= upper.length) return new int[0];
            int[] n = upper[layer - 1];
            return n != null ? n : new int[0];
        }
    }

    protected void setNeighbors(int nodeIdx, int layer, int[] nbrs) {
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

    /** Returns true if scoreA is "better" than scoreB. */
    protected boolean isBetter(float scoreA, float scoreB) {
        return similarityFunction.higherIsBetter()
                ? scoreA > scoreB
                : scoreA < scoreB;
    }

    /** Min-heap: best (smallest distance / highest similarity) on top. */
    protected boolean minHeap() {
        return !similarityFunction.higherIsBetter();
    }

    /** Max-heap: worst on top (for bounded eviction). */
    protected boolean maxHeap() {
        return similarityFunction.higherIsBetter();
    }

    protected int randomLevel() {
        double r = ThreadLocalRandom.current().nextDouble();
        int level = (int) (-Math.log(r) * params.levelMultiplier());
        return Math.max(0, level);
    }

    // ─────────────── Serialization accessors ───────────────

    /** Returns the HNSW parameters. */
    public HnswParams params() { return params; }

    /** Returns the dimensionality. */
    public int dimensions() { return dimensions; }

    /** Returns the entry point node index. */
    public int entryPoint() { return entryPoint; }

    /** Returns the max level in the graph. */
    public int maxLevel() { return maxLevel; }

    /** Returns the ID for the given node. */
    public String getId(int nodeIdx) { return ids[nodeIdx]; }

    /** Returns the level for the given node. */
    public int getLevel(int nodeIdx) { return nodeLevels[nodeIdx]; }

    /** Returns the neighbor indices at the specified layer. */
    public int[] getNeighborsAtLayer(int nodeIdx, int layer) {
        return getNeighbors(nodeIdx, layer);
    }
}
