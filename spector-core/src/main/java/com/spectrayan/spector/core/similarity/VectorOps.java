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
 * SIMD-accelerated vector utility operations.
 *
 * <p>Provides common vector algebra operations (normalize, add, scale, magnitude)
 * all implemented with branchless SIMD kernels. These are the building blocks
 * used by the higher-level similarity functions and index structures.</p>
 */
public final class VectorOps {

    private static final VectorSpecies<Float> SPECIES = SimdCapability.PREFERRED_SPECIES;

    private VectorOps() {
        // utility class
    }

    // ─────────────────────── Magnitude ───────────────────────

    /**
     * Computes the L2 magnitude (Euclidean norm) of a vector.
     *
     * @param v the vector
     * @return ‖v‖₂
     */
    public static float magnitude(float[] v) {
        return (float) Math.sqrt(magnitudeSquared(v, 0, v.length));
    }

    /**
     * Computes the squared L2 magnitude of a vector slice.
     *
     * @param v      the vector array
     * @param offset offset into {@code v}
     * @param length number of elements
     * @return ‖v‖₂²
     */
    public static float magnitudeSquared(float[] v, int offset, int length) {
        validateSlice(v, offset, length);

        int laneCount = SPECIES.length();
        FloatVector sum = FloatVector.zero(SPECIES);

        int i = 0;
        int limit = SPECIES.loopBound(length);
        for (; i < limit; i += laneCount) {
            FloatVector vv = FloatVector.fromArray(SPECIES, v, offset + i);
            sum = vv.fma(vv, sum);
        }

        if (i < length) {
            VectorMask<Float> mask = SPECIES.indexInRange(i, length);
            FloatVector vv = FloatVector.fromArray(SPECIES, v, offset + i, mask);
            sum = sum.add(vv.mul(vv, mask));
        }

        return sum.reduceLanes(VectorOperators.ADD);
    }

    // ─────────────────────── Normalize ───────────────────────

    /**
     * Normalizes a vector to unit length (L2 normalization) and returns a new array.
     *
     * <p>If the vector has zero magnitude, returns a zero-filled array.</p>
     *
     * @param v the vector to normalize
     * @return a new array containing the unit vector
     */
    public static float[] normalize(float[] v) {
        float[] result = new float[v.length];
        normalize(v, 0, result, 0, v.length);
        return result;
    }

    /**
     * Normalizes a vector slice and writes the result to a destination slice.
     *
     * @param src       source array
     * @param srcOffset offset into source
     * @param dst       destination array
     * @param dstOffset offset into destination
     * @param length    number of elements
     */
    public static void normalize(float[] src, int srcOffset, float[] dst, int dstOffset, int length) {
        validateSlice(src, srcOffset, length);
        validateSlice(dst, dstOffset, length);

        float mag = (float) Math.sqrt(magnitudeSquared(src, srcOffset, length));
        if (mag == 0.0f) {
            System.arraycopy(new float[length], 0, dst, dstOffset, length);
            return;
        }

        float invMag = 1.0f / mag;
        scale(src, srcOffset, dst, dstOffset, length, invMag);
    }

    // ─────────────────────── Scale ───────────────────────

    /**
     * Scales a vector by a scalar factor and returns a new array.
     *
     * @param v      the vector
     * @param scalar the scaling factor
     * @return a new array containing the scaled vector
     */
    public static float[] scale(float[] v, float scalar) {
        float[] result = new float[v.length];
        scale(v, 0, result, 0, v.length, scalar);
        return result;
    }

    /**
     * Scales a vector slice by a scalar and writes to a destination slice.
     *
     * @param src       source array
     * @param srcOffset offset into source
     * @param dst       destination array
     * @param dstOffset offset into destination
     * @param length    number of elements
     * @param scalar    the scaling factor
     */
    public static void scale(float[] src, int srcOffset, float[] dst, int dstOffset, int length, float scalar) {
        validateSlice(src, srcOffset, length);
        validateSlice(dst, dstOffset, length);

        int laneCount = SPECIES.length();
        FloatVector vScalar = FloatVector.broadcast(SPECIES, scalar);

        int i = 0;
        int limit = SPECIES.loopBound(length);
        for (; i < limit; i += laneCount) {
            FloatVector vv = FloatVector.fromArray(SPECIES, src, srcOffset + i);
            vv.mul(vScalar).intoArray(dst, dstOffset + i);
        }

        if (i < length) {
            VectorMask<Float> mask = SPECIES.indexInRange(i, length);
            FloatVector vv = FloatVector.fromArray(SPECIES, src, srcOffset + i, mask);
            vv.mul(vScalar).intoArray(dst, dstOffset + i, mask);
        }
    }

    // ─────────────────────── Add ───────────────────────

    /**
     * Adds two vectors element-wise and returns a new array.
     *
     * @param a first vector
     * @param b second vector
     * @return a new array containing a + b
     */
    public static float[] add(float[] a, float[] b) {
        float[] result = new float[a.length];
        add(a, 0, b, 0, result, 0, a.length);
        return result;
    }

    /**
     * Adds two vector slices element-wise and writes to a destination slice.
     */
    public static void add(float[] a, int aOffset, float[] b, int bOffset,
                           float[] dst, int dstOffset, int length) {
        validateSlice(a, aOffset, length);
        validateSlice(b, bOffset, length);
        validateSlice(dst, dstOffset, length);

        int laneCount = SPECIES.length();

        int i = 0;
        int limit = SPECIES.loopBound(length);
        for (; i < limit; i += laneCount) {
            FloatVector va = FloatVector.fromArray(SPECIES, a, aOffset + i);
            FloatVector vb = FloatVector.fromArray(SPECIES, b, bOffset + i);
            va.add(vb).intoArray(dst, dstOffset + i);
        }

        if (i < length) {
            VectorMask<Float> mask = SPECIES.indexInRange(i, length);
            FloatVector va = FloatVector.fromArray(SPECIES, a, aOffset + i, mask);
            FloatVector vb = FloatVector.fromArray(SPECIES, b, bOffset + i, mask);
            va.add(vb).intoArray(dst, dstOffset + i, mask);
        }
    }

    // ─────────────────────── Subtract ───────────────────────

    /**
     * Subtracts two vectors element-wise (a - b) and returns a new array.
     *
     * @param a first vector
     * @param b second vector
     * @return a new array containing a - b
     */
    public static float[] subtract(float[] a, float[] b) {
        float[] result = new float[a.length];
        subtract(a, 0, b, 0, result, 0, a.length);
        return result;
    }

    /**
     * Subtracts two vector slices element-wise and writes to a destination slice.
     */
    public static void subtract(float[] a, int aOffset, float[] b, int bOffset,
                                float[] dst, int dstOffset, int length) {
        validateSlice(a, aOffset, length);
        validateSlice(b, bOffset, length);
        validateSlice(dst, dstOffset, length);

        int laneCount = SPECIES.length();

        int i = 0;
        int limit = SPECIES.loopBound(length);
        for (; i < limit; i += laneCount) {
            FloatVector va = FloatVector.fromArray(SPECIES, a, aOffset + i);
            FloatVector vb = FloatVector.fromArray(SPECIES, b, bOffset + i);
            va.sub(vb).intoArray(dst, dstOffset + i);
        }

        if (i < length) {
            VectorMask<Float> mask = SPECIES.indexInRange(i, length);
            FloatVector va = FloatVector.fromArray(SPECIES, a, aOffset + i, mask);
            FloatVector vb = FloatVector.fromArray(SPECIES, b, bOffset + i, mask);
            va.sub(vb).intoArray(dst, dstOffset + i, mask);
        }
    }

    // ─────────────────────── Validation ───────────────────────

    private static void validateSlice(float[] arr, int offset, int length) {
        if (length < 0) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NEGATIVE, "length", length);
        }
        if (offset < 0 || offset + length > arr.length) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, String.format("offset=%d, length=%d, array.length=%d", offset, length, arr.length));
        }
    }
}