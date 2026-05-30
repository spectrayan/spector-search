package com.spectrayan.spector.core.similarity;
import com.spectrayan.spector.commons.error.SpectorException;
import com.spectrayan.spector.core.simd.SimdCapability;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorSpecies;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * SIMD-accelerated dot product computation.
 *
 * <p>Uses {@link FloatVector} with {@code SPECIES_PREFERRED} to auto-detect
 * the optimal SIMD width (AVX2/AVX-512/NEON/SVE). Tail elements that don't
 * fill a complete SIMD register are handled via {@link VectorMask} to keep
 * the hot path completely branchless.</p>
 *
 * <h3>Mathematical Definition</h3>
 * <pre>
 *   dot(a, b) = Σ a[i] * b[i]   for i ∈ [0, length)
 * </pre>
 */
public final class DotProduct {

    private static final VectorSpecies<Float> SPECIES = SimdCapability.PREFERRED_SPECIES;

    private DotProduct() {
        // utility class
    }

    /**
     * Computes the dot product of two float arrays.
     *
     * @param a first vector
     * @param b second vector
     * @return dot product value
     * @throws SpectorValidationException if arrays have different lengths
     */
    public static float compute(float[] a, float[] b) {
        return compute(a, 0, b, 0, a.length);
    }

    /**
     * Computes the dot product of two float array slices.
     *
     * <p>This is the core SIMD kernel. It processes full SIMD-width chunks
     * in the main loop and uses a masked load for the remaining tail
     * elements, avoiding any scalar fallback branch.</p>
     *
     * @param a      first vector array
     * @param aOffset offset into {@code a}
     * @param b      second vector array
     * @param bOffset offset into {@code b}
     * @param length number of elements to process
     * @return dot product value
     * @throws SpectorValidationException if length is negative or offsets are out of bounds
     */
    public static float compute(float[] a, int aOffset, float[] b, int bOffset, int length) {
        validateInputs(a, aOffset, b, bOffset, length);

        int laneCount = SPECIES.length();
        FloatVector sum = FloatVector.zero(SPECIES);

        // ── Main vectorized loop: full SIMD-width chunks ──
        int i = 0;
        int limit = SPECIES.loopBound(length);
        for (; i < limit; i += laneCount) {
            FloatVector va = FloatVector.fromArray(SPECIES, a, aOffset + i);
            FloatVector vb = FloatVector.fromArray(SPECIES, b, bOffset + i);
            sum = va.fma(vb, sum);  // fused multiply-add: sum += va * vb
        }

        // ── Tail: masked load for remaining elements ──
        if (i < length) {
            VectorMask<Float> mask = SPECIES.indexInRange(i, length);
            FloatVector va = FloatVector.fromArray(SPECIES, a, aOffset + i, mask);
            FloatVector vb = FloatVector.fromArray(SPECIES, b, bOffset + i, mask);
            sum = sum.add(va.mul(vb, mask));
        }

        return sum.reduceLanes(jdk.incubator.vector.VectorOperators.ADD);
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