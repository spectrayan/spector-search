/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.spectrayan.spector.core.similarity;
import com.spectrayan.spector.commons.error.SpectorException;
import com.spectrayan.spector.core.simd.SimdCapability;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * SIMD-accelerated cosine similarity computation.
 *
 * <p>Computes cosine similarity in a single pass over the data by accumulating
 * the dot product and both norms simultaneously, minimizing cache misses.
 * Uses {@link FloatVector} with masked tail handling for branchless execution.</p>
 *
 * <h3>Mathematical Definition</h3>
 * <pre>
 *   cosine(a, b) = dot(a, b) / (‖a‖ * ‖b‖)
 * </pre>
 *
 * <p>Returns {@code 0.0f} if either vector has zero magnitude (degenerate case).</p>
 */
public final class CosineSimilarity {

    private static final VectorSpecies<Float> SPECIES = SimdCapability.PREFERRED_SPECIES;

    private CosineSimilarity() {
        // utility class
    }

    /**
     * Computes cosine similarity between two float arrays.
     *
     * @param a first vector
     * @param b second vector
     * @return cosine similarity in range [-1, 1], or 0 if degenerate
     * @throws SpectorValidationException if arrays have different lengths
     */
    public static float compute(float[] a, float[] b) {
        return compute(a, 0, b, 0, a.length);
    }

    /**
     * Computes cosine similarity between two float array slices in a single pass.
     *
     * <p>Accumulates dot-product, norm-a², and norm-b² simultaneously to maximize
     * data locality and minimize memory bandwidth pressure.</p>
     *
     * @param a       first vector array
     * @param aOffset offset into {@code a}
     * @param b       second vector array
     * @param bOffset offset into {@code b}
     * @param length  number of elements to process
     * @return cosine similarity in range [-1, 1], or 0 if degenerate
     */
    public static float compute(float[] a, int aOffset, float[] b, int bOffset, int length) {
        validateInputs(a, aOffset, b, bOffset, length);

        int laneCount = SPECIES.length();
        FloatVector sumDot = FloatVector.zero(SPECIES);
        FloatVector sumNormA = FloatVector.zero(SPECIES);
        FloatVector sumNormB = FloatVector.zero(SPECIES);

        // ── Main vectorized loop ──
        int i = 0;
        int limit = SPECIES.loopBound(length);
        for (; i < limit; i += laneCount) {
            FloatVector va = FloatVector.fromArray(SPECIES, a, aOffset + i);
            FloatVector vb = FloatVector.fromArray(SPECIES, b, bOffset + i);

            sumDot   = va.fma(vb, sumDot);     // dot += a * b
            sumNormA = va.fma(va, sumNormA);   // normA += a * a
            sumNormB = vb.fma(vb, sumNormB);   // normB += b * b
        }

        // ── Tail: masked operations ──
        if (i < length) {
            VectorMask<Float> mask = SPECIES.indexInRange(i, length);
            FloatVector va = FloatVector.fromArray(SPECIES, a, aOffset + i, mask);
            FloatVector vb = FloatVector.fromArray(SPECIES, b, bOffset + i, mask);

            sumDot   = sumDot.add(va.mul(vb, mask));
            sumNormA = sumNormA.add(va.mul(va, mask));
            sumNormB = sumNormB.add(vb.mul(vb, mask));
        }

        float dot   = sumDot.reduceLanes(VectorOperators.ADD);
        float normA = sumNormA.reduceLanes(VectorOperators.ADD);
        float normB = sumNormB.reduceLanes(VectorOperators.ADD);

        float denom = (float) Math.sqrt((double) normA * normB);
        return denom == 0.0f ? 0.0f : dot / denom;
    }

    private static void validateInputs(float[] a, int aOffset, float[] b, int bOffset, int length) {
        if (length < 0) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NEGATIVE, "length", length);
        }
        if (aOffset < 0 || aOffset + length > a.length) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, String.format("a: offset=%d, length=%d, array.length=%d", aOffset, length, a.length));
        }
        if (bOffset < 0 || bOffset + length > b.length) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, String.format("b: offset=%d, length=%d, array.length=%d", bOffset, length, b.length));
        }
    }
}