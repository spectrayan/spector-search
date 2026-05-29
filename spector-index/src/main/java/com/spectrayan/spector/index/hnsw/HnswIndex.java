package com.spectrayan.spector.index;


import com.spectrayan.spector.config.HnswParams;
import com.spectrayan.spector.core.similarity.SimilarityFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * HNSW (Hierarchical Navigable Small World) vector index.
 *
 * <p>Implements approximate nearest-neighbor search using a multi-layer
 * navigable small world graph. Distance computations delegate to the
 * SIMD-accelerated kernels in {@code spector-core}.</p>
 *
 * <p>This implementation stores full float32 vectors inline for fast
 * distance computation during graph traversal and construction.</p>
 *
 * @see AbstractHnswIndex
 * @see QuantizedHnswIndex
 */
public class HnswIndex extends AbstractHnswIndex {

    private static final Logger log = LoggerFactory.getLogger(HnswIndex.class);

    // ── Float32 vector storage (inline copy for fast distance computation) ──
    private final float[][] vectors;

    /**
     * Creates a new HNSW index.
     *
     * @param dimensions         vector dimensionality
     * @param capacity           max number of vectors
     * @param similarityFunction distance/similarity metric
     * @param params             HNSW tuning parameters
     */
    public HnswIndex(int dimensions, int capacity, SimilarityFunction similarityFunction, HnswParams params) {
        super(dimensions, capacity, similarityFunction, params);
        this.vectors = new float[capacity][];

        log.info("HnswIndex created: dims={}, capacity={}, M={}, efC={}, efS={}, similarity={}",
                dimensions, capacity, params.m(), params.efConstruction(), params.efSearch(),
                similarityFunction);
    }

    /** Creates with default params. */
    public HnswIndex(int dimensions, int capacity, SimilarityFunction similarityFunction) {
        this(dimensions, capacity, similarityFunction, HnswParams.DEFAULT);
    }

    // ─────────────── Template method implementations ───────────────

    @Override
    protected float computeDistance(float[] query, int nodeIdx) {
        return similarityFunction.compute(query, vectors[nodeIdx]);
    }

    @Override
    protected float[] getNodeVector(int nodeIdx) {
        return vectors[nodeIdx];
    }

    @Override
    protected void storeVector(int nodeIdx, float[] vector) {
        vectors[nodeIdx] = Arrays.copyOf(vector, vector.length);
    }

    // ─────────────── Serialization accessor ───────────────

    /** Returns the inline vector copy for the given node. */
    public float[] getVector(int nodeIdx) { return vectors[nodeIdx]; }
}
