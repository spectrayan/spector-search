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
 * Immutable, precomputed query context for SVASQ asymmetric distance computation.
 *
 * <p>Created once per query by {@link SvasqQueryPrep#prepare} and then reused for every
 * candidate distance evaluation during HNSW/IVF graph traversal. Doing this once per
 * query rather than per candidate is the core efficiency win of the asymmetric approach.</p>
 *
 * <h3>Contents</h3>
 * <ul>
 *   <li><strong>qTilde</strong> ({@code q̃ᵢ = q_rot_i × scaleᵢ}) — the pre-scaled query
 *       coefficients. The SIMD hot loop computes {@code Σ q̃ᵢ × zᵢ} directly.</li>
 *   <li><strong>constL2Q</strong> ({@code ‖q‖² - 2·C(q)}) — the query-side L2 constant.
 *       The full L2 distance expands to:
 *       {@code L2 = exactNormSq + constL2Q - 2 × dot(qTilde, z)}.
 *       Sign: positive when C(q) is negative (typical for zero-mean embeddings).</li>
 *   <li><strong>dotOffset</strong> ({@code C(q) = Σ q_rot_i × μᵢ}) — the query-side
 *       mean correction, stored separately so callers can reconstruct the approximate
 *       inner product as {@code dot(qTilde, z) + dotOffset}.</li>
 * </ul>
 *
 * <p>Instances are immutable and safe for concurrent use across virtual threads.</p>
 */
public final class SvasqQueryState {

    private final float[] qTilde;    // q̃ᵢ = q_rot_i × scaleᵢ  [paddedDim]
    private final float constL2Q;    // ‖q‖² - 2·C(q)  (query-side L2 constant)
    private final float dotOffset;   // C(q) = Σ q_rot_i × μᵢ
    private final float qNormSq;     // ‖q‖² (stored for diagnostics)

    SvasqQueryState(float[] qTilde, float constL2Q, float dotOffset, float qNormSq) {
        this.qTilde    = qTilde;
        this.constL2Q  = constL2Q;
        this.dotOffset = dotOffset;
        this.qNormSq   = qNormSq;
    }

    /**
     * Pre-scaled query vector ({@code q̃ᵢ = q_rot_i × scaleᵢ}).
     *
     * <p><strong>Do not modify the returned array</strong> — it is shared across calls.</p>
     *
     * @return qTilde array of length {@code paddedDim}
     */
    public float[] qTilde() { return qTilde; }

    /**
     * Query-side L2 constant: {@code ‖q‖² - 2·C(q)}.
     *
     * <p>The full approximate L2 distance formula is:
     * {@code L2 ≈ exactNormSq + constL2Q - 2 × Σ(q̃ᵢ × zᵢ)}</p>
     *
     * @return query-side L2 constant
     */
    public float constL2Q() { return constL2Q; }

    /**
     * Mean-correction offset for inner product: {@code C(q) = Σ q_rot_i × μᵢ}.
     *
     * <p>Approximate inner product is: {@code Σ(q̃ᵢ × zᵢ) + dotOffset()}</p>
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
