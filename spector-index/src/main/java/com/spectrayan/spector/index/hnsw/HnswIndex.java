package com.spectrayan.spector.index;


import com.spectrayan.spector.config.HnswParams;
import com.spectrayan.spector.core.similarity.SimilarityFunction;
import com.spectrayan.spector.storage.VectorStore;

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
 * <h3>Vector Storage Modes</h3>
 * <ul>
 *   <li><b>VectorStore-backed</b> (preferred): vectors are read from an off-heap
 *       {@link VectorStore} during traversal — zero heap overhead per vector.</li>
 *   <li><b>Inline</b> (legacy/tests): full float32 copies stored in a heap-resident
 *       {@code float[][]} for fast distance computation.</li>
 * </ul>
 *
 * @see AbstractHnswIndex
 * @see QuantizedHnswIndex
 */
public class HnswIndex extends AbstractHnswIndex {

    private static final Logger log = LoggerFactory.getLogger(HnswIndex.class);

    // ── Vector storage: exactly one of these is non-null ──
    private final float[][] vectors;       // inline mode (null when store-backed)
    private final VectorStore vectorStore;  // store-backed mode (null when inline)

    // ── Pre-allocated read buffer for store-backed mode (per-thread for concurrent reads) ──
    private final ThreadLocal<float[]> readBuffer;

    /**
     * Creates a new HNSW index with inline vector storage (original behavior).
     *
     * <p>Vectors are copied into a heap-resident {@code float[][]} for fast
     * distance computation during graph traversal. Use this constructor for
     * tests or when no VectorStore is available.</p>
     *
     * @param dimensions         vector dimensionality
     * @param capacity           max number of vectors
     * @param similarityFunction distance/similarity metric
     * @param params             HNSW tuning parameters
     */
    public HnswIndex(int dimensions, int capacity, SimilarityFunction similarityFunction, HnswParams params) {
        super(dimensions, capacity, similarityFunction, params);
        this.vectors = new float[capacity][];
        this.vectorStore = null;
        this.readBuffer = null;

        log.info("HnswIndex created: dims={}, capacity={}, M={}, efC={}, efS={}, similarity={}, mode=inline",
                dimensions, capacity, params.m(), params.efConstruction(), params.efSearch(),
                similarityFunction);
    }

    /**
     * Creates a new HNSW index backed by an off-heap {@link VectorStore}.
     *
     * <p>During graph traversal and construction, vectors are read directly from
     * the store via {@code storeIndices[nodeIdx]} — no heap-resident vector copy
     * is kept. This eliminates the {@code O(capacity × dims × 4)} heap overhead
     * of the inline mode.</p>
     *
     * @param dimensions         vector dimensionality
     * @param capacity           max number of vectors
     * @param similarityFunction distance/similarity metric
     * @param params             HNSW tuning parameters
     * @param vectorStore        the off-heap vector store to read from
     */
    public HnswIndex(int dimensions, int capacity, SimilarityFunction similarityFunction,
                     HnswParams params, VectorStore vectorStore) {
        super(dimensions, capacity, similarityFunction, params);
        this.vectors = null;
        this.vectorStore = vectorStore;
        this.readBuffer = ThreadLocal.withInitial(() -> new float[dimensions]);

        log.info("HnswIndex created: dims={}, capacity={}, M={}, efC={}, efS={}, similarity={}, mode=store-backed",
                dimensions, capacity, params.m(), params.efConstruction(), params.efSearch(),
                similarityFunction);
    }

    /** Creates with default params (inline mode). */
    public HnswIndex(int dimensions, int capacity, SimilarityFunction similarityFunction) {
        this(dimensions, capacity, similarityFunction, HnswParams.DEFAULT);
    }

    // ─────────────── Template method implementations ───────────────

    @Override
    protected float computeDistance(float[] query, int nodeIdx) {
        if (vectorStore != null) {
            // Store-backed: read into per-thread buffer, compute distance
            float[] buf = readBuffer.get();
            vectorStore.getByIndex(storeIndices[nodeIdx], buf, 0);
            return similarityFunction.compute(query, buf);
        }
        return similarityFunction.compute(query, vectors[nodeIdx]);
    }

    @Override
    protected float[] getNodeVector(int nodeIdx) {
        if (vectorStore != null) {
            // Store-backed: must allocate since callers may hold the reference
            return vectorStore.getByIndex(storeIndices[nodeIdx]);
        }
        return vectors[nodeIdx];
    }

    @Override
    protected void storeVector(int nodeIdx, float[] vector) {
        if (vectors != null) {
            vectors[nodeIdx] = Arrays.copyOf(vector, vector.length);
        }
        // Store-backed: no-op — vector already lives in the VectorStore
    }

    // ─────────────── Serialization accessor ───────────────

    /**
     * Returns the inline vector copy for the given node.
     *
     * <p>In store-backed mode, reads from the underlying VectorStore instead.</p>
     */
    public float[] getVector(int nodeIdx) {
        if (vectorStore != null) {
            return vectorStore.getByIndex(storeIndices[nodeIdx]);
        }
        return vectors[nodeIdx];
    }

    /**
     * Returns whether this index uses store-backed vector storage (off-heap).
     */
    public boolean isStoreBacked() {
        return vectorStore != null;
    }
}
