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
package com.spectrayan.spector.core.quantization.svasq;
import com.spectrayan.spector.commons.error.SpectorException;

import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Prepares a {@link Svasq4QueryState} from a raw float32 query vector.
 *
 * <p>Call {@link #prepare(float[])} exactly <em>once per query</em> before the
 * HNSW/IVF graph traversal loop. The resulting {@link Svasq4QueryState} is then
 * passed to {@link Svasq4SimdKernel} for every candidate distance evaluation.</p>
 *
 * <h3>Preparation steps</h3>
 * <ol>
 *   <li>Compute exact query norm: {@code qNormSq = ‖q‖²}.</li>
 *   <li>FWHT-rotate the query: {@code qRot = FWHT(signFlip(q_padded)) / √paddedDim}.</li>
 *   <li>Pre-scale: {@code q̃ᵢ = qRotᵢ × scaleᵢ} and accumulate
 *       {@code C(q) = Σ qRotᵢ × μᵢ}.</li>
 *   <li>Compute nibble bias: {@code nibbleBias = 7 × Σᵢ q̃ᵢ}.</li>
 *   <li>Deinterleave q̃ into hi/lo arrays for SIMD kernel alignment.</li>
 *   <li>Compute adjusted constants:
 *       {@code constL2Q = qNormSq − 2·C(q) + 2·nibbleBias}.</li>
 * </ol>
 *
 * <h3>Allocation budget</h3>
 * <p>Uses per-thread {@link ThreadLocal} scratch buffers for the FWHT rotation
 * and the deinterleaved output arrays. Zero per-call heap allocation on the hot path.</p>
 *
 * <h3>Lifetime contract</h3>
 * <p>The returned {@link Svasq4QueryState} references thread-local storage and must
 * not be stored beyond the current search call.</p>
 *
 * <p>Instances are immutable after construction and safe for concurrent use.</p>
 */
public final class Svasq4QueryPrep {

    private final SvasqParams params;
    private final int paddedDim;
    private final int halfDim;

    /**
     * Per-thread scratch: [0] = qRot(paddedDim), [1] = qTildeHi(halfDim), [2] = qTildeLo(halfDim).
     */
    private final ThreadLocal<float[][]> queryScratch;

    /**
     * Creates a query preparer backed by the given 4-bit calibration parameters.
     *
     * @param params calibrated SVASQ-4 parameters (non-null, bitWidth must be 4)
     * @throws SpectorValidationException if params.bitWidth() ≠ 4
     */
    public Svasq4QueryPrep(SvasqParams params) {
        if (params == null) throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "params");
        if (params.bitWidth() != SvasqParams.BIT_WIDTH_4) {
            throw new SpectorValidationException(ErrorCode.BIT_WIDTH_INVALID, "4", params.bitWidth());
        }
        this.params = params;
        this.paddedDim = params.paddedDim();
        this.halfDim = paddedDim / 2;
        this.queryScratch = ThreadLocal.withInitial(() -> new float[][] {
                new float[paddedDim],   // [0] qRot
                new float[halfDim],     // [1] qTildeHi (even dims)
                new float[halfDim]      // [2] qTildeLo (odd dims)
        });
    }

    /**
     * Prepares a {@link Svasq4QueryState} from a float32 query vector.
     *
     * <p>Uses thread-local scratch buffers — zero per-call heap allocation.</p>
     *
     * <p><b>Lifetime contract:</b> the returned state references thread-local storage
     * and must not be stored beyond the current search call.</p>
     *
     * @param query the float32 query vector (length must equal {@code params.originalDim()})
     * @return a {@link Svasq4QueryState} ready for {@link Svasq4SimdKernel}
     * @throws SpectorValidationException if query.length ≠ originalDim
     */
    public Svasq4QueryState prepare(float[] query) {
        int originalDim  = params.originalDim();
        float[] means    = params.means();
        float[] scales   = params.scales();

        if (query.length != originalDim) {
            throw new SpectorValidationException(ErrorCode.DIMENSIONS_MISMATCH, originalDim, query.length);
        }

        // 1. Exact query norm squared (double accumulator for precision)
        double qNormSqAcc = 0.0;
        for (float v : query) qNormSqAcc += (double) v * v;
        float qNormSq = (float) qNormSqAcc;

        // 2. Rotate query into thread-local scratch — zero allocation
        float[][] scratch  = queryScratch.get();
        float[] qRot       = scratch[0];
        float[] qTildeHi   = scratch[1];
        float[] qTildeLo   = scratch[2];
        params.fwht().rotate(query, qRot);

        // 3. Pre-scale and accumulate C(q) + nibbleBias
        double cQ = 0.0;
        double nibbleBias = 0.0;

        for (int i = 0; i < paddedDim; i++) {
            float qTilde_i = qRot[i] * scales[i];
            cQ += (double) qRot[i] * means[i];
            nibbleBias += qTilde_i;

            // 4. Deinterleave into hi/lo arrays
            int k = i / 2;
            if ((i & 1) == 0) {
                qTildeHi[k] = qTilde_i;   // even dims → high nibble array
            } else {
                qTildeLo[k] = qTilde_i;   // odd dims  → low nibble array
            }
        }
        nibbleBias *= Svasq4Encoder.OFFSET;  // nibbleBias = 7 × Σ q̃ᵢ

        // 5. Adjusted L2 constant: absorbs offset-encoding bias
        //    L2 = exactNormSq + constL2Q − 2 × dotUnsigned
        //    where constL2Q = qNormSq − 2·C(q) + 2·nibbleBias
        float constL2Q  = qNormSq - 2f * (float) cQ + 2f * (float) nibbleBias;
        float dotOffset = (float) cQ - (float) nibbleBias;

        return new Svasq4QueryState(qTildeHi, qTildeLo, constL2Q, dotOffset, qNormSq);
    }

    /**
     * Returns the calibration parameters backing this query preparer.
     *
     * @return SVASQ-4 params
     */
    public SvasqParams params() { return params; }
}