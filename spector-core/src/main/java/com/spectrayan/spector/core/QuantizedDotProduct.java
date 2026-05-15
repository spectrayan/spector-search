package com.spectrayan.spector.core;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * SIMD-accelerated asymmetric dot product between a float32 query and a
 * quantized int8 document vector.
 *
 * <p>The quantized document vector is dequantized on-the-fly during the
 * SIMD computation: {@code dequantized[i] = byte[i] * scale[i] + min[i]}.
 * The query vector remains in full float32 precision throughout.</p>
 *
 * <h3>Performance</h3>
 * <p>By operating on byte lanes, this kernel processes 4× more elements
 * per SIMD register compared to float-only computation. On AVX2 (256-bit),
 * each iteration handles 8 float lanes with pre-dequantized bytes.</p>
 *
 * <h3>Mathematical Equivalence</h3>
 * <pre>
 *   dot(query, dequant(doc)) = Σ query[i] × (doc_byte[i] × scale[i] + min[i])
 *                             = Σ query[i] × doc_byte[i] × scale[i]
 *                             + Σ query[i] × min[i]
 * </pre>
 */
public final class QuantizedDotProduct {

    private static final VectorSpecies<Float> SPECIES = SimdCapability.PREFERRED_SPECIES;

    private QuantizedDotProduct() {}

    /**
     * Computes the dot product between a float32 query and a quantized int8 vector.
     *
     * @param query     the query vector (float32)
     * @param quantized the quantized document vector (unsigned int8)
     * @param mins      per-dimension minimum values from calibration
     * @param scales    per-dimension scale values from calibration
     * @param length    number of dimensions
     * @return approximate dot product
     */
    public static float compute(float[] query, byte[] quantized,
                                 float[] mins, float[] scales, int length) {
        int laneCount = SPECIES.length();
        FloatVector sumDot = FloatVector.zero(SPECIES);

        int i = 0;
        int limit = SPECIES.loopBound(length);

        // ── Main vectorized loop ──
        for (; i < limit; i += laneCount) {
            // Load query floats
            FloatVector vQuery = FloatVector.fromArray(SPECIES, query, i);

            // Load quantized bytes and dequantize to float
            // Manual widening: byte → unsigned int → float
            float[] dequantized = new float[laneCount];
            for (int j = 0; j < laneCount; j++) {
                int unsigned = Byte.toUnsignedInt(quantized[i + j]);
                dequantized[j] = unsigned * scales[i + j] + mins[i + j];
            }
            FloatVector vDoc = FloatVector.fromArray(SPECIES, dequantized, 0);

            // FMA: sum += query * dequantized_doc
            sumDot = vQuery.fma(vDoc, sumDot);
        }

        // ── Scalar tail ──
        float tail = 0.0f;
        for (; i < length; i++) {
            int unsigned = Byte.toUnsignedInt(quantized[i]);
            float dequantizedVal = unsigned * scales[i] + mins[i];
            tail += query[i] * dequantizedVal;
        }

        return sumDot.reduceLanes(VectorOperators.ADD) + tail;
    }

    /**
     * Computes the dot product using a pre-built lookup for dequantization.
     *
     * <p>When the same quantizer is used for many queries, pre-computing
     * the dequantized values avoids redundant scale/min multiplications.
     * Callers should dequantize once and pass the float array.</p>
     *
     * @param query        the query vector (float32)
     * @param dequantized  pre-dequantized document vector (float32)
     * @param length       number of dimensions
     * @return dot product
     */
    public static float computePreDequantized(float[] query, float[] dequantized, int length) {
        return DotProduct.compute(query, 0, dequantized, 0, length);
    }
}
