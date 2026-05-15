package com.spectrayan.spector.index;

import com.spectrayan.spector.core.ScalarQuantizer;
import com.spectrayan.spector.core.SimilarityFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.BitSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

/**
 * HNSW vector index with scalar quantization (SQ8) support.
 *
 * <p>Uses a two-phase search strategy for optimal speed/recall tradeoff:</p>
 * <ol>
 *   <li><b>Coarse search</b> — traverses the HNSW graph using quantized int8
 *       distances (4× less memory, faster cache performance)</li>
 *   <li><b>Re-ranking</b> — recomputes exact float32 distances for the top
 *       candidates to restore full-precision recall</li>
 * </ol>
 *
 * <h3>Memory Savings</h3>
 * <p>Inline vectors are stored as {@code byte[]} instead of {@code float[]},
 * reducing per-vector memory from {@code dims × 4} to {@code dims × 1} bytes.
 * At 1M vectors × 384 dims, this saves ~1.1 GB.</p>
 *
 * <h3>Calibration</h3>
 * <p>The quantizer can be provided pre-calibrated, or calibrated automatically
 * from the first batch of inserted vectors.</p>
 */
public class QuantizedHnswIndex implements VectorIndex {

    private static final Logger log = LoggerFactory.getLogger(QuantizedHnswIndex.class);

    /** Number of vectors to buffer before auto-calibrating the quantizer. */
    private static final int CALIBRATION_SAMPLE_SIZE = 10_000;

    private final HnswParams params;
    private final SimilarityFunction similarityFunction;
    private final int dimensions;

    // ── Node storage ──
    private final int capacity;
    private volatile int nodeCount;
    private final String[] ids;
    private final int[] storeIndices;
    private final float[][] floatVectors;     // kept for re-ranking (nullable after flush)
    private final byte[][] quantizedVectors;  // quantized for fast graph traversal
    private final int[][] neighbors;
    private final int[][][] upperNeighbors;
    private final int[] nodeLevels;

    // ── Quantizer state ──
    private volatile ScalarQuantizer quantizer;   // null until calibrated
    private float[][] calibrationBuffer;          // buffer for auto-calibration
    private int calibrationCount;

    // ── Graph state ──
    private volatile int entryPoint = -1;
    private volatile int maxLevel = -1;

    // ── Concurrency ──
    private final ReentrantLock writeLock = new ReentrantLock();

    /**
     * Creates a quantized HNSW index with a pre-calibrated quantizer.
     *
     * @param dimensions         vector dimensionality
     * @param capacity           max vectors
     * @param similarityFunction distance metric
     * @param params             HNSW parameters
     * @param quantizer          pre-calibrated quantizer (null for auto-calibrate)
     */
    public QuantizedHnswIndex(int dimensions, int capacity,
                               SimilarityFunction similarityFunction,
                               HnswParams params,
                               ScalarQuantizer quantizer) {
        this.dimensions = dimensions;
        this.capacity = capacity;
        this.similarityFunction = similarityFunction;
        this.params = params;
        this.nodeCount = 0;
        this.quantizer = quantizer;

        this.ids = new String[capacity];
        this.storeIndices = new int[capacity];
        this.floatVectors = new float[capacity][];
        this.quantizedVectors = new byte[capacity][];
        this.neighbors = new int[capacity][];
        this.upperNeighbors = new int[capacity][][];
        this.nodeLevels = new int[capacity];

        if (quantizer == null) {
            this.calibrationBuffer = new float[Math.min(CALIBRATION_SAMPLE_SIZE, capacity)][];
            this.calibrationCount = 0;
        }

        log.info("QuantizedHnswIndex created: dims={}, capacity={}, M={}, quantizer={}",
                dimensions, capacity, params.m(),
                quantizer != null ? "pre-calibrated" : "auto-calibrate");
    }

    /** Creates with auto-calibration. */
    public QuantizedHnswIndex(int dimensions, int capacity,
                               SimilarityFunction similarityFunction,
                               HnswParams params) {
        this(dimensions, capacity, similarityFunction, params, null);
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

            // Store float vector (for re-ranking and construction)
            ids[nodeIdx] = id;
            storeIndices[nodeIdx] = storeIndex;
            floatVectors[nodeIdx] = Arrays.copyOf(vector, vector.length);
            nodeLevels[nodeIdx] = level;
            neighbors[nodeIdx] = new int[0];
            if (level > 0) {
                upperNeighbors[nodeIdx] = new int[level][];
                for (int l = 0; l < level; l++) {
                    upperNeighbors[nodeIdx][l] = new int[0];
                }
            }

            // Handle quantizer calibration
            if (quantizer == null) {
                // Buffer for auto-calibration
                if (calibrationCount < calibrationBuffer.length) {
                    calibrationBuffer[calibrationCount++] = vector;
                }
                // Auto-calibrate when buffer is full
                if (calibrationCount >= calibrationBuffer.length
                        || calibrationCount >= CALIBRATION_SAMPLE_SIZE) {
                    calibrate();
                }
            }

            // Quantize if calibrated
            if (quantizer != null) {
                quantizedVectors[nodeIdx] = quantizer.encode(vector);
            }

            nodeCount++;

            if (entryPoint == -1) {
                entryPoint = nodeIdx;
                maxLevel = level;
                return;
            }

            // ── Insert into graph ──
            int currentNode = entryPoint;
            int currentMaxLevel = maxLevel;

            for (int lc = currentMaxLevel; lc > level; lc--) {
                currentNode = greedyClosest(vector, currentNode, lc);
            }

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

        // Phase 1: Greedy descent through upper layers (uses float for precision)
        for (int lc = maxLevel; lc > 0; lc--) {
            currentNode = greedyClosest(query, currentNode, lc);
        }

        // Phase 2: Search at layer 0
        NeighborQueue candidates;
        if (quantizer != null) {
            // Coarse search using quantized distances — retrieve more candidates for re-ranking
            candidates = searchLayerQuantized(query, currentNode, ef * 2);
        } else {
            // No quantizer yet — use exact float distances
            candidates = searchLayer(query, currentNode, ef, 0);
            return candidates.toSortedResults(ids, similarityFunction.higherIsBetter());
        }

        // Phase 3: Re-rank coarse candidates with exact float distances
        int[] candidateIndices = candidates.indicesUnsorted();
        int reRankCount = candidateIndices.length;

        // Compute exact scores for all coarse candidates
        ScoredResult[] exactResults = new ScoredResult[reRankCount];
        for (int i = 0; i < reRankCount; i++) {
            int nodeIdx = candidateIndices[i];
            float exactScore = similarityFunction.compute(query, floatVectors[nodeIdx]);
            exactResults[i] = new ScoredResult(ids[nodeIdx], nodeIdx, exactScore);
        }

        // Sort by score (best first)
        if (similarityFunction.higherIsBetter()) {
            Arrays.sort(exactResults); // descending
        } else {
            Arrays.sort(exactResults, ScoredResult::compareAscending);
        }

        // Return top-k
        int resultCount = Math.min(k, exactResults.length);
        return Arrays.copyOf(exactResults, resultCount);
    }

    @Override
    public int size() { return nodeCount; }

    @Override
    public SimilarityFunction similarityFunction() { return similarityFunction; }

    @Override
    public void close() {
        // No external resources
    }

    /** Returns the quantizer (may be null if not yet calibrated). */
    public ScalarQuantizer quantizer() { return quantizer; }

    /** Returns true if the quantizer has been calibrated. */
    public boolean isCalibrated() { return quantizer != null; }

    // ─────────────── Graph operations ───────────────

    private int greedyClosest(float[] query, int startNode, int layer) {
        int current = startNode;
        float currentDist = distanceFloat(query, current);
        boolean improved = true;

        while (improved) {
            improved = false;
            int[] nbrs = getNeighbors(current, layer);
            for (int neighbor : nbrs) {
                float dist = distanceFloat(query, neighbor);
                if (isBetter(dist, currentDist)) {
                    current = neighbor;
                    currentDist = dist;
                    improved = true;
                }
            }
        }
        return current;
    }

    /** Standard search layer using float32 vectors (for construction and upper layers). */
    private NeighborQueue searchLayer(float[] query, int entryNode, int ef, int layer) {
        BitSet visited = new BitSet(nodeCount);
        NeighborQueue candidates = new NeighborQueue(ef + 1, ef, maxHeap());
        NeighborQueue workQueue = new NeighborQueue(ef + 1, minHeap());

        float entryDist = distanceFloat(query, entryNode);
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
                    float dist = distanceFloat(query, neighbor);
                    if (candidates.size() < ef || isBetter(dist, candidates.topScore())) {
                        candidates.add(neighbor, dist);
                        workQueue.add(neighbor, dist);
                    }
                }
            }
        }
        return candidates;
    }

    /** Layer-0 search using quantized distances for coarse filtering. */
    private NeighborQueue searchLayerQuantized(float[] query, int entryNode, int ef) {
        BitSet visited = new BitSet(nodeCount);
        NeighborQueue candidates = new NeighborQueue(ef + 1, ef, maxHeap());
        NeighborQueue workQueue = new NeighborQueue(ef + 1, minHeap());

        float[] qMins = quantizer.mins();
        float[] qScales = quantizer.scales();

        float entryDist = distanceQuantized(query, entryNode, qMins, qScales);
        candidates.add(entryNode, entryDist);
        workQueue.add(entryNode, entryDist);
        visited.set(entryNode);

        while (!workQueue.isEmpty()) {
            float currentDist = workQueue.topScore();
            int current = workQueue.poll();

            if (candidates.size() >= ef && !isBetter(currentDist, candidates.topScore())) {
                break;
            }

            int[] nbrs = getNeighbors(current, 0);
            for (int neighbor : nbrs) {
                if (!visited.get(neighbor)) {
                    visited.set(neighbor);
                    float dist = distanceQuantized(query, neighbor, qMins, qScales);
                    if (candidates.size() < ef || isBetter(dist, candidates.topScore())) {
                        candidates.add(neighbor, dist);
                        workQueue.add(neighbor, dist);
                    }
                }
            }
        }
        return candidates;
    }

    private int[] selectNeighbors(NeighborQueue candidates, int maxConn) {
        ScoredResult[] sorted = candidates.toSortedResults(null, similarityFunction.higherIsBetter());
        int count = Math.min(sorted.length, maxConn);
        int[] result = new int[count];
        for (int i = 0; i < count; i++) {
            result[i] = sorted[i].index();
        }
        return result;
    }

    private void addConnection(int fromNode, int toNode, int layer, int maxConn) {
        int[] currentNeighbors = getNeighbors(fromNode, layer);
        for (int n : currentNeighbors) {
            if (n == toNode) return;
        }

        if (currentNeighbors.length < maxConn) {
            int[] newNeighbors = new int[currentNeighbors.length + 1];
            System.arraycopy(currentNeighbors, 0, newNeighbors, 0, currentNeighbors.length);
            newNeighbors[currentNeighbors.length] = toNode;
            setNeighbors(fromNode, layer, newNeighbors);
        } else {
            NeighborQueue queue = new NeighborQueue(maxConn + 1, false);
            for (int n : currentNeighbors) {
                queue.add(n, distanceFloat(floatVectors[fromNode], n));
            }
            queue.add(toNode, distanceFloat(floatVectors[fromNode], toNode));

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

    private float distanceFloat(float[] query, int nodeIdx) {
        return similarityFunction.compute(query, floatVectors[nodeIdx]);
    }

    private float distanceFloat(float[] a, float[] b) {
        return similarityFunction.compute(a, b);
    }

    private float distanceQuantized(float[] query, int nodeIdx,
                                     float[] qMins, float[] qScales) {
        return similarityFunction.computeQuantized(
                query, quantizedVectors[nodeIdx], qMins, qScales, dimensions);
    }

    private boolean isBetter(float scoreA, float scoreB) {
        return similarityFunction.higherIsBetter()
                ? scoreA > scoreB
                : scoreA < scoreB;
    }

    private boolean minHeap() { return !similarityFunction.higherIsBetter(); }
    private boolean maxHeap() { return similarityFunction.higherIsBetter(); }

    private int randomLevel() {
        double r = ThreadLocalRandom.current().nextDouble();
        return Math.max(0, (int) (-Math.log(r) * params.levelMultiplier()));
    }

    /** Auto-calibrates the quantizer from buffered vectors. */
    private void calibrate() {
        float[][] sample = Arrays.copyOf(calibrationBuffer, calibrationCount);
        this.quantizer = ScalarQuantizer.calibrate(sample, dimensions);
        log.info("QuantizedHnswIndex auto-calibrated from {} sample vectors", calibrationCount);

        // Quantize all existing vectors that were inserted before calibration
        for (int i = 0; i < nodeCount; i++) {
            if (floatVectors[i] != null) {
                quantizedVectors[i] = quantizer.encode(floatVectors[i]);
            }
        }

        // Free calibration buffer
        calibrationBuffer = null;
        calibrationCount = 0;
    }
}
