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
package com.spectrayan.spector.core.quantization.strategy;

import com.spectrayan.spector.core.quantization.svasq.Svasq4QueryState;
import com.spectrayan.spector.core.quantization.svasq.SvasqQueryState;

/**
 * Sealed per-query distance context — carries pre-computed state that is
 * prepared <em>once per search</em> and reused for every candidate comparison.
 *
 * <p>Each {@link QuantizationStrategy} produces one concrete subtype from
 * {@link QuantizationStrategy#prepareQueryContext(float[])}. The subtype is
 * then passed back into {@link QuantizationStrategy#distance} for each
 * candidate node, avoiding redundant computation in the HNSW hot loop.</p>
 *
 * <h3>Sealed hierarchy</h3>
 * <ul>
 *   <li>{@link Int8Context} — query vector + per-dim min/scale for INT8 ADC</li>
 *   <li>{@link PackedContext} — query vector + global centroids for INT4/INT2 packed dot</li>
 *   <li>{@link TurboContext} — pre-rotated query for TurboQuant distance</li>
 *   <li>{@link SvasqCtx} — pre-rotated query state for SVASQ-8 SIMD kernel</li>
 *   <li>{@link Svasq4Ctx} — deinterleaved query state for SVASQ-4 nibble SIMD kernel</li>
 *   <li>{@link ExactContext} — raw float query for exact float32 fallback</li>
 * </ul>
 */
public sealed interface DistanceContext
        permits DistanceContext.Int8Context,
                DistanceContext.PackedContext,
                DistanceContext.TurboContext,
                DistanceContext.SvasqCtx,
                DistanceContext.Svasq4Ctx,
                DistanceContext.ExactContext {

    /**
     * Context for INT8 (SQ8) asymmetric distance computation.
     *
     * @param query  the raw float query vector
     * @param mins   per-dimension min values from ScalarQuantizer
     * @param scales per-dimension scale values from ScalarQuantizer
     */
    record Int8Context(float[] query, float[] mins, float[] scales)
            implements DistanceContext {}

    /**
     * Context for INT4 / INT2 packed dot product computation.
     *
     * @param query           the raw float query vector
     * @param globalCentroids averaged centroids for PackedDotProduct lookup
     * @param dimensions      original vector dimensionality
     */
    record PackedContext(float[] query, float[] globalCentroids, int dimensions)
            implements DistanceContext {}

    /**
     * Context for TurboQuant distance computation.
     *
     * <p>Carries the pre-rotated query vector — the rotation step (O(D²)) is performed
     * once per search and reused for every candidate comparison.</p>
     *
     * @param rotatedQuery pre-rotated query in TurboQuant's rotated space
     */
    record TurboContext(float[] rotatedQuery)
            implements DistanceContext {}

    /**
     * Context for SVASQ SIMD kernel (FWHT-rotated asymmetric distance).
     *
     * <p>Contains the pre-rotated, pre-scaled query tilde and the asymmetric
     * constant for the L2 expansion formula.</p>
     *
     * @param state     pre-computed SVASQ query state (qTilde, constL2Q, dotOffset)
     * @param paddedDim SVASQ padded dimensionality (power-of-two)
     */
    record SvasqCtx(SvasqQueryState state, int paddedDim)
            implements DistanceContext {}

    /**
     * Context for SVASQ-4 nibble-packed SIMD kernel (FWHT-rotated asymmetric distance, INT4).
     *
     * <p>Contains the deinterleaved pre-scaled query arrays (hi/lo) and the
     * adjusted L2 constant with offset-encoding bias absorbed.</p>
     *
     * @param state   pre-computed SVASQ-4 query state (qTildeHi, qTildeLo, constL2Q, dotOffset)
     * @param halfDim half of paddedDim (number of nibble-packed code bytes)
     */
    record Svasq4Ctx(Svasq4QueryState state, int halfDim)
            implements DistanceContext {}

    /**
     * Fallback context for exact float32 distance (used before calibration
     * or when no quantizer is available).
     *
     * @param query the raw float query vector
     */
    record ExactContext(float[] query)
            implements DistanceContext {}
}
