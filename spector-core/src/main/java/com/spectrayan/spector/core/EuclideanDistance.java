package com.spectrayan.spector.core;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * SIMD-accelerated Euclidean (L2) distance computation.
 *
 * <p>Computes both the squared distance and the full distance. For nearest-neighbor
 * search, {@link #computeSquared} is preferred since it avoids the costly
 * {@code sqrt} operation while preserving rank ordering.</p>
 *
 * <h3>Mathematical Definition</h3>
 * <pre>
 *   L2²(a, b) = Σ (a[i] - b[i])²   for i ∈ [0, length)
 *   L2(a, b)  = √L2²(a, b)
 * </pre>
 */
public final class EuclideanDistance {

    private static final VectorSpecies<Float> SPECIES = SimdCapability.PREFERRED_SPECIES;

    private EuclideanDistance() {
        // utility class
    }

    /**
     * Computes the Euclidean distance between two float arrays.
     *
     * @param a first vector
     * @param b second vector
     * @return Euclidean distance (L2 norm of the difference)
     */
    public static float compute(float[] a, float[] b) {
        return (float) Math.sqrt(computeSquared(a, 0, b, 0, a.length));
    }

    /**
     * Computes the Euclidean distance between two float array slices.
     *
     * @param a       first vector array
     * @param aOffset offset into {@code a}
     * @param b       second vector array
     * @param bOffset offset into {@code b}
     * @param length  number of elements to process
     * @return Euclidean distance
     */
    public static float compute(float[] a, int aOffset, float[] b, int bOffset, int length) {
        return (float) Math.sqrt(computeSquared(a, aOffset, b, bOffset, length));
    }

    /**
     * Computes the <em>squared</em> Euclidean distance between two float arrays.
     *
     * <p>Preferred for nearest-neighbor search since it avoids the square root
     * while preserving the same rank ordering as the full distance.</p>
     *
     * @param a first vector
     * @param b second vector
     * @return squared Euclidean distance
     */
    public static float computeSquared(float[] a, float[] b) {
        return computeSquared(a, 0, b, 0, a.length);
    }

    /**
     * Computes the squared Euclidean distance between two float array slices.
     *
     * @param a       first vector array
     * @param aOffset offset into {@code a}
     * @param b       second vector array
     * @param bOffset offset into {@code b}
     * @param length  number of elements to process
     * @return squared Euclidean distance
     */
    public static float computeSquared(float[] a, int aOffset, float[] b, int bOffset, int length) {
        validateInputs(a, aOffset, b, bOffset, length);

        int laneCount = SPECIES.length();
        FloatVector sum = FloatVector.zero(SPECIES);

        // ── Main vectorized loop ──
        int i = 0;
        int limit = SPECIES.loopBound(length);
        for (; i < limit; i += laneCount) {
            FloatVector va = FloatVector.fromArray(SPECIES, a, aOffset + i);
            FloatVector vb = FloatVector.fromArray(SPECIES, b, bOffset + i);
            FloatVector diff = va.sub(vb);
            sum = diff.fma(diff, sum);  // sum += diff * diff
        }

        // ── Tail: masked operations ──
        if (i < length) {
            VectorMask<Float> mask = SPECIES.indexInRange(i, length);
            FloatVector va = FloatVector.fromArray(SPECIES, a, aOffset + i, mask);
            FloatVector vb = FloatVector.fromArray(SPECIES, b, bOffset + i, mask);
            FloatVector diff = va.sub(vb, mask);
            sum = sum.add(diff.mul(diff, mask));
        }

        return sum.reduceLanes(VectorOperators.ADD);
    }

    private static void validateInputs(float[] a, int aOffset, float[] b, int bOffset, int length) {
        if (length < 0) {
            throw new IllegalArgumentException("length must be non-negative: " + length);
        }
        if (aOffset < 0 || aOffset + length > a.length) {
            throw new IllegalArgumentException(
                    String.format("a: offset=%d, length=%d, array.length=%d", aOffset, length, a.length));
        }
        if (bOffset < 0 || bOffset + length > b.length) {
            throw new IllegalArgumentException(
                    String.format("b: offset=%d, length=%d, array.length=%d", bOffset, length, b.length));
        }
    }
}
