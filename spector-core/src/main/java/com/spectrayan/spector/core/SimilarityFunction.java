package com.spectrayan.spector.core;

/**
 * Enumerates the supported distance/similarity functions.
 *
 * <p>Each variant encapsulates the corresponding SIMD kernel and provides
 * a uniform {@link #compute(float[], float[])} interface for use by indexes
 * and query engines.</p>
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
     * Whether higher scores indicate greater similarity.
     *
     * @return true for similarity metrics (cosine, dot), false for distance metrics (euclidean)
     */
    public abstract boolean higherIsBetter();
}
