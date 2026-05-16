package com.spectrayan.spector.index;

import com.spectrayan.spector.core.ScalarQuantizer;
import com.spectrayan.spector.core.SimilarityFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.BitSet;

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
 *
 * @see AbstractHnswIndex
 * @see HnswIndex
 */
public class QuantizedHnswIndex extends AbstractHnswIndex {

    private static final Logger log = LoggerFactory.getLogger(QuantizedHnswIndex.class);

    /** Number of vectors to buffer before auto-calibrating the quantizer. */
    private static final int CALIBRATION_SAMPLE_SIZE = 10_000;

    // ── Vector storage ──
    private final float[][] floatVectors;      // kept for re-ranking and construction
    private final byte[][] quantizedVectors;   // quantized for fast graph traversal

    // ── Quantizer state ──
    private volatile ScalarQuantizer quantizer;
    private float[][] calibrationBuffer;
    private int calibrationCount;

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
        super(dimensions, capacity, similarityFunction, params);
        this.quantizer = quantizer;

        this.floatVectors = new float[capacity][];
        this.quantizedVectors = new byte[capacity][];

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

    // ─────────────── Template method implementations ───────────────

    @Override
    protected float computeDistance(float[] query, int nodeIdx) {
        return similarityFunction.compute(query, floatVectors[nodeIdx]);
    }

    @Override
    protected float[] getNodeVector(int nodeIdx) {
        return floatVectors[nodeIdx];
    }

    @Override
    protected void storeVector(int nodeIdx, float[] vector) {
        floatVectors[nodeIdx] = Arrays.copyOf(vector, vector.length);

        // Handle quantizer calibration
        if (quantizer == null) {
            if (calibrationCount < calibrationBuffer.length) {
                calibrationBuffer[calibrationCount++] = vector;
            }
            if (calibrationCount >= calibrationBuffer.length
                    || calibrationCount >= CALIBRATION_SAMPLE_SIZE) {
                calibrate();
            }
        }

        // Quantize if calibrated
        if (quantizer != null) {
            quantizedVectors[nodeIdx] = quantizer.encode(vector);
        }
    }

    // ─────────────── Overridden search with quantized re-ranking ───────────────

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

        ScoredResult[] exactResults = new ScoredResult[reRankCount];
        for (int i = 0; i < reRankCount; i++) {
            int nodeIdx = candidateIndices[i];
            float exactScore = similarityFunction.compute(query, floatVectors[nodeIdx]);
            exactResults[i] = new ScoredResult(ids[nodeIdx], nodeIdx, exactScore);
        }

        if (similarityFunction.higherIsBetter()) {
            Arrays.sort(exactResults);
        } else {
            Arrays.sort(exactResults, ScoredResult::compareAscending);
        }

        int resultCount = Math.min(k, exactResults.length);
        return Arrays.copyOf(exactResults, resultCount);
    }

    // ─────────────── Quantized layer-0 search ───────────────

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

    // ─────────────── Quantizer helpers ───────────────

    private float distanceQuantized(float[] query, int nodeIdx,
                                     float[] qMins, float[] qScales) {
        return similarityFunction.computeQuantized(
                query, quantizedVectors[nodeIdx], qMins, qScales, dimensions);
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

        calibrationBuffer = null;
        calibrationCount = 0;
    }

    // ─────────────── Public accessors ───────────────

    /** Returns the quantizer (may be null if not yet calibrated). */
    public ScalarQuantizer quantizer() { return quantizer; }

    /** Returns true if the quantizer has been calibrated. */
    public boolean isCalibrated() { return quantizer != null; }
}
