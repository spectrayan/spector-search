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
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * SIMD-accelerated asymmetric dot product between a float32 query and a
 * quantized INT8 document vector stored in an off-heap {@link MemorySegment}.
 *
 * <h3>Zero-Copy Design</h3>
 * <p>The primary {@link #compute(float[], MemorySegment, long, float[], float[], int)}
 * overload reads INT8 codes directly from the off-heap segment without any intermediate
 * {@code byte[]} allocation. The query vector remains in full float32 precision.</p>
 *
 * <h3>GC-Free Hot Path</h3>
 * <p>Previous implementation allocated {@code float[] dequantized = new float[laneCount]}
 * inside the SIMD loop — O(D/laneCount) heap allocations per call. The new implementation
 * allocates a single {@code float[laneCount]} scratch buffer <em>once per call</em> and
 * reuses it across all SIMD iterations. Zero per-iteration allocations.</p>
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
     * Computes the dot product between a float32 query and a quantized INT8 vector
     * stored in an off-heap {@link MemorySegment}.
     *
     * <p>Zero-copy: reads directly from off-heap memory, no {@code byte[]} intermediate.</p>
     *
     * @param query   the float32 query vector
     * @param segment off-heap segment containing the quantized document
     * @param offset  byte offset of the first INT8 code within the segment
     * @param mins    per-dimension minimum values from calibration
     * @param scales  per-dimension scale values from calibration
     * @param length  number of dimensions
     * @return approximate dot product
     */
    public static float compute(float[] query, MemorySegment segment, long offset,
                                 float[] mins, float[] scales, int length) {
        int laneCount = SPECIES.length();
        // Single scratch buffer — allocated once per call, reused across SIMD iterations
        float[] scratch = new float[laneCount];

        FloatVector sumDot = FloatVector.zero(SPECIES);

        int limit = SPECIES.loopBound(length);
        for (int i = 0; i < limit; i += laneCount) {
            FloatVector vQuery = FloatVector.fromArray(SPECIES, query, i);

            // Dequantize laneCount bytes from off-heap segment (no heap alloc per iteration)
            for (int j = 0; j < laneCount; j++) {
                int unsigned = segment.get(ValueLayout.JAVA_BYTE, offset + i + j) & 0xFF;
                scratch[j] = unsigned * scales[i + j] + mins[i + j];
            }
            FloatVector vDoc = FloatVector.fromArray(SPECIES, scratch, 0);

            // FMA: acc += query * dequantized_doc
            sumDot = vQuery.fma(vDoc, sumDot);
        }

        // Scalar tail
        float tail = 0.0f;
        for (int i = limit; i < length; i++) {
            int unsigned = segment.get(ValueLayout.JAVA_BYTE, offset + i) & 0xFF;
            tail += query[i] * (unsigned * scales[i] + mins[i]);
        }

        return sumDot.reduceLanes(VectorOperators.ADD) + tail;
    }

    /**
     * Backward-compatible overload: computes dot product from a heap {@code byte[]} array.
     *
     * <p>Delegates to the segment-based kernel via {@link MemorySegment#ofArray} — no data copy.</p>
     *
     * @deprecated Prefer the {@link MemorySegment} overload for zero-copy off-heap access.
     */
    @Deprecated
    public static float compute(float[] query, byte[] quantized,
                                 float[] mins, float[] scales, int length) {
        return compute(query, MemorySegment.ofArray(quantized), 0L, mins, scales, length);
    }

    /**
     * Computes dot product using a pre-dequantized float document vector.
     *
     * @param query       the float32 query vector
     * @param dequantized pre-dequantized document vector (float32)
     * @param length      number of dimensions
     * @return dot product
     */
    public static float computePreDequantized(float[] query, float[] dequantized, int length) {
        return DotProduct.compute(query, 0, dequantized, 0, length);
    }
}