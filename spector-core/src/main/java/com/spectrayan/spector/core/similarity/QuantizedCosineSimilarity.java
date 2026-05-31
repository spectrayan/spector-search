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
 * SIMD-accelerated asymmetric cosine similarity between a float32 query
 * and a quantized INT8 document vector stored in an off-heap {@link MemorySegment}.
 *
 * <h3>Zero-Copy Design</h3>
 * <p>The primary {@link #compute(float[], MemorySegment, long, float[], float[], int)}
 * overload reads INT8 codes directly from the off-heap segment without any
 * intermediate {@code byte[]} allocation. The query vector remains in float32.</p>
 *
 * <h3>GC-Free Hot Path</h3>
 * <p>Previous implementation allocated {@code float[] dequantized = new float[laneCount]}
 * inside the SIMD loop — O(D/laneCount) heap allocations per call. The new implementation
 * allocates a single {@code float[laneCount]} scratch buffer <em>once per call</em> and
 * reuses it across all SIMD iterations. Zero per-iteration allocations.</p>
 *
 * <h3>Formula</h3>
 * <pre>
 *   cosine(query, dequant(doc)) = dot(q, d') / (‖q‖ × ‖d'‖)
 *   where d'[i] = unsigned(byte[i]) × scale[i] + min[i]
 * </pre>
 */
public final class QuantizedCosineSimilarity {

    private static final VectorSpecies<Float> SPECIES = SimdCapability.PREFERRED_SPECIES;

    private QuantizedCosineSimilarity() {}

    /**
     * Computes cosine similarity between a float32 query and a quantized INT8 vector
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
     * @return approximate cosine similarity in [-1, 1]
     */
    public static float compute(float[] query, MemorySegment segment, long offset,
                                 float[] mins, float[] scales, int length) {
        int laneCount = SPECIES.length();
        // Single scratch buffer — allocated once per call, reused across SIMD iterations
        float[] scratch = new float[laneCount];

        FloatVector sumDot  = FloatVector.zero(SPECIES);
        FloatVector sumNormQ = FloatVector.zero(SPECIES);
        FloatVector sumNormD = FloatVector.zero(SPECIES);

        int limit = SPECIES.loopBound(length);
        for (int i = 0; i < limit; i += laneCount) {
            FloatVector vQuery = FloatVector.fromArray(SPECIES, query, i);

            // Dequantize laneCount bytes from off-heap segment into scratch (no heap alloc per iter)
            for (int j = 0; j < laneCount; j++) {
                int unsigned = segment.get(ValueLayout.JAVA_BYTE, offset + i + j) & 0xFF;
                scratch[j] = unsigned * scales[i + j] + mins[i + j];
            }
            FloatVector vDoc = FloatVector.fromArray(SPECIES, scratch, 0);

            sumDot  = vQuery.fma(vDoc, sumDot);
            sumNormQ = vQuery.fma(vQuery, sumNormQ);
            sumNormD = vDoc.fma(vDoc, sumNormD);
        }

        // Scalar tail
        float tailDot = 0, tailNormQ = 0, tailNormD = 0;
        for (int i = limit; i < length; i++) {
            int unsigned = segment.get(ValueLayout.JAVA_BYTE, offset + i) & 0xFF;
            float d = unsigned * scales[i] + mins[i];
            tailDot  += query[i] * d;
            tailNormQ += query[i] * query[i];
            tailNormD += d * d;
        }

        float dot   = sumDot.reduceLanes(VectorOperators.ADD)  + tailDot;
        float normQ = sumNormQ.reduceLanes(VectorOperators.ADD) + tailNormQ;
        float normD = sumNormD.reduceLanes(VectorOperators.ADD) + tailNormD;

        float denom = (float) Math.sqrt((double) normQ * normD);
        return denom == 0.0f ? 0.0f : dot / denom;
    }

    /**
     * Backward-compatible overload: computes cosine similarity from a heap {@code byte[]} array.
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
}
