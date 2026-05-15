package com.spectrayan.spector.core;

/**
 * Enumerates the supported distance/similarity functions.
 *
 * <p>Each variant encapsulates the corresponding SIMD kernel and provides
 * a uniform {@link #compute(float[], float[])} interface for use by indexes
 * and query engines.</p>
 *
 * <p>Also supports asymmetric quantized computation via
 * {@link #computeQuantized(float[], byte[], float[], float[], int)} for
 * float32 query × int8 document distance.</p>
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
        public float computeQuantized(float[] query, byte[] quantized,
                                       float[] mins, float[] scales, int length) {
            // Dequantize and compute — no specialized Euclidean quantized kernel yet
            float sum = 0;
            for (int i = 0; i < length; i++) {
                float d = Byte.toUnsignedInt(quantized[i]) * scales[i] + mins[i];
                float diff = query[i] - d;
                sum += diff * diff;
            }
            return (float) Math.sqrt(sum);
        }

        @Override
        public boolean higherIsBetter() {
            return false;
        }
    };

    /**
     * Computes the similarity/distance between two vectors.
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
     * Computes asymmetric similarity/distance between a float32 query
     * and a quantized int8 document vector.
     *
     * @param query     query vector in float32
     * @param quantized document vector in int8 (unsigned byte)
     * @param mins      per-dimension minimums from calibration
     * @param scales    per-dimension scales from calibration
     * @param length    number of dimensions
     * @return the similarity or distance score
     */
    public abstract float computeQuantized(float[] query, byte[] quantized,
                                            float[] mins, float[] scales, int length);

    /**
     * Whether higher scores indicate greater similarity.
     *
     * @return true for similarity metrics (cosine, dot), false for distance metrics (euclidean)
     */
    public abstract boolean higherIsBetter();
}

