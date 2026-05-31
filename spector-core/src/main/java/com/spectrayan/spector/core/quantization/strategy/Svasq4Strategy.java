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
import com.spectrayan.spector.commons.error.SpectorException;

import com.spectrayan.spector.core.quantization.svasq.Svasq4Encoder;
import com.spectrayan.spector.core.quantization.svasq.Svasq4QueryPrep;
import com.spectrayan.spector.core.quantization.svasq.SvasqParams;
import com.spectrayan.spector.core.similarity.SimilarityFunction;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.commons.error.SpectorValidationException;

/**
 * Quantization strategy for SVASQ-4 (FWHT-rotated asymmetric INT4 quantization).
 *
 * <h3>Memory layout per vector</h3>
 * <pre>
 *   [float32 exactNormSq (4 bytes)] [nibble-packed INT4 × paddedDim/2 bytes]
 * </pre>
 *
 * <h3>Distance computation</h3>
 * <p>{@link #prepareQueryContext} applies FWHT rotation, pre-scaling, deinterleaving,
 * and offset-bias folding <em>once per query</em>. The resulting
 * {@link DistanceContext.Svasq4Ctx} is reused for every candidate via
 * {@link SimilarityFunction#computeSvasq4}, which dispatches to the Panama SIMD kernel
 * ({@link com.spectrayan.spector.core.quantization.svasq.Svasq4SimdKernel}) with zero
 * heap allocations on the hot path.</p>
 *
 * <h3>Thread safety</h3>
 * <p>This class is immutable after construction. The {@link DistanceContext.Svasq4Ctx}
 * returned by {@link #prepareQueryContext} is a per-call value that must not be
 * shared across concurrent searches.</p>
 */
public final class Svasq4Strategy implements QuantizationStrategy {

    private final Svasq4Encoder encoder;
    private final Svasq4QueryPrep queryPrep;
    private final SimilarityFunction similarityFunction;
    private final int bpv;
    private final int halfDim;

    /**
     * Creates a SVASQ-4 strategy from pre-calibrated 4-bit parameters.
     *
     * @param params             calibrated SVASQ parameters with bitWidth=4
     * @param similarityFunction distance metric (EUCLIDEAN → L2, COSINE/DOT → dot)
     */
    public Svasq4Strategy(SvasqParams params, SimilarityFunction similarityFunction) {
        this.encoder = new Svasq4Encoder(params);
        this.queryPrep = new Svasq4QueryPrep(params);
        this.similarityFunction = similarityFunction;
        this.bpv = params.bytesPerVector();
        this.halfDim = params.paddedDim() / 2;
    }

    /**
     * Creates a SVASQ-4 strategy from a pre-built encoder.
     *
     * @param encoder            pre-built SVASQ-4 encoder (non-null)
     * @param similarityFunction distance metric
     */
    public Svasq4Strategy(Svasq4Encoder encoder, SimilarityFunction similarityFunction) {
        this.encoder = encoder;
        this.queryPrep = new Svasq4QueryPrep(encoder.params());
        this.similarityFunction = similarityFunction;
        this.bpv = encoder.bytesPerVector();
        this.halfDim = encoder.params().paddedDim() / 2;
    }

    /**
     * Encodes a float32 vector directly into the off-heap segment.
     *
     * <p>Applies FWHT rotation, INT4 quantization, offset encoding, and writes
     * the 4-byte norm header + nibble-packed codes — zero heap allocation.</p>
     */
    @Override
    public void encode(float[] vector, MemorySegment segment, long offset) {
        encoder.encode(vector, segment, offset);
    }

    /**
     * Decodes an approximation of the original vector from the off-heap segment.
     *
     * <p>Reads the nibble-packed codes, reverses offset encoding, and reconstructs via
     * {@code x̂ᵢ ≈ (uᵢ − 7) × scaleᵢ + μᵢ}.</p>
     */
    @Override
    public float[] decode(MemorySegment segment, long offset, int dimensions) {
        return encoder.decode(segment, offset, dimensions);
    }

    /**
     * Computes SVASQ-4 distance between a stored (quantized) candidate and the
     * pre-prepared query state.
     *
     * <p>Delegates to {@link SimilarityFunction#computeSvasq4} which dispatches to
     * the Panama SIMD kernel — reading directly from off-heap memory, zero GC pressure.</p>
     */
    @Override
    public float distance(MemorySegment segment, long offset, DistanceContext ctx) {
        if (!(ctx instanceof DistanceContext.Svasq4Ctx vc)) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "Expected Svasq4Ctx, got: " + ctx.getClass().getSimpleName());
        }
        return similarityFunction.computeSvasq4(segment, offset, vc.halfDim(), vc.state());
    }

    /**
     * Prepares a per-query {@link DistanceContext.Svasq4Ctx} by applying FWHT rotation,
     * pre-scaling, deinterleaving, and offset-bias folding.
     *
     * <p>This is the O(D log D) step. Call it <em>once per search</em>, then reuse
     * the returned context for every candidate's {@link #distance} call.</p>
     */
    @Override
    public DistanceContext prepareQueryContext(float[] query) {
        return new DistanceContext.Svasq4Ctx(queryPrep.prepare(query), halfDim);
    }

    /** Returns the bytes per SVASQ-4 encoded vector (4-byte header + paddedDim/2 nibble-packed codes). */
    @Override
    public int bytesPerVector() {
        return bpv;
    }

    @Override
    public int compressionFactor(int dimensions) {
        return Math.max(1, (dimensions * 4) / bpv);
    }

    /** Returns the backing SVASQ-4 encoder. */
    public Svasq4Encoder encoder() {
        return encoder;
    }
}