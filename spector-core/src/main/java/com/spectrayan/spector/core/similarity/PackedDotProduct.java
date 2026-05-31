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

import com.spectrayan.spector.core.simd.SimdCapability;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * SIMD-accelerated dot product computation on nibble-packed (INT4) and crumb-packed (INT2)
 * quantized vectors stored in an off-heap {@link MemorySegment}.
 *
 * <h3>Zero-Copy Design</h3>
 * <p>All {@code computeInt4} and {@code computeInt2} overloads that accept a
 * {@link MemorySegment} read directly from off-heap memory without any intermediate
 * {@code byte[]} allocation. This is the correct Panama API usage: the segment
 * is the authoritative store, and compute kernels operate on it in-place.</p>
 *
 * <h3>GC-Free Hot Path</h3>
 * <p>The previous {@code byte[]}-based SIMD path allocated:</p>
 * <ul>
 *   <li>{@code float[] docValues = new float[laneCount]} inside the SIMD loop
 *       — O(D/laneCount) heap allocations per call</li>
 *   <li>{@code float[] products = new float[dimensions]}
 *       — 1 heap allocation per call</li>
 * </ul>
 * <p>The new segment-based path allocates <strong>zero objects</strong> in the hot loop.
 * A single {@code float[laneCount]} scratch buffer is pre-allocated as a local variable
 * outside the loop and reused across SIMD iterations.</p>
 *
 * <h3>INT4 (Nibble Packing)</h3>
 * <pre>
 *   Each byte: [dim_2i (bits 7-4)] [dim_2i+1 (bits 3-0)]
 *   Centroids array: 16 entries (one per quantization level, 0–15)
 * </pre>
 *
 * <h3>INT2 (Crumb Packing)</h3>
 * <pre>
 *   Each byte: [dim_4i (bits 7-6)] [dim_4i+1 (bits 5-4)] [dim_4i+2 (bits 3-2)] [dim_4i+3 (bits 1-0)]
 *   Centroids array: 4 entries (one per quantization level, 0–3)
 * </pre>
 *
 * <h3>Backward Compatibility</h3>
 * <p>Legacy {@code byte[]}-based overloads are kept for callers that still use heap arrays.
 * These delegate to the segment-based kernels by wrapping via {@link MemorySegment#ofArray}.</p>
 */
public final class PackedDotProduct {

    private static final boolean SIMD_AVAILABLE;
    private static final VectorSpecies<Float> SPECIES;

    static {
        boolean available;
        VectorSpecies<Float> species = null;
        try {
            species = SimdCapability.PREFERRED_SPECIES;
            FloatVector.zero(species);
            available = true;
        } catch (Throwable t) {
            available = false;
        }
        SIMD_AVAILABLE = available;
        SPECIES = species;
    }

    private PackedDotProduct() {}

    // ─────────────── Primary API: zero-copy MemorySegment overloads ───────────────

    /**
     * Computes dot product between a float32 query and a nibble-packed INT4 document vector
     * stored at {@code offset} within the given off-heap {@link MemorySegment}.
     *
     * <p>Reads directly from off-heap memory — zero heap allocation in the hot path.</p>
     *
     * @param query      float32 query vector (length ≥ dimensions)
     * @param segment    off-heap memory segment containing the packed document
     * @param offset     byte offset of the first packed byte within the segment
     * @param centroids4 centroid values for each of the 16 quantization levels
     * @param dimensions number of original vector dimensions
     * @return dot product value
     */
    public static float computeInt4(float[] query, MemorySegment segment, long offset,
                                     float[] centroids4, int dimensions) {
        if (SIMD_AVAILABLE) {
            return computeInt4SimdFromSegment(query, segment, offset, centroids4, dimensions);
        }
        return computeInt4ScalarFromSegment(query, segment, offset, centroids4, dimensions);
    }

    /**
     * Computes dot product between a float32 query and a crumb-packed INT2 document vector
     * stored at {@code offset} within the given off-heap {@link MemorySegment}.
     *
     * <p>Reads directly from off-heap memory — zero heap allocation in the hot path.</p>
     *
     * @param query      float32 query vector (length ≥ dimensions)
     * @param segment    off-heap memory segment containing the packed document
     * @param offset     byte offset of the first packed byte within the segment
     * @param centroids2 centroid values for each of the 4 quantization levels
     * @param dimensions number of original vector dimensions
     * @return dot product value
     */
    public static float computeInt2(float[] query, MemorySegment segment, long offset,
                                     float[] centroids2, int dimensions) {
        if (SIMD_AVAILABLE) {
            return computeInt2SimdFromSegment(query, segment, offset, centroids2, dimensions);
        }
        return computeInt2ScalarFromSegment(query, segment, offset, centroids2, dimensions);
    }

    // ─────────────── Backward-compat: byte[] overloads (delegate to segment path) ───────────────

    /**
     * Computes dot product between a float32 query and a nibble-packed INT4 document vector.
     *
     * @deprecated Prefer the {@link MemorySegment} overload for zero-copy off-heap access.
     */
    @Deprecated
    public static float computeInt4(float[] query, byte[] packedDoc,
                                     float[] centroids4, int dimensions) {
        // Wrap heap array as a read-only segment — no data copy, just a view
        MemorySegment seg = MemorySegment.ofArray(packedDoc);
        return computeInt4(query, seg, 0L, centroids4, dimensions);
    }

    /**
     * Computes dot product between a float32 query and a crumb-packed INT2 document vector.
     *
     * @deprecated Prefer the {@link MemorySegment} overload for zero-copy off-heap access.
     */
    @Deprecated
    public static float computeInt2(float[] query, byte[] packedDoc,
                                     float[] centroids2, int dimensions) {
        MemorySegment seg = MemorySegment.ofArray(packedDoc);
        return computeInt2(query, seg, 0L, centroids2, dimensions);
    }

    // ─────────────── Scalar fallbacks (segment-based, zero heap alloc) ───────────────

    /**
     * Scalar INT4 dot product from off-heap segment — zero heap allocation.
     *
     * <p>Reads each packed byte directly from the segment. No intermediate array.</p>
     */
    public static float computeInt4ScalarFromSegment(float[] query, MemorySegment segment,
                                                      long offset, float[] centroids4, int dimensions) {
        float sum = 0.0f;
        for (int i = 0; i < dimensions; i++) {
            int byteIndex = i >> 1; // i / 2
            int packed = segment.get(ValueLayout.JAVA_BYTE, offset + byteIndex) & 0xFF;
            int level = (i & 1) == 0 ? (packed >> 4) : (packed & 0x0F);
            sum += query[i] * centroids4[level];
        }
        return sum;
    }

    /**
     * Scalar INT2 dot product from off-heap segment — zero heap allocation.
     */
    public static float computeInt2ScalarFromSegment(float[] query, MemorySegment segment,
                                                      long offset, float[] centroids2, int dimensions) {
        float sum = 0.0f;
        for (int i = 0; i < dimensions; i++) {
            int byteIndex = i >> 2; // i / 4
            int packed = segment.get(ValueLayout.JAVA_BYTE, offset + byteIndex) & 0xFF;
            int shift = 6 - ((i & 3) << 1); // 6 - (i%4)*2
            int level = (packed >> shift) & 0x03;
            sum += query[i] * centroids2[level];
        }
        return sum;
    }

    // ─────────────── SIMD kernels (segment-based, GC-free hot loop) ───────────────

    /**
     * SIMD-accelerated INT4 dot product from off-heap segment.
     *
     * <h3>Zero-allocation design</h3>
     * <p>A single {@code float[laneCount]} scratch buffer is allocated <em>once</em> per call
     * (stack-equivalent) and reused across all SIMD iterations. There are no per-iteration
     * allocations. The packed bytes are read directly from the off-heap segment via
     * {@link MemorySegment#get}.</p>
     *
     * <h3>FMA accumulation</h3>
     * <p>Uses {@link FloatVector#fma} for fused multiply-add and a single
     * {@link FloatVector#reduceLanes} at the end — one horizontal reduction vs.
     * one per iteration.</p>
     */
    private static float computeInt4SimdFromSegment(float[] query, MemorySegment segment,
                                                      long offset, float[] centroids4, int dimensions) {
        int laneCount = SPECIES.length();
        // Single scratch buffer — allocated once per call, reused across SIMD iterations
        float[] docValues = new float[laneCount];

        FloatVector acc = FloatVector.zero(SPECIES);
        int limit = SPECIES.loopBound(dimensions);

        for (int i = 0; i < limit; i += laneCount) {
            // Unpack laneCount nibbles into docValues[] — read directly from segment
            for (int j = 0; j < laneCount; j++) {
                int dim = i + j;
                int byteIndex = dim >> 1;
                int packed = segment.get(ValueLayout.JAVA_BYTE, offset + byteIndex) & 0xFF;
                docValues[j] = centroids4[(dim & 1) == 0 ? (packed >> 4) : (packed & 0x0F)];
            }
            FloatVector vQuery = FloatVector.fromArray(SPECIES, query, i);
            FloatVector vDoc   = FloatVector.fromArray(SPECIES, docValues, 0);
            // FMA: acc += vQuery * vDoc
            acc = vQuery.fma(vDoc, acc);
        }

        // Single horizontal reduction
        float sum = acc.reduceLanes(VectorOperators.ADD);

        // Scalar tail for remaining dimensions (when dimensions % laneCount != 0)
        for (int i = limit; i < dimensions; i++) {
            int byteIndex = i >> 1;
            int packed = segment.get(ValueLayout.JAVA_BYTE, offset + byteIndex) & 0xFF;
            int level = (i & 1) == 0 ? (packed >> 4) : (packed & 0x0F);
            sum += query[i] * centroids4[level];
        }

        return sum;
    }

    /**
     * SIMD-accelerated INT2 dot product from off-heap segment.
     *
     * <p>Same zero-allocation design as {@link #computeInt4SimdFromSegment}:
     * one scratch {@code float[laneCount]} reused per call, bytes read directly
     * from the off-heap segment, FMA accumulation with single {@code reduceLanes}.</p>
     */
    private static float computeInt2SimdFromSegment(float[] query, MemorySegment segment,
                                                      long offset, float[] centroids2, int dimensions) {
        int laneCount = SPECIES.length();
        float[] docValues = new float[laneCount];

        FloatVector acc = FloatVector.zero(SPECIES);
        int limit = SPECIES.loopBound(dimensions);

        for (int i = 0; i < limit; i += laneCount) {
            for (int j = 0; j < laneCount; j++) {
                int dim = i + j;
                int byteIndex = dim >> 2;
                int packed = segment.get(ValueLayout.JAVA_BYTE, offset + byteIndex) & 0xFF;
                int shift = 6 - ((dim & 3) << 1);
                docValues[j] = centroids2[(packed >> shift) & 0x03];
            }
            FloatVector vQuery = FloatVector.fromArray(SPECIES, query, i);
            FloatVector vDoc   = FloatVector.fromArray(SPECIES, docValues, 0);
            acc = vQuery.fma(vDoc, acc);
        }

        float sum = acc.reduceLanes(VectorOperators.ADD);

        for (int i = limit; i < dimensions; i++) {
            int byteIndex = i >> 2;
            int packed = segment.get(ValueLayout.JAVA_BYTE, offset + byteIndex) & 0xFF;
            int shift = 6 - ((i & 3) << 1);
            sum += query[i] * centroids2[(packed >> shift) & 0x03];
        }

        return sum;
    }

    // ─────────────── Legacy scalar byte[] fallbacks ───────────────

    /**
     * Scalar INT4 dot product from heap byte[]. Identical results to the segment path.
     *
     * @deprecated Use the {@link MemorySegment} overload.
     */
    @Deprecated
    public static float computeInt4Scalar(float[] query, byte[] packedDoc,
                                           float[] centroids4, int dimensions) {
        return computeInt4ScalarFromSegment(query, MemorySegment.ofArray(packedDoc), 0L, centroids4, dimensions);
    }

    /**
     * Scalar INT2 dot product from heap byte[]. Identical results to the segment path.
     *
     * @deprecated Use the {@link MemorySegment} overload.
     */
    @Deprecated
    public static float computeInt2Scalar(float[] query, byte[] packedDoc,
                                           float[] centroids2, int dimensions) {
        return computeInt2ScalarFromSegment(query, MemorySegment.ofArray(packedDoc), 0L, centroids2, dimensions);
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
