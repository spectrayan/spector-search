package com.spectrayan.spector.core;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * SIMD-accelerated asymmetric cosine similarity between a float32 query
 * and a quantized int8 document vector.
 *
 * <p>Dequantizes the document on-the-fly and computes cosine similarity
 * in a single pass: accumulates dot product, query norm², and doc norm²
 * simultaneously for maximum data locality.</p>
 *
 * <h3>Formula</h3>
 * <pre>
 *   cosine(query, dequant(doc)) = dot(q, d') / (‖q‖ × ‖d'‖)
 *   where d'[i] = byte[i] × scale[i] + min[i]
 * </pre>
 */
public final class QuantizedCosineSimilarity {

    private static final VectorSpecies<Float> SPECIES = SimdCapability.PREFERRED_SPECIES;

    private QuantizedCosineSimilarity() {}

    /**
     * Computes cosine similarity between a float32 query and a quantized int8 vector.
     *
     * @param query     the query vector (float32)
     * @param quantized the quantized document vector (unsigned int8)
     * @param mins      per-dimension minimum values from calibration
     * @param scales    per-dimension scale values from calibration
     * @param length    number of dimensions
     * @return approximate cosine similarity in [-1, 1]
     */
    public static float compute(float[] query, byte[] quantized,
                                 float[] mins, float[] scales, int length) {
        int laneCount = SPECIES.length();
        FloatVector sumDot = FloatVector.zero(SPECIES);
        FloatVector sumNormQ = FloatVector.zero(SPECIES);
        FloatVector sumNormD = FloatVector.zero(SPECIES);

        int i = 0;
        int limit = SPECIES.loopBound(length);

        // ── Main vectorized loop ──
        for (; i < limit; i += laneCount) {
            FloatVector vQuery = FloatVector.fromArray(SPECIES, query, i);

            // Dequantize bytes to float
            float[] dequantized = new float[laneCount];
            for (int j = 0; j < laneCount; j++) {
                int unsigned = Byte.toUnsignedInt(quantized[i + j]);
                dequantized[j] = unsigned * scales[i + j] + mins[i + j];
            }
            FloatVector vDoc = FloatVector.fromArray(SPECIES, dequantized, 0);

            sumDot = vQuery.fma(vDoc, sumDot);       // dot += q * d
            sumNormQ = vQuery.fma(vQuery, sumNormQ); // normQ += q * q
            sumNormD = vDoc.fma(vDoc, sumNormD);     // normD += d * d
        }

        // ── Scalar tail ──
        float tailDot = 0, tailNormQ = 0, tailNormD = 0;
        for (; i < length; i++) {
            int unsigned = Byte.toUnsignedInt(quantized[i]);
            float d = unsigned * scales[i] + mins[i];
            tailDot += query[i] * d;
            tailNormQ += query[i] * query[i];
            tailNormD += d * d;
        }

        float dot = sumDot.reduceLanes(VectorOperators.ADD) + tailDot;
        float normQ = sumNormQ.reduceLanes(VectorOperators.ADD) + tailNormQ;
        float normD = sumNormD.reduceLanes(VectorOperators.ADD) + tailNormD;

        float denom = (float) Math.sqrt((double) normQ * normD);
        return denom == 0.0f ? 0.0f : dot / denom;
    }
}
