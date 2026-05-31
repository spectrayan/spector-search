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
 * Prepares a {@link SvasqQueryState} from a raw float32 query vector.
 *
 * <p>Call {@link #prepare(float[])} exactly <em>once per query</em> before the
 * HNSW/IVF graph traversal loop. The resulting {@link SvasqQueryState} is then
 * passed to {@link SvasqSimdKernel} for every candidate distance evaluation.</p>
 *
 * <h3>Preparation Steps</h3>
 * <ol>
 *   <li>Compute exact query norm: {@code qNormSq = ‖q‖²} (on original vector).</li>
 *   <li>Rotate: {@code q_rot = FWHT(signFlip(q_padded)) / √paddedDim}.</li>
 *   <li>For each dimension {@code i}:
 *     <ul>
 *       <li>{@code q̃ᵢ = q_rot_i × scaleᵢ}  (pre-scale for FMA kernel)</li>
 *       <li>Accumulate {@code C(q) += q_rot_i × μᵢ}  (mean correction)</li>
 *     </ul>
 *   </li>
 *   <li>Compute {@code constL2Q = qNormSq - 2 × C(q)}  (query-side L2 constant).</li>
 * </ol>
 *
 * <h3>Allocation Budget</h3>
 * <p>Uses a per-instance {@link ThreadLocal} holding two {@code float[paddedDim]} arrays:
 * {@code qRot} (intermediate rotate output) and {@code qTilde} (scaled query for the
 * SIMD kernel). Both are allocated once per thread on first use and reused across
 * all subsequent calls, eliminating the per-query allocation that previously occurred.</p>
 *
 * <h3>Contract: SvasqQueryState Lifetime</h3>
 * <p>The returned {@link SvasqQueryState} holds a direct reference to the thread-local
 * {@code qTilde} buffer. It must <em>not</em> be stored beyond the current search call —
 * reuse of the buffer by a subsequent {@link #prepare} call on the same thread would
 * silently corrupt the stale state. In practice, the state is always consumed within
 * the HNSW search and discarded before the method returns, making this safe.</p>
 *
 * <p>Instances are immutable after construction and safe for concurrent use
 * (each thread has its own scratch buffers via ThreadLocal).</p>
 */
public final class SvasqQueryPrep {

    private final SvasqParams params;

    /**
     * Per-thread scratch: [0] = qRot (paddedDim), [1] = qTilde (paddedDim).
     * The qTilde array is directly referenced by the returned SvasqQueryState.
     */
    private final ThreadLocal<float[][]> queryScratch;

    /**
     * Creates a query preparer backed by the given calibration parameters.
     *
     * @param params calibrated SVASQ parameters (non-null)
     */
    public SvasqQueryPrep(SvasqParams params) {
        if (params == null) throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "params");
        this.params = params;
        final int paddedDim = params.paddedDim();
        this.queryScratch   = ThreadLocal.withInitial(() -> new float[][] {
                new float[paddedDim],   // [0] qRot
                new float[paddedDim]    // [1] qTilde — referenced by SvasqQueryState
        });
    }

    /**
     * Prepares a {@link SvasqQueryState} from a float32 query vector.
     *
     * <p>Uses thread-local scratch buffers for both the rotate step and the scaled
     * query vector — zero per-call heap allocations on the hot path.</p>
     *
     * <p><b>Lifetime contract:</b> the returned state references thread-local storage
     * and must not be stored beyond the current search call.</p>
     *
     * @param query the float32 query vector (length must equal {@code params.originalDim()})
     * @return an immutable-by-contract {@link SvasqQueryState} ready for {@link SvasqSimdKernel}
     * @throws SpectorValidationException if query.length ≠ originalDim
     */
    public SvasqQueryState prepare(float[] query) {
        int originalDim = params.originalDim();
        int paddedDim   = params.paddedDim();
        float[] means   = params.means();
        float[] scales  = params.scales();

        if (query.length != originalDim) {
            throw new SpectorValidationException(ErrorCode.DIMENSIONS_MISMATCH, originalDim, query.length);
        }

        // 1. Exact query norm squared (double accumulator for precision)
        double qNormSqAcc = 0.0;
        for (float v : query) qNormSqAcc += (double) v * v;
        float qNormSq = (float) qNormSqAcc;

        // 2. Rotate query into thread-local qRot — zero allocation
        float[][] scratch = queryScratch.get();
        float[] qRot   = scratch[0];
        float[] qTilde = scratch[1];
        params.fwht().rotate(query, qRot);

        // 3. Fill qTilde and accumulate C(q) = Σ q_rot_i × μᵢ
        double cQ = 0.0;
        for (int i = 0; i < paddedDim; i++) {
            qTilde[i] = qRot[i] * scales[i];
            cQ        += (double) qRot[i] * means[i];
        }

        // 4. Query-side L2 constant: ‖q‖² - 2·C(q)  ← CORRECT sign
        float constL2Q  = qNormSq - 2f * (float) cQ;
        float dotOffset = (float) cQ;

        // qTilde is the thread-local array — referenced (not copied) by SvasqQueryState.
        // Safe because the state is only used within the current search call.
        return new SvasqQueryState(qTilde, constL2Q, dotOffset, qNormSq);
    }

    /**
     * Returns the calibration parameters backing this query preparer.
     *
     * @return SVASQ params
     */
    public SvasqParams params() {
        return params;
    }
}