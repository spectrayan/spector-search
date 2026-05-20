package com.spectrayan.spector.index;

import java.util.Arrays;
import java.util.BitSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.core.CrumbPacker;
import com.spectrayan.spector.core.NibblePacker;
import com.spectrayan.spector.core.NonUniformQuantizer;
import com.spectrayan.spector.core.PackedDotProduct;
import com.spectrayan.spector.core.QuantizationType;
import com.spectrayan.spector.core.ScalarQuantizer;
import com.spectrayan.spector.core.SimilarityFunction;

/**
 * HNSW vector index with scalar quantization (INT8, INT4, INT2) support.
 *
 * <p>Uses a two-phase search strategy for optimal speed/recall tradeoff:</p>
 * <ol>
 *   <li><b>Coarse search</b> — traverses the HNSW graph using quantized
 *       distances (INT8 linear, or INT4/INT2 packed dot product via SIMD)</li>
 *   <li><b>Re-ranking</b> — recomputes exact float32 distances for the top
 *       candidates to restore full-precision recall</li>
 * </ol>
 *
 * <h3>Quantization Types</h3>
 * <ul>
 *   <li><b>INT8</b> — one byte per dimension, linear min/max calibration (4× compression)</li>
 *   <li><b>INT4</b> — nibble-packed (2 values/byte), non-uniform quantile calibration (8× compression)</li>
 *   <li><b>INT2</b> — crumb-packed (4 values/byte), non-uniform quantile calibration (16× compression)</li>
 * </ul>
 *
 * <h3>Rescore Strategy</h3>
 * <p>When the oversampling factor is greater than 1, the index retrieves
 * {@code oversamplingFactor × k} candidates using fast quantized distance,
 * then rescores them with exact float32 distances to return the true top-K.</p>
 *
 * @see AbstractHnswIndex
 * @see HnswIndex
 * @see PackedDotProduct
 */
public class QuantizedHnswIndex extends AbstractHnswIndex {

    private static final Logger log = LoggerFactory.getLogger(QuantizedHnswIndex.class);

    /** Number of vectors to buffer before auto-calibrating the quantizer. */
    private static final int CALIBRATION_SAMPLE_SIZE = 10_000;

    // ── Vector storage ──
    private final float[][] floatVectors;      // kept for re-ranking and construction
    private final byte[][] quantizedVectors;   // quantized for fast graph traversal

    // ── Quantizer state (INT8) ──
    private volatile ScalarQuantizer quantizer;
    private float[][] calibrationBuffer;
    private int calibrationCount;

    // ── Quantizer state (INT4/INT2) ──
    private final QuantizationType quantizationType;
    private final NonUniformQuantizer nonUniformQuantizer;
    private final float[] globalCentroids; // averaged centroids for PackedDotProduct

    // ── Rescore configuration ──
    private final int oversamplingFactor;

    /**
     * Creates a quantized HNSW index with a pre-calibrated INT8 quantizer.
     *
     * @param dimensions         vector dimensionality
     * @param capacity           max vectors
     * @param similarityFunction distance metric
     * @param params             HNSW parameters
     * @param quantizer          pre-calibrated INT8 quantizer (null for auto-calibrate)
     */
    public QuantizedHnswIndex(int dimensions, int capacity,
                               SimilarityFunction similarityFunction,
                               HnswParams params,
                               ScalarQuantizer quantizer) {
        this(dimensions, capacity, similarityFunction, params, quantizer,
                QuantizationType.SCALAR_INT8, null, 1);
    }

    /** Creates with auto-calibration (INT8, no oversampling). */
    public QuantizedHnswIndex(int dimensions, int capacity,
                               SimilarityFunction similarityFunction,
                               HnswParams params) {
        this(dimensions, capacity, similarityFunction, params, null,
                QuantizationType.SCALAR_INT8, null, 1);
    }

    /**
     * Creates a quantized HNSW index supporting INT8, INT4, or INT2 quantization
     * with configurable rescore oversampling.
     *
     * @param dimensions           vector dimensionality
     * @param capacity             max vectors
     * @param similarityFunction   distance metric
     * @param params               HNSW parameters
     * @param quantizer            pre-calibrated INT8 quantizer (null for auto-calibrate; ignored for INT4/INT2)
     * @param quantizationType     quantization type (SCALAR_INT8, SCALAR_INT4, or SCALAR_INT2)
     * @param nonUniformQuantizer  calibrated non-uniform quantizer (required for INT4/INT2, null for INT8)
     * @param oversamplingFactor   rescore oversampling factor (1 = no rescore, >1 = oversample and rescore)
     */
    public QuantizedHnswIndex(int dimensions, int capacity,
                               SimilarityFunction similarityFunction,
                               HnswParams params,
                               ScalarQuantizer quantizer,
                               QuantizationType quantizationType,
                               NonUniformQuantizer nonUniformQuantizer,
                               int oversamplingFactor) {
        super(dimensions, capacity, similarityFunction, params);

        this.quantizationType = quantizationType != null ? quantizationType : QuantizationType.SCALAR_INT8;
        this.nonUniformQuantizer = nonUniformQuantizer;
        this.oversamplingFactor = Math.max(1, oversamplingFactor);

        this.floatVectors = new float[capacity][];
        this.quantizedVectors = new byte[capacity][];

        // INT4/INT2 path: pre-compute global centroids for PackedDotProduct
        if (this.quantizationType == QuantizationType.SCALAR_INT4
                || this.quantizationType == QuantizationType.SCALAR_INT2) {
            if (nonUniformQuantizer != null) {
                this.globalCentroids = computeGlobalCentroids(nonUniformQuantizer);
            } else {
                // Deferred calibration: centroids will be computed when quantizer is set
                this.globalCentroids = null;
            }
            this.quantizer = null;
            this.calibrationBuffer = null;
            this.calibrationCount = 0;
        } else {
            // INT8 path
            this.globalCentroids = null;
            this.quantizer = quantizer;
            if (quantizer == null) {
                this.calibrationBuffer = new float[Math.min(CALIBRATION_SAMPLE_SIZE, capacity)][];
                this.calibrationCount = 0;
            }
        }

        log.info("QuantizedHnswIndex created: dims={}, capacity={}, M={}, type={}, oversampling={}, quantizer={}",
                dimensions, capacity, params.m(), this.quantizationType, this.oversamplingFactor,
                this.quantizationType == QuantizationType.SCALAR_INT8
                        ? (quantizer != null ? "pre-calibrated" : "auto-calibrate")
                        : "non-uniform");
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

        switch (quantizationType) {
            case SCALAR_INT8 -> storeVectorInt8(nodeIdx, vector);
            case SCALAR_INT4 -> storeVectorInt4(nodeIdx, vector);
            case SCALAR_INT2 -> storeVectorInt2(nodeIdx, vector);
            default -> throw new IllegalStateException("Unsupported type: " + quantizationType);
        }
    }

    private void storeVectorInt8(int nodeIdx, float[] vector) {
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

    private void storeVectorInt4(int nodeIdx, float[] vector) {
        int[] levels = nonUniformQuantizer.encode(vector);
        quantizedVectors[nodeIdx] = NibblePacker.pack(levels, dimensions);
    }

    private void storeVectorInt2(int nodeIdx, float[] vector) {
        int[] levels = nonUniformQuantizer.encode(vector);
        quantizedVectors[nodeIdx] = CrumbPacker.pack(levels, dimensions);
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

        // Phase 2: Search at layer 0 using quantized distance
        NeighborQueue candidates;
        boolean hasQuantizer = (quantizationType == QuantizationType.SCALAR_INT8 && quantizer != null)
                || quantizationType == QuantizationType.SCALAR_INT4
                || quantizationType == QuantizationType.SCALAR_INT2;

        if (hasQuantizer) {
            // When oversampling > 1, retrieve more candidates for rescore
            int effectiveEf = oversamplingFactor > 1
                    ? Math.max(ef, oversamplingFactor * k)
                    : ef;
            candidates = searchLayerQuantized(query, currentNode, effectiveEf);
        } else {
            // No quantizer yet — use exact float distances
            candidates = searchLayer(query, currentNode, ef, 0);
            return candidates.toSortedResults(ids, similarityFunction.higherIsBetter());
        }

        // Phase 3: Rescore — re-rank coarse candidates with exact float distances
        // When oversamplingFactor == 1, skip rescoring and return quantized results directly
        if (oversamplingFactor <= 1) {
            ScoredResult[] sorted = candidates.toSortedResults(ids, similarityFunction.higherIsBetter());
            int resultCount = Math.min(k, sorted.length);
            return resultCount == sorted.length ? sorted : Arrays.copyOf(sorted, resultCount);
        }

        // Rescore: compute exact float32 distances for oversampled candidates
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

        float entryDist = computeQuantizedDistance(query, entryNode);
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
                    float dist = computeQuantizedDistance(query, neighbor);
                    if (candidates.size() < ef || isBetter(dist, candidates.topScore())) {
                        candidates.add(neighbor, dist);
                        workQueue.add(neighbor, dist);
                    }
                }
            }
        }
        return candidates;
    }

    // ─────────────── Quantized distance dispatch ───────────────

    /**
     * Computes quantized distance between a query and a stored vector,
     * dispatching to the appropriate kernel based on quantization type.
     */
    private float computeQuantizedDistance(float[] query, int nodeIdx) {
        return switch (quantizationType) {
            case SCALAR_INT8 -> distanceQuantizedInt8(query, nodeIdx);
            case SCALAR_INT4 -> distanceQuantizedInt4(query, nodeIdx);
            case SCALAR_INT2 -> distanceQuantizedInt2(query, nodeIdx);
            default -> similarityFunction.compute(query, floatVectors[nodeIdx]);
        };
    }

    private float distanceQuantizedInt8(float[] query, int nodeIdx) {
        float[] qMins = quantizer.mins();
        float[] qScales = quantizer.scales();
        return similarityFunction.computeQuantized(
                query, quantizedVectors[nodeIdx], qMins, qScales, dimensions);
    }

    private float distanceQuantizedInt4(float[] query, int nodeIdx) {
        byte[] packed = quantizedVectors[nodeIdx];
        if (packed == null) {
            return similarityFunction.compute(query, floatVectors[nodeIdx]);
        }
        // PackedDotProduct computes sum(query[i] * centroids[level[i]])
        // For cosine/dot product similarity, higher is better (negate for distance)
        float dotProduct = PackedDotProduct.computeInt4(query, packed, globalCentroids, dimensions);
        return similarityFunction.higherIsBetter() ? dotProduct : -dotProduct;
    }

    private float distanceQuantizedInt2(float[] query, int nodeIdx) {
        byte[] packed = quantizedVectors[nodeIdx];
        if (packed == null) {
            return similarityFunction.compute(query, floatVectors[nodeIdx]);
        }
        float dotProduct = PackedDotProduct.computeInt2(query, packed, globalCentroids, dimensions);
        return similarityFunction.higherIsBetter() ? dotProduct : -dotProduct;
    }

    // ─────────────── Quantizer helpers ───────────────

    /**
     * Computes global centroids by averaging per-dimension centroids from the NonUniformQuantizer.
     * This produces a single centroid lookup table for PackedDotProduct.
     */
    private static float[] computeGlobalCentroids(NonUniformQuantizer nuq) {
        int levels = nuq.levels();
        int dims = nuq.dimensions();
        float[] global = new float[levels];

        for (int level = 0; level < levels; level++) {
            double sum = 0.0;
            for (int dim = 0; dim < dims; dim++) {
                float[] dimCentroids = nuq.centroids(dim);
                sum += dimCentroids[level];
            }
            global[level] = (float) (sum / dims);
        }
        return global;
    }

    /** Auto-calibrates the INT8 quantizer from buffered vectors. */
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

    /** Returns the INT8 quantizer (may be null if not INT8 or not yet calibrated). */
    public ScalarQuantizer quantizer() { return quantizer; }

    /** Returns true if the quantizer has been calibrated (INT8) or non-uniform quantizer is set (INT4/INT2). */
    public boolean isCalibrated() {
        return switch (quantizationType) {
            case SCALAR_INT8 -> quantizer != null;
            case SCALAR_INT4, SCALAR_INT2 -> nonUniformQuantizer != null;
            default -> false;
        };
    }

    /** Returns the quantization type used by this index. */
    public QuantizationType quantizationType() { return quantizationType; }

    /** Returns the non-uniform quantizer (INT4/INT2), or null if INT8. */
    public NonUniformQuantizer nonUniformQuantizer() { return nonUniformQuantizer; }

    /** Returns the configured oversampling factor. */
    public int oversamplingFactor() { return oversamplingFactor; }
}
