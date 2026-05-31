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

/**
 * Precomputed query context for SVASQ-4 (INT4) asymmetric distance computation.
 *
 * <p>Created once per query by {@link Svasq4QueryPrep#prepare} and reused for every
 * candidate distance evaluation in the HNSW/IVF traversal loop.</p>
 *
 * <h3>Deinterleaved layout for SIMD efficiency</h3>
 * <p>SVASQ-4 nibble-packed bytes contain two values per byte: the high nibble holds
 * even-indexed FWHT dimensions, the low nibble holds odd-indexed dimensions. To
 * enable straight-through SIMD processing, the query's pre-scaled coefficients are
 * deinterleaved into two contiguous arrays:</p>
 * <ul>
 *   <li>{@link #qTildeHi()} — pre-scaled coefficients for even dims: q̃[0], q̃[2], q̃[4], ...</li>
 *   <li>{@link #qTildeLo()} — pre-scaled coefficients for odd dims:  q̃[1], q̃[3], q̃[5], ...</li>
 * </ul>
 *
 * <h3>Offset-encoding bias</h3>
 * <p>Since stored codes are offset-encoded ({@code u = z + 7}), the dot product is:
 * {@code Σ uᵢ × q̃ᵢ = Σ zᵢ × q̃ᵢ + 7 × Σ q̃ᵢ}. The constant term
 * ({@code 7 × Σ q̃ᵢ}) is absorbed into {@link #constL2Q()} so the SIMD kernel
 * computes only the unsigned dot product.</p>
 *
 * <h3>Full L2 distance formula</h3>
 * <pre>
 *   L2 = exactNormSq + constL2Q − 2 × (Σ uᵢ_hi × qTildeHi[i] + Σ uᵢ_lo × qTildeLo[i])
 * </pre>
 *
 * <p>Instances are immutable-by-contract and safe for concurrent use.</p>
 *
 * @see Svasq4QueryPrep
 * @see Svasq4SimdKernel
 */
public final class Svasq4QueryState {

    private final float[] qTildeHi;   // pre-scaled query, even dims [halfDim]
    private final float[] qTildeLo;   // pre-scaled query, odd dims  [halfDim]
    private final float constL2Q;     // ‖q‖² − 2·C(q) + 2·nibbleBias (query-side L2 constant)
    private final float dotOffset;    // C(q) − nibbleBias (for dot product reconstruction)
    private final float qNormSq;      // ‖q‖² (stored for diagnostics)

    Svasq4QueryState(float[] qTildeHi, float[] qTildeLo,
                    float constL2Q, float dotOffset, float qNormSq) {
        this.qTildeHi = qTildeHi;
        this.qTildeLo = qTildeLo;
        this.constL2Q = constL2Q;
        this.dotOffset = dotOffset;
        this.qNormSq = qNormSq;
    }

    /**
     * Pre-scaled query coefficients for even-indexed FWHT dimensions (high nibbles).
     *
     * <p>Layout: {@code qTildeHi[k] = qRot[2k] × scale[2k]}, length = paddedDim/2.</p>
     *
     * <p><strong>Do not modify the returned array</strong> — it may be shared.</p>
     *
     * @return deinterleaved high-nibble query array
     */
    public float[] qTildeHi() { return qTildeHi; }

    /**
     * Pre-scaled query coefficients for odd-indexed FWHT dimensions (low nibbles).
     *
     * <p>Layout: {@code qTildeLo[k] = qRot[2k+1] × scale[2k+1]}, length = paddedDim/2.</p>
     *
     * <p><strong>Do not modify the returned array</strong> — it may be shared.</p>
     *
     * @return deinterleaved low-nibble query array
     */
    public float[] qTildeLo() { return qTildeLo; }

    /**
     * Query-side L2 constant incorporating the offset-encoding bias.
     *
     * <p>{@code constL2Q = ‖q‖² − 2·C(q) + 2·nibbleBias}, where
     * {@code nibbleBias = 7 × Σᵢ q̃ᵢ}.</p>
     *
     * <p>The full L2 distance is: {@code L2 = exactNormSq + constL2Q − 2 × dotUnsigned}.</p>
     *
     * @return query-side L2 constant
     */
    public float constL2Q() { return constL2Q; }

    /**
     * Dot-product offset for reconstructing the approximate inner product:
     * {@code approxIP = dotUnsigned + dotOffset}.
     *
     * @return dot product offset
     */
    public float dotOffset() { return dotOffset; }

    /**
     * Exact query L2 norm squared: {@code ‖q‖²}.
     *
     * @return query norm squared
     */
    public float qNormSq() { return qNormSq; }
}
