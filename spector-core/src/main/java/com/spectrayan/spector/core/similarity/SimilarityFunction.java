package com.spectrayan.spector.core.similarity;
import com.spectrayan.spector.commons.error.SpectorException;

import com.spectrayan.spector.core.quantization.vasq.Vasq4QueryState;
import com.spectrayan.spector.core.quantization.vasq.Vasq4SimdKernel;
import com.spectrayan.spector.core.quantization.vasq.VasqQueryState;
import com.spectrayan.spector.core.quantization.vasq.VasqSimdKernel;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Enumerates the supported distance/similarity functions.
 *
 * <p>Each variant encapsulates the corresponding SIMD kernel and provides
 * a uniform {@link #compute(float[], float[])} interface for use by indexes
 * and query engines.</p>
 *
 * <h3>Zero-Copy Distance API</h3>
 * <p>All quantized distance methods have primary overloads that accept a
 * {@link MemorySegment} + offset, reading encoded vectors directly from off-heap
 * memory without any intermediate {@code byte[]} allocation:</p>
 * <ul>
 *   <li>{@link #computeQuantizedFromSegment} — INT8 scalar quantization, zero-copy</li>
 *   <li>{@link #computeVasq} — VASQ FWHT Panama kernel, zero-copy (always was)</li>
 * </ul>
 * <p>The legacy {@link #computeQuantized(float[], byte[], float[], float[], int)} overloads
 * are deprecated and delegate to the segment-based kernels via
 * {@link MemorySegment#ofArray} without data copying.</p>
 */
public enum SimilarityFunction {

    /**
     * Cosine similarity — measures the angle between two vectors.
     * Result range: [-1, 1]. Higher is more similar.
     */
    COSINE {
        @Override
        public float compute(float[] a, float[] b) {
            return CosineSimilarity.compute(a, b);
        }

        @Override
        public float compute(float[] a, int aOff, float[] b, int bOff, int len) {
            return CosineSimilarity.compute(a, aOff, b, bOff, len);
        }

        @Override
        public float computeForRanking(float[] a, float[] b) {
            return CosineSimilarity.compute(a, b);
        }

        @Override
        public float computeForRanking(float[] a, int aOff, float[] b, int bOff, int len) {
            return CosineSimilarity.compute(a, aOff, b, bOff, len);
        }

        @Override
        public float computeQuantizedFromSegment(float[] query, MemorySegment segment, long offset,
                                                  float[] mins, float[] scales, int length) {
            return QuantizedCosineSimilarity.compute(query, segment, offset, mins, scales, length);
        }

        @Override
        @Deprecated
        public float computeQuantized(float[] query, byte[] quantized,
                                       float[] mins, float[] scales, int length) {
            return QuantizedCosineSimilarity.compute(query, quantized, mins, scales, length);
        }

        @Override
        public boolean higherIsBetter() {
            return true;
        }
    },

    /**
     * Dot product — measures the projection of one vector onto another.
     * Unbounded range. Higher is more similar (for normalized vectors).
     */
    DOT_PRODUCT {
        @Override
        public float compute(float[] a, float[] b) {
            return DotProduct.compute(a, b);
        }

        @Override
        public float compute(float[] a, int aOff, float[] b, int bOff, int len) {
            return DotProduct.compute(a, aOff, b, bOff, len);
        }

        @Override
        public float computeForRanking(float[] a, float[] b) {
            return DotProduct.compute(a, b);
        }

        @Override
        public float computeForRanking(float[] a, int aOff, float[] b, int bOff, int len) {
            return DotProduct.compute(a, aOff, b, bOff, len);
        }

        @Override
        public float computeQuantizedFromSegment(float[] query, MemorySegment segment, long offset,
                                                  float[] mins, float[] scales, int length) {
            return QuantizedDotProduct.compute(query, segment, offset, mins, scales, length);
        }

        @Override
        @Deprecated
        public float computeQuantized(float[] query, byte[] quantized,
                                       float[] mins, float[] scales, int length) {
            return QuantizedDotProduct.compute(query, quantized, mins, scales, length);
        }

        @Override
        public boolean higherIsBetter() {
            return true;
        }
    },

    /**
     * Euclidean (L2) distance — measures straight-line distance.
     * Range: [0, ∞). Lower is more similar.
     */
    EUCLIDEAN {
        @Override
        public float compute(float[] a, float[] b) {
            return EuclideanDistance.compute(a, b);
        }

        @Override
        public float compute(float[] a, int aOff, float[] b, int bOff, int len) {
            return EuclideanDistance.compute(a, aOff, b, bOff, len);
        }

        @Override
        public float computeForRanking(float[] a, float[] b) {
            return EuclideanDistance.computeSquared(a, b);
        }

        @Override
        public float computeForRanking(float[] a, int aOff, float[] b, int bOff, int len) {
            return EuclideanDistance.computeSquared(a, aOff, b, bOff, len);
        }

        @Override
        public float computeQuantizedFromSegment(float[] query, MemorySegment segment, long offset,
                                                  float[] mins, float[] scales, int length) {
            // SIMD-accelerated: processes laneCount dimensions per iteration via FloatVector FMA
            return QuantizedEuclideanDistance.compute(query, segment, offset, mins, scales, length);
        }

        @Override
        @Deprecated
        public float computeQuantized(float[] query, byte[] quantized,
                                       float[] mins, float[] scales, int length) {
            return computeQuantizedFromSegment(
                    query, MemorySegment.ofArray(quantized), 0L, mins, scales, length);
        }

        @Override
        public boolean higherIsBetter() {
            return false;
        }
    };

    /**
     * Computes the similarity/distance between two float32 vectors.
     *
     * @param a first vector
     * @param b second vector
     * @return the similarity or distance score
     */
    public abstract float compute(float[] a, float[] b);

    /**
     * Computes the similarity/distance between two vector slices.
     *
     * @param a    first vector array
     * @param aOff offset into a
     * @param b    second vector array
     * @param bOff offset into b
     * @param len  number of elements
     * @return the similarity or distance score
     */
    public abstract float compute(float[] a, int aOff, float[] b, int bOff, int len);

    /**
     * Computes a score suitable for <em>ranking only</em> (relative ordering).
     *
     * <p>For COSINE and DOT_PRODUCT, this is identical to {@link #compute(float[], float[])}.
     * For EUCLIDEAN, this returns the <em>squared</em> L2 distance (no {@code sqrt}),
     * which preserves rank ordering while saving ~20 CPU cycles per call.
     * <strong>Do not expose the result to users as a distance value</strong> — it
     * is only valid for comparisons.</p>
     *
     * @param a first vector
     * @param b second vector
     * @return a rank-preserving score (not necessarily the true distance/similarity)
     */
    public abstract float computeForRanking(float[] a, float[] b);

    /**
     * Rank-preserving computation on vector slices.
     *
     * @see #computeForRanking(float[], float[])
     */
    public abstract float computeForRanking(float[] a, int aOff, float[] b, int bOff, int len);

    /**
     * Computes asymmetric similarity/distance between a float32 query and a quantized INT8
     * document vector stored in an off-heap {@link MemorySegment}.
     *
     * <p><b>Zero-copy hot path:</b> reads directly from the off-heap segment — no {@code byte[]}
     * intermediate, no GC pressure. This is the primary API for INT8 HNSW graph traversal.</p>
     *
     * @param query   query vector in float32
     * @param segment off-heap segment containing the encoded document database
     * @param offset  byte offset of the target vector's first INT8 code within the segment
     * @param mins    per-dimension minimum values from calibration
     * @param scales  per-dimension scale values from calibration
     * @param length  number of dimensions
     * @return the similarity or distance score
     */
    public abstract float computeQuantizedFromSegment(float[] query, MemorySegment segment,
                                                       long offset, float[] mins, float[] scales,
                                                       int length);

    /**
     * Computes asymmetric similarity/distance between a float32 query and a quantized INT8
     * document vector stored in a heap {@code byte[]} array.
     *
     * @deprecated Use {@link #computeQuantizedFromSegment} for zero-copy off-heap access.
     *             This overload delegates via {@link MemorySegment#ofArray} without data copying.
     *
     * @param query     query vector in float32
     * @param quantized document vector in int8 (unsigned byte)
     * @param mins      per-dimension minimums from calibration
     * @param scales    per-dimension scales from calibration
     * @param length    number of dimensions
     * @return the similarity or distance score
     */
    @Deprecated
    public abstract float computeQuantized(float[] query, byte[] quantized,
                                            float[] mins, float[] scales, int length);

    /**
     * Computes VASQ-quantized distance using a pre-prepared query context and an
     * off-heap {@link MemorySegment} storing the encoded vector database.
     *
     * <p><b>Zero-copy:</b> reads directly from off-heap memory, zero JVM GC allocations.
     * This is the primary hot path for VASQ HNSW graph traversal via the Panama SIMD kernel.</p>
     *
     * <ul>
     *   <li>{@code EUCLIDEAN}: approximate squared L2 distance (lower = more similar)</li>
     *   <li>{@code DOT_PRODUCT}: approximate inner product (higher = more similar)</li>
     *   <li>{@code COSINE}: approximate inner product in rotated space (higher = more similar;
     *       equals cosine similarity for unit-normalized vectors)</li>
     * </ul>
     *
     * @param segment   off-heap memory segment containing the encoded vector database
     * @param offset    byte offset of the target vector's 4-byte norm header
     * @param paddedDim FWHT-padded dimension (power-of-two)
     * @param qs        pre-prepared query state (from {@link com.spectrayan.spector.core.quantization.vasq.VasqQueryPrep})
     * @return distance or similarity score appropriate for this function
     */
    public float computeVasq(MemorySegment segment, long offset,
                              int paddedDim, VasqQueryState qs) {
        return switch (this) {
            case EUCLIDEAN   -> VasqSimdKernel.computeL2(segment, offset, paddedDim, qs);
            case DOT_PRODUCT -> VasqSimdKernel.computeDot(segment, offset, paddedDim, qs);
            // For cosine, inner product in FWHT-rotated space. Equals cosine for unit vectors.
            case COSINE      -> VasqSimdKernel.computeDot(segment, offset, paddedDim, qs);
        };
    }

    /**
     * Computes VASQ-4 quantized distance using a pre-prepared VASQ-4 query context and
     * an off-heap {@link MemorySegment} storing nibble-packed INT4 encoded vectors.
     *
     * <p><b>Zero-copy:</b> reads directly from off-heap memory with zero JVM GC allocations.
     * This is the hot path for VASQ-4 HNSW graph traversal.</p>
     *
     * @param segment  off-heap memory segment containing the encoded vector database
     * @param offset   byte offset of the target vector's 4-byte norm header
     * @param halfDim  half of paddedDim (number of nibble-packed code bytes to process)
     * @param qs       pre-prepared VASQ-4 query state (from {@link com.spectrayan.spector.core.quantization.vasq.Vasq4QueryPrep})
     * @return distance or similarity score appropriate for this function
     */
    public float computeVasq4(MemorySegment segment, long offset,
                               int halfDim, Vasq4QueryState qs) {
        return switch (this) {
            case EUCLIDEAN   -> Vasq4SimdKernel.computeL2(segment, offset, halfDim, qs);
            case DOT_PRODUCT -> Vasq4SimdKernel.computeDot(segment, offset, halfDim, qs);
            case COSINE      -> Vasq4SimdKernel.computeDot(segment, offset, halfDim, qs);
        };
    }

    /**
     * Whether higher scores indicate greater similarity.
     *
     * @return true for similarity metrics (cosine, dot), false for distance metrics (euclidean)
     */
    public abstract boolean higherIsBetter();
}