package com.spectrayan.spector.gpu;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * CUDA-accelerated cosine similarity kernel with CPU SIMD fallback.
 *
 * <p>Computes cosine similarity between a query vector and a batch of document vectors.
 * When CUDA is available, computation happens on the GPU via Panama FFM. When CUDA is
 * unavailable or encounters an error, the kernel transparently falls back to CPU SIMD
 * computation using the Java Vector API.</p>
 *
 * <h3>Key Features</h3>
 * <ul>
 *   <li><b>Norm caching:</b> Pre-computes and caches vector norms so repeated queries
 *       against the same document batch skip norm recomputation</li>
 *   <li><b>Pre-normalized detection:</b> When all vectors have unit norm (within float32
 *       epsilon), skips norm computation entirely and uses dot-product directly</li>
 *   <li><b>NaN/Infinity handling:</b> Returns {@link Float#NaN} as error indication for
 *       computations involving NaN or infinity values, without crashing</li>
 *   <li><b>Transparent fallback:</b> Same interface regardless of GPU availability</li>
 * </ul>
 *
 * <p>Supports vector dimensions that are multiples of 32, ranging from 32 to 2048.</p>
 *
 * @see SimilarityKernel
 * @see GpuCapability
 */
public class CudaCosineKernel implements SimilarityKernel {

    private static final Logger log = LoggerFactory.getLogger(CudaCosineKernel.class);

    /** Preferred SIMD vector species for this hardware. */
    private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;

    /** Float32 epsilon for pre-normalized detection. */
    private static final float EPSILON = 1e-6f;

    /** Minimum supported dimension. */
    private static final int MIN_DIMENSIONS = 32;

    /** Maximum supported dimension. */
    private static final int MAX_DIMENSIONS = 2048;

    /**
     * Cache of pre-computed norms for document batches.
     * Key: identity hash of the database array + numVectors + dimensions.
     */
    private final ConcurrentHashMap<NormCacheKey, float[]> normCache = new ConcurrentHashMap<>();

    /** Whether GPU is currently active for this kernel. */
    private final boolean gpuActive;

    /**
     * Creates a CudaCosineKernel.
     *
     * <p>If CUDA is available, GPU acceleration is used. Otherwise, the kernel
     * transparently falls back to CPU SIMD.</p>
     */
    public CudaCosineKernel() {
        this.gpuActive = GpuCapability.isAvailable();
        if (gpuActive) {
            log.info("CudaCosineKernel initialized with GPU acceleration");
        } else {
            log.info("CudaCosineKernel initialized with CPU SIMD fallback (GPU unavailable)");
        }
    }

    /**
     * Package-private constructor for testing with explicit GPU mode.
     *
     * @param forceGpuActive whether to report GPU as active
     */
    CudaCosineKernel(boolean forceGpuActive) {
        this.gpuActive = forceGpuActive;
    }

    @Override
    public float[] compute(float[] query, float[] database, int numVectors, int dimensions) {
        validateInputs(query, database, numVectors, dimensions);

        if (numVectors == 0) {
            return new float[0];
        }

        // Check query for NaN/Infinity
        if (containsNanOrInfinity(query, 0, dimensions)) {
            float[] results = new float[numVectors];
            Arrays.fill(results, Float.NaN);
            return results;
        }

        if (gpuActive) {
            try {
                return computeGpu(query, database, numVectors, dimensions);
            } catch (Exception e) {
                log.warn("CUDA cosine kernel failed, falling back to CPU SIMD: {}", e.getMessage());
                return computeCpuSimd(query, database, numVectors, dimensions);
            }
        } else {
            return computeCpuSimd(query, database, numVectors, dimensions);
        }
    }

    @Override
    public String name() {
        return "cosine";
    }

    @Override
    public boolean isGpuActive() {
        return gpuActive;
    }

    /**
     * Invalidates the norm cache for a specific database batch.
     * Call this when the database contents change.
     *
     * @param database   the database array reference
     * @param numVectors number of vectors
     * @param dimensions vector dimensionality
     */
    public void invalidateNormCache(float[] database, int numVectors, int dimensions) {
        normCache.remove(new NormCacheKey(System.identityHashCode(database), numVectors, dimensions));
    }

    /**
     * Clears all cached norms.
     */
    public void clearNormCache() {
        normCache.clear();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // GPU computation (delegates to CudaKernelLauncher when available)
    // ─────────────────────────────────────────────────────────────────────────────

    private float[] computeGpu(float[] query, float[] database, int numVectors, int dimensions) {
        // Use the kernel launcher for actual GPU dispatch
        try {
            CudaKernelLauncher launcher = new CudaKernelLauncher();
            try {
                return launcher.batchCosine(query, database, numVectors, dimensions);
            } finally {
                launcher.close();
            }
        } catch (Exception e) {
            log.debug("GPU kernel launch failed, using CPU SIMD: {}", e.getMessage());
            return computeCpuSimd(query, database, numVectors, dimensions);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // CPU SIMD computation (fallback path)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Computes cosine similarity using CPU SIMD (Java Vector API).
     * Implements norm caching and pre-normalized vector detection.
     */
    private float[] computeCpuSimd(float[] query, float[] database, int numVectors, int dimensions) {
        float[] results = new float[numVectors];

        // Compute query norm (SIMD-accelerated)
        float queryNorm = computeNormSimd(query, 0, dimensions);
        if (queryNorm == 0.0f) {
            // Zero-magnitude query: all cosine similarities are 0
            return results;
        }

        // Get or compute document norms (cached)
        float[] docNorms = getOrComputeDocNorms(database, numVectors, dimensions);

        // Check if vectors are pre-normalized (all norms ~= 1.0)
        boolean preNormalized = arePreNormalized(docNorms);
        boolean queryPreNormalized = Math.abs(queryNorm - 1.0f) < EPSILON;

        int vectorLen = SPECIES.length();
        int simdBound = dimensions - (dimensions % vectorLen);

        for (int i = 0; i < numVectors; i++) {
            int offset = i * dimensions;

            // Check document vector for NaN/Infinity
            if (containsNanOrInfinity(database, offset, dimensions)) {
                results[i] = Float.NaN;
                continue;
            }

            float docNorm = docNorms[i];
            if (docNorm == 0.0f) {
                results[i] = 0.0f;
                continue;
            }

            // Compute dot product (SIMD-accelerated)
            float dot = computeDotProductSimd(query, database, offset, dimensions, simdBound, vectorLen);

            if (preNormalized && queryPreNormalized) {
                // Skip norm division for pre-normalized vectors — dot product IS cosine similarity
                results[i] = dot;
            } else {
                results[i] = dot / (queryNorm * docNorm);
            }
        }

        return results;
    }

    /**
     * Computes dot product between query and a database vector slice using SIMD.
     */
    private float computeDotProductSimd(float[] query, float[] database, int offset,
                                         int dimensions, int simdBound, int vectorLen) {
        FloatVector sumVec = FloatVector.zero(SPECIES);
        int d = 0;
        for (; d < simdBound; d += vectorLen) {
            FloatVector qVec = FloatVector.fromArray(SPECIES, query, d);
            FloatVector dbVec = FloatVector.fromArray(SPECIES, database, offset + d);
            sumVec = qVec.fma(dbVec, sumVec);
        }
        float dot = sumVec.reduceLanes(VectorOperators.ADD);

        // Scalar tail
        for (; d < dimensions; d++) {
            dot += query[d] * database[offset + d];
        }
        return dot;
    }

    /**
     * Computes the L2 norm of a vector slice using SIMD.
     */
    private float computeNormSimd(float[] vector, int offset, int dimensions) {
        int vectorLen = SPECIES.length();
        int simdBound = dimensions - (dimensions % vectorLen);

        FloatVector normVec = FloatVector.zero(SPECIES);
        int d = 0;
        for (; d < simdBound; d += vectorLen) {
            FloatVector v = FloatVector.fromArray(SPECIES, vector, offset + d);
            normVec = v.fma(v, normVec);
        }
        float normSq = normVec.reduceLanes(VectorOperators.ADD);

        // Scalar tail
        for (; d < dimensions; d++) {
            normSq += vector[offset + d] * vector[offset + d];
        }
        return (float) Math.sqrt(normSq);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Norm caching
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Gets cached document norms or computes and caches them.
     */
    private float[] getOrComputeDocNorms(float[] database, int numVectors, int dimensions) {
        NormCacheKey key = new NormCacheKey(System.identityHashCode(database), numVectors, dimensions);
        return normCache.computeIfAbsent(key, k -> computeAllDocNorms(database, numVectors, dimensions));
    }

    /**
     * Computes norms for all document vectors.
     */
    private float[] computeAllDocNorms(float[] database, int numVectors, int dimensions) {
        float[] norms = new float[numVectors];
        for (int i = 0; i < numVectors; i++) {
            int offset = i * dimensions;
            if (containsNanOrInfinity(database, offset, dimensions)) {
                norms[i] = Float.NaN;
            } else {
                norms[i] = computeNormSimd(database, offset, dimensions);
            }
        }
        return norms;
    }

    /**
     * Checks if all document norms are approximately 1.0 (pre-normalized).
     */
    private boolean arePreNormalized(float[] norms) {
        for (float norm : norms) {
            if (Float.isNaN(norm) || Math.abs(norm - 1.0f) >= EPSILON) {
                return false;
            }
        }
        return true;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Validation and utilities
    // ─────────────────────────────────────────────────────────────────────────────

    private void validateInputs(float[] query, float[] database, int numVectors, int dimensions) {
        if (dimensions < MIN_DIMENSIONS || dimensions > MAX_DIMENSIONS) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "dimensions", MIN_DIMENSIONS, MAX_DIMENSIONS, dimensions);
        }
        if (dimensions % 32 != 0) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "dimensions (must be multiple of 32)", dimensions);
        }
        if (numVectors < 0) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NEGATIVE, "numVectors", numVectors);
        }
        if (query == null || query.length < dimensions) {
            throw new SpectorValidationException(ErrorCode.VECTOR_LENGTH_MISMATCH, 0, dimensions);
        }
        if (numVectors > 0 && (database == null || database.length < (long) numVectors * dimensions)) {
            throw new SpectorValidationException(ErrorCode.DIMENSIONS_MISMATCH, "Database must have at least " + ((long) numVectors * dimensions) + " elements");
        }
    }

    /**
     * Checks if a vector slice contains NaN or infinity values.
     */
    private static boolean containsNanOrInfinity(float[] vector, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            if (Float.isNaN(vector[i]) || Float.isInfinite(vector[i])) {
                return true;
            }
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Norm cache key
    // ─────────────────────────────────────────────────────────────────────────────

    private record NormCacheKey(int arrayIdentityHash, int numVectors, int dimensions) {}
}