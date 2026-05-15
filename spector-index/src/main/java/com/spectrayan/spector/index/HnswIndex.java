package com.spectrayan.spector.index;

import com.spectrayan.spector.core.SimilarityFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.BitSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

/**
 * HNSW (Hierarchical Navigable Small World) vector index.
 *
 * <p>Implements approximate nearest-neighbor search using a multi-layer
 * navigable small world graph. Distance computations delegate to the
 * SIMD-accelerated kernels in {@code spector-core}.</p>
 *
 * <h3>Key Design Decisions</h3>
 * <ul>
 *   <li>Uses {@link ReentrantLock} (not {@code synchronized}) to avoid
 *       virtual thread pinning.</li>
 *   <li>Neighbor arrays are plain {@code int[]} — reads are safe without
 *       synchronization since arrays are replaced atomically (volatile write).</li>
 *   <li>Vectors are stored inline for construction speed; the index holds
 *       a copy of each vector for fast distance computation during search.</li>
 * </ul>
 */
public class HnswIndex implements VectorIndex {

    private static final Logger log = LoggerFactory.getLogger(HnswIndex.class);

    private final HnswParams params;
    private final SimilarityFunction similarityFunction;
    private final int dimensions;

    // ── Node storage (parallel arrays for cache locality) ──
    private final int capacity;
    private volatile int nodeCount;
    private final String[] ids;
    private final int[] storeIndices;
    private final float[][] vectors;        // inline copy for fast distance computation
    private final int[][] neighbors;        // neighbors[nodeIndex] = neighbor indices at layer 0
    private final int[][][] upperNeighbors; // upperNeighbors[nodeIndex][layer-1] = neighbor indices
    private final int[] nodeLevels;         // max layer for each node

    // ── Graph state ──
    private volatile int entryPoint = -1;
    private volatile int maxLevel = -1;

    // ── Concurrency ──
    private final ReentrantLock writeLock = new ReentrantLock();

    /**
     * Creates a new HNSW index.
     *
     * @param dimensions         vector dimensionality
     * @param capacity           max number of vectors
     * @param similarityFunction distance/similarity metric
     * @param params             HNSW tuning parameters
     */
    public HnswIndex(int dimensions, int capacity, SimilarityFunction similarityFunction, HnswParams params) {
        this.dimensions = dimensions;
        this.capacity = capacity;
        this.similarityFunction = similarityFunction;
        this.params = params;
        this.nodeCount = 0;

        this.ids = new String[capacity];
        this.storeIndices = new int[capacity];
        this.vectors = new float[capacity][];
        this.neighbors = new int[capacity][];
        this.upperNeighbors = new int[capacity][][];
        this.nodeLevels = new int[capacity];

        log.info("HnswIndex created: dims={}, capacity={}, M={}, efC={}, efS={}, similarity={}",
                dimensions, capacity, params.m(), params.efConstruction(), params.efSearch(),
                similarityFunction);
    }

    /** Creates with default params. */
    public HnswIndex(int dimensions, int capacity, SimilarityFunction similarityFunction) {
        this(dimensions, capacity, similarityFunction, HnswParams.DEFAULT);
    }

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

            // Store node data
            ids[nodeIdx] = id;
            storeIndices[nodeIdx] = storeIndex;
            vectors[nodeIdx] = Arrays.copyOf(vector, vector.length);
            nodeLevels[nodeIdx] = level;
            neighbors[nodeIdx] = new int[0];
            if (level > 0) {
                upperNeighbors[nodeIdx] = new int[level][];
                for (int l = 0; l < level; l++) {
                    upperNeighbors[nodeIdx][l] = new int[0];
                }
            }

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

            // Phase 1: Greedy descent through upper layers to find entry for lower layers
            for (int lc = currentMaxLevel; lc > level; lc--) {
                currentNode = greedyClosest(vector, currentNode, lc);
            }

            // Phase 2: Insert at each layer from min(level, currentMaxLevel) down to 0
            for (int lc = Math.min(level, currentMaxLevel); lc >= 0; lc--) {
                int ef = (lc == 0) ? params.efConstruction() : params.efConstruction();
                NeighborQueue candidates = searchLayer(vector, currentNode, ef, lc);

                // Select best neighbors (simple nearest selection)
                int maxConn = (lc == 0) ? params.maxLevel0Connections() : params.m();
                int[] selectedNeighbors = selectNeighbors(candidates, maxConn);

                // Set neighbors for new node at this layer
                setNeighbors(nodeIdx, lc, selectedNeighbors);

                // Add bidirectional connections
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
        // No external resources to close — vectors are on-heap copies
    }

    // ─────────────── Graph operations ───────────────

    /**
     * Greedy search: find the single closest node to the query at the given layer.
     */
    private int greedyClosest(float[] query, int startNode, int layer) {
        int current = startNode;
        float currentDist = distance(query, current);
        boolean improved = true;

        while (improved) {
            improved = false;
            int[] nbrs = getNeighbors(current, layer);
            for (int neighbor : nbrs) {
                float dist = distance(query, neighbor);
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
     */
    private NeighborQueue searchLayer(float[] query, int entryNode, int ef, int layer) {
        int currentNodeCount = nodeCount;  // snapshot for BitSet sizing
        BitSet visited = new BitSet(currentNodeCount);
        // candidates: max-heap (worst on top) for bounded top-K tracking
        NeighborQueue candidates = new NeighborQueue(ef + 1, ef, maxHeap());
        // workQueue: min-heap (best on top) for BFS expansion
        NeighborQueue workQueue = new NeighborQueue(ef + 1, minHeap());

        float entryDist = distance(query, entryNode);
        candidates.add(entryNode, entryDist);
        workQueue.add(entryNode, entryDist);
        visited.set(entryNode);

        while (!workQueue.isEmpty()) {
            // Retrieve score before polling to avoid recomputing distance
            float currentDist = workQueue.topScore();
            int current = workQueue.poll();

            // Stop if current best candidate is worse than worst in result set
            if (candidates.size() >= ef && !isBetter(currentDist, candidates.topScore())) {
                break;
            }

            int[] nbrs = getNeighbors(current, layer);
            for (int neighbor : nbrs) {
                if (!visited.get(neighbor)) {
                    visited.set(neighbor);
                    float dist = distance(query, neighbor);
                    if (candidates.size() < ef || isBetter(dist, candidates.topScore())) {
                        candidates.add(neighbor, dist);
                        workQueue.add(neighbor, dist);
                    }
                }
            }
        }

        return candidates;
    }

    /**
     * Selects up to maxConn best neighbors from the candidate queue.
     */
    private int[] selectNeighbors(NeighborQueue candidates, int maxConn) {
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
    private void addConnection(int fromNode, int toNode, int layer, int maxConn) {
        int[] currentNeighbors = getNeighbors(fromNode, layer);

        // Check if already connected
        for (int n : currentNeighbors) {
            if (n == toNode) return;
        }

        if (currentNeighbors.length < maxConn) {
            // Room available — append (pre-sized array avoids repeated growth)
            int[] newNeighbors = new int[currentNeighbors.length + 1];
            System.arraycopy(currentNeighbors, 0, newNeighbors, 0, currentNeighbors.length);
            newNeighbors[currentNeighbors.length] = toNode;
            setNeighbors(fromNode, layer, newNeighbors);
        } else {
            // Full — prune: keep the best maxConn neighbors
            NeighborQueue queue = new NeighborQueue(maxConn + 1, false);
            for (int n : currentNeighbors) {
                queue.add(n, distance(vectors[fromNode], n));
            }
            queue.add(toNode, distance(vectors[fromNode], toNode));

            ScoredResult[] best = queue.toSortedResults(null, similarityFunction.higherIsBetter());
            int keepCount = Math.min(best.length, maxConn);
            int[] pruned = new int[keepCount];
            for (int i = 0; i < keepCount; i++) {
                pruned[i] = best[i].index();
            }
            setNeighbors(fromNode, layer, pruned);
        }
    }

    // ─────────────── Helpers ───────────────

    private int[] getNeighbors(int nodeIdx, int layer) {
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

    private void setNeighbors(int nodeIdx, int layer, int[] nbrs) {
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

    private float distance(float[] query, int nodeIdx) {
        return similarityFunction.compute(query, vectors[nodeIdx]);
    }

    /** Returns true if scoreA is "better" than scoreB. */
    private boolean isBetter(float scoreA, float scoreB) {
        if (similarityFunction.higherIsBetter()) {
            return scoreA > scoreB;
        } else {
            return scoreA < scoreB;
        }
    }

    /** Min-heap: best (smallest distance / highest similarity) on top. */
    private boolean minHeap() {
        return !similarityFunction.higherIsBetter(); // distance: min on top
    }

    /** Max-heap: worst on top (for bounded eviction). */
    private boolean maxHeap() {
        return similarityFunction.higherIsBetter(); // similarity: worst=lowest on top → actually we want max-heap for worst tracking
    }

    private int randomLevel() {
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

    /** Returns the inline vector copy for the given node. */
    public float[] getVector(int nodeIdx) { return vectors[nodeIdx]; }

    /** Returns the level for the given node. */
    public int getLevel(int nodeIdx) { return nodeLevels[nodeIdx]; }

    /** Returns the neighbor indices at the specified layer. */
    public int[] getNeighborsAtLayer(int nodeIdx, int layer) {
        return getNeighbors(nodeIdx, layer);
    }
}
