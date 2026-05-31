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

import com.spectrayan.spector.core.quantization.svasq.SvasqEncoder;
import com.spectrayan.spector.core.quantization.svasq.SvasqParams;
import com.spectrayan.spector.core.quantization.svasq.SvasqQueryPrep;
import com.spectrayan.spector.core.similarity.SimilarityFunction;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.commons.error.SpectorValidationException;

/**
 * Quantization strategy for SVASQ (FWHT-rotated asymmetric INT8 quantization).
 *
 * <h3>Memory layout per vector</h3>
 * <pre>
 *   [float32 exactNormSq (4 bytes)] [INT8 × paddedDim signed codes]
 * </pre>
 *
 * <h3>Distance computation</h3>
 * <p>The core efficiency win: {@link #prepareQueryContext} applies the FWHT rotation
 * and scale pre-multiplication <em>once per query</em>. The resulting
 * {@link DistanceContext.SvasqCtx} is reused for every candidate via
 * {@link SimilarityFunction#computeSvasq}, which dispatches to the Panama SIMD kernel
 * ({@link com.spectrayan.spector.core.quantization.svasq.SvasqSimdKernel}) with zero
 * additional allocations in the hot path.</p>
 *
 * <h3>Thread safety</h3>
 * <p>This class is immutable after construction. The {@link DistanceContext.SvasqCtx}
 * returned by {@link #prepareQueryContext} is a per-call value object that must not
 * be shared across concurrent searches.</p>
 */
public final class SvasqStrategy implements QuantizationStrategy {

    private final SvasqEncoder encoder;
    private final SvasqQueryPrep queryPrep;
    private final SimilarityFunction similarityFunction;
    private final int bpv;
    private final int paddedDim;

    /**
     * Creates a SVASQ strategy from pre-calibrated parameters.
     *
     * @param params             calibrated SVASQ parameters
     * @param similarityFunction distance metric to use (EUCLIDEAN → L2, COSINE/DOT → dot)
     */
    public SvasqStrategy(SvasqParams params, SimilarityFunction similarityFunction) {
        this.encoder = new SvasqEncoder(params);
        this.queryPrep = new SvasqQueryPrep(params);
        this.similarityFunction = similarityFunction;
        this.bpv = params.bytesPerVector();
        this.paddedDim = params.paddedDim();
    }

    /**
     * Creates a SVASQ strategy from a pre-built encoder (avoids double-allocation
     * when an encoder is already available).
     *
     * @param encoder            pre-built SVASQ encoder (non-null)
     * @param similarityFunction distance metric to use
     */
    public SvasqStrategy(SvasqEncoder encoder, SimilarityFunction similarityFunction) {
        this.encoder = encoder;
        this.queryPrep = new SvasqQueryPrep(encoder.params());
        this.similarityFunction = similarityFunction;
        this.bpv = encoder.bytesPerVector();
        this.paddedDim = encoder.params().paddedDim();
    }

    /**
     * Encodes a float32 vector directly into the off-heap segment.
     *
     * <p>Applies FWHT rotation, INT8 quantization, and writes the 4-byte norm
     * header + INT8 codes — zero heap allocation in the store path.</p>
     */
    @Override
    public void encode(float[] vector, MemorySegment segment, long offset) {
        encoder.encode(vector, segment, offset);
    }

    /**
     * Decodes an approximation of the original vector from the off-heap segment.
     *
     * <p>Skips the 4-byte norm header; reads INT8 codes and reconstructs via
     * {@code x̂ᵢ ≈ zᵢ × scaleᵢ + μᵢ} for {@code i < originalDim}.</p>
     */
    @Override
    public float[] decode(MemorySegment segment, long offset, int dimensions) {
        SvasqParams params = encoder.params();
        float[] scales = params.scales();
        float[] means  = params.means();
        float[] result = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            int code = segment.get(ValueLayout.JAVA_BYTE, offset + 4L + i);
            result[i] = code * scales[i] + means[i];
        }
        return result;
    }

    /**
     * Computes SVASQ distance between a stored (quantized) candidate and the
     * pre-prepared query state.
     *
     * <p>Delegates to {@link SimilarityFunction#computeSvasq} which dispatches to
     * the Panama SIMD kernel — reading directly from off-heap memory, zero GC pressure.</p>
     */
    @Override
    public float distance(MemorySegment segment, long offset, DistanceContext ctx) {
        if (!(ctx instanceof DistanceContext.SvasqCtx vc)) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "context", "expected SvasqCtx but got " + ctx.getClass().getSimpleName());
        }
        return similarityFunction.computeSvasq(segment, offset, vc.paddedDim(), vc.state());
    }

    /**
     * Prepares a per-query {@link DistanceContext.SvasqCtx} by applying FWHT rotation
     * and scale pre-multiplication to the query vector.
     *
     * <p>This is the O(D log D) step. Call it <em>once per search</em>, then reuse
     * the returned context for every candidate's {@link #distance} call.</p>
     */
    @Override
    public DistanceContext prepareQueryContext(float[] query) {
        return new DistanceContext.SvasqCtx(queryPrep.prepare(query), paddedDim);
    }

    /** Returns the number of bytes per SVASQ-encoded vector (4-byte header + paddedDim codes). */
    @Override
    public int bytesPerVector() {
        return bpv;
    }

    @Override
    public int compressionFactor(int dimensions) {
        return Math.max(1, (dimensions * 4) / bpv);
    }

    /** Returns the backing SVASQ encoder (for direct segment access by index layers). */
    public SvasqEncoder encoder() {
        return encoder;
    }
}