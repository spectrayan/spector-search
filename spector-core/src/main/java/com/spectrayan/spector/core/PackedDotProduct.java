package com.spectrayan.spector.core;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorSpecies;

/**
 * SIMD-accelerated dot product computation on nibble-packed (INT4) and crumb-packed (INT2)
 * quantized vectors.
 *
 * <p>Computes {@code sum(query[i] * centroids[level[i]])} for all dimensions, where
 * {@code level[i]} is extracted from the packed byte array. The centroid lookup converts
 * quantized level indices back to representative float values for the distance computation.</p>
 *
 * <p>Auto-detects Java Vector API availability at class-load time. If the Vector API is
 * not available, the public {@code computeInt4} and {@code computeInt2} methods fall back
 * to the scalar implementations transparently.</p>
 *
 * <h3>INT4 (Nibble Packing)</h3>
 * <pre>
 *   Each byte: [dim_i (bits 7-4)] [dim_i+1 (bits 3-0)]
 *   Centroids array: 16 entries (one per quantization level)
 * </pre>
 *
 * <h3>INT2 (Crumb Packing)</h3>
 * <pre>
 *   Each byte: [dim_i (bits 7-6)] [dim_i+1 (bits 5-4)] [dim_i+2 (bits 3-2)] [dim_i+3 (bits 1-0)]
 *   Centroids array: 4 entries (one per quantization level)
 * </pre>
 */
public final class PackedDotProduct {

    private static final boolean SIMD_AVAILABLE;
    private static final VectorSpecies<Float> SPECIES;

    static {
        boolean available;
        VectorSpecies<Float> species = null;
        try {
            species = SimdCapability.PREFERRED_SPECIES;
            // Force class initialization to confirm Vector API is usable
            FloatVector.zero(species);
            available = true;
        } catch (Throwable t) {
            available = false;
        }
        SIMD_AVAILABLE = available;
        SPECIES = species;
    }

    private PackedDotProduct() {
        // utility class
    }

    /**
     * Computes dot product between a float32 query and a nibble-packed INT4 document vector.
     *
     * <p>Automatically selects SIMD or scalar implementation based on runtime capability.</p>
     *
     * @param query      the query vector (float32), length must be >= dimensions
     * @param packedDoc  nibble-packed document vector (2 values per byte)
     * @param centroids4 centroid values for each of the 16 quantization levels
     * @param dimensions number of dimensions in the original vector
     * @return dot product value
     */
    public static float computeInt4(float[] query, byte[] packedDoc,
                                     float[] centroids4, int dimensions) {
        if (SIMD_AVAILABLE) {
            return computeInt4Simd(query, packedDoc, centroids4, dimensions);
        }
        return computeInt4Scalar(query, packedDoc, centroids4, dimensions);
    }

    /**
     * Computes dot product between a float32 query and a crumb-packed INT2 document vector.
     *
     * <p>Automatically selects SIMD or scalar implementation based on runtime capability.</p>
     *
     * @param query      the query vector (float32), length must be >= dimensions
     * @param packedDoc  crumb-packed document vector (4 values per byte)
     * @param centroids2 centroid values for each of the 4 quantization levels
     * @param dimensions number of dimensions in the original vector
     * @return dot product value
     */
    public static float computeInt2(float[] query, byte[] packedDoc,
                                     float[] centroids2, int dimensions) {
        if (SIMD_AVAILABLE) {
            return computeInt2Simd(query, packedDoc, centroids2, dimensions);
        }
        return computeInt2Scalar(query, packedDoc, centroids2, dimensions);
    }

    /**
     * Scalar fallback for INT4 dot product. Produces identical results to the SIMD path.
     *
     * @param query      the query vector (float32)
     * @param packedDoc  nibble-packed document vector
     * @param centroids4 centroid values for 16 levels
     * @param dimensions number of dimensions
     * @return dot product value
     */
    public static float computeInt4Scalar(float[] query, byte[] packedDoc,
                                           float[] centroids4, int dimensions) {
        float sum = 0.0f;
        for (int i = 0; i < dimensions; i++) {
            int byteIndex = i / 2;
            int level;
            if (i % 2 == 0) {
                level = (packedDoc[byteIndex] >> 4) & 0x0F;
            } else {
                level = packedDoc[byteIndex] & 0x0F;
            }
            sum += query[i] * centroids4[level];
        }
        return sum;
    }

    /**
     * Scalar fallback for INT2 dot product. Produces identical results to the SIMD path.
     *
     * @param query      the query vector (float32)
     * @param packedDoc  crumb-packed document vector
     * @param centroids2 centroid values for 4 levels
     * @param dimensions number of dimensions
     * @return dot product value
     */
    public static float computeInt2Scalar(float[] query, byte[] packedDoc,
                                           float[] centroids2, int dimensions) {
        float sum = 0.0f;
        for (int i = 0; i < dimensions; i++) {
            int byteIndex = i / 4;
            int positionInByte = i % 4;
            int shift = 6 - (positionInByte * 2);
            int level = (packedDoc[byteIndex] >> shift) & 0x03;
            sum += query[i] * centroids2[level];
        }
        return sum;
    }

    // ── SIMD implementations ──

    private static float computeInt4Simd(float[] query, byte[] packedDoc,
                                          float[] centroids4, int dimensions) {
        int laneCount = SPECIES.length();

        // Accumulate products into a temporary array, then sum sequentially
        // to ensure bitwise-identical results to the scalar fallback.
        float[] products = new float[dimensions];

        int i = 0;
        int limit = SPECIES.loopBound(dimensions);

        // Main vectorized loop: compute products in SIMD-width chunks
        for (; i < limit; i += laneCount) {
            float[] docValues = new float[laneCount];
            for (int j = 0; j < laneCount; j++) {
                int dim = i + j;
                int byteIndex = dim / 2;
                int level;
                if (dim % 2 == 0) {
                    level = (packedDoc[byteIndex] >> 4) & 0x0F;
                } else {
                    level = packedDoc[byteIndex] & 0x0F;
                }
                docValues[j] = centroids4[level];
            }

            FloatVector vQuery = FloatVector.fromArray(SPECIES, query, i);
            FloatVector vDoc = FloatVector.fromArray(SPECIES, docValues, 0);
            FloatVector vProduct = vQuery.mul(vDoc);
            vProduct.intoArray(products, i);
        }

        // Scalar tail for remaining dimensions
        for (; i < dimensions; i++) {
            int byteIndex = i / 2;
            int level;
            if (i % 2 == 0) {
                level = (packedDoc[byteIndex] >> 4) & 0x0F;
            } else {
                level = packedDoc[byteIndex] & 0x0F;
            }
            products[i] = query[i] * centroids4[level];
        }

        // Sequential summation — same order as scalar path
        float sum = 0.0f;
        for (int k = 0; k < dimensions; k++) {
            sum += products[k];
        }
        return sum;
    }

    private static float computeInt2Simd(float[] query, byte[] packedDoc,
                                          float[] centroids2, int dimensions) {
        int laneCount = SPECIES.length();

        // Accumulate products into a temporary array, then sum sequentially
        // to ensure bitwise-identical results to the scalar fallback.
        float[] products = new float[dimensions];

        int i = 0;
        int limit = SPECIES.loopBound(dimensions);

        // Main vectorized loop: compute products in SIMD-width chunks
        for (; i < limit; i += laneCount) {
            float[] docValues = new float[laneCount];
            for (int j = 0; j < laneCount; j++) {
                int dim = i + j;
                int byteIndex = dim / 4;
                int positionInByte = dim % 4;
                int shift = 6 - (positionInByte * 2);
                int level = (packedDoc[byteIndex] >> shift) & 0x03;
                docValues[j] = centroids2[level];
            }

            FloatVector vQuery = FloatVector.fromArray(SPECIES, query, i);
            FloatVector vDoc = FloatVector.fromArray(SPECIES, docValues, 0);
            FloatVector vProduct = vQuery.mul(vDoc);
            vProduct.intoArray(products, i);
        }

        // Scalar tail for remaining dimensions
        for (; i < dimensions; i++) {
            int byteIndex = i / 4;
            int positionInByte = i % 4;
            int shift = 6 - (positionInByte * 2);
            int level = (packedDoc[byteIndex] >> shift) & 0x03;
            products[i] = query[i] * centroids2[level];
        }

        // Sequential summation — same order as scalar path
        float sum = 0.0f;
        for (int k = 0; k < dimensions; k++) {
            sum += products[k];
        }
        return sum;
    }

    /**
     * Returns whether SIMD acceleration is available for packed dot product computation.
     *
     * @return true if Java Vector API is available and usable
     */
    public static boolean isSimdAvailable() {
        return SIMD_AVAILABLE;
    }
}
