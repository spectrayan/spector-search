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
package com.spectrayan.spector.core.quantization;
import com.spectrayan.spector.commons.error.SpectorException;

import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Supported vector quantization strategies.
 *
 * <p>Quantization compresses float32 vectors into lower-precision formats
 * to reduce memory usage while preserving search quality.</p>
 */
public enum QuantizationType {

    /** No quantization — full float32 precision. */
    NONE,

    /**
     * Scalar quantization to int8 (SQ8).
     *
     * <p>Each float32 dimension is mapped to a single byte [0, 255] using
     * per-dimension min/max calibration. Reduces memory by 4× with
     * ~99%+ recall when combined with asymmetric distance computation.</p>
     */
    SCALAR_INT8,

    /**
     * Scalar quantization to int4 (SQ4).
     *
     * <p>Each float32 dimension is mapped to a 4-bit value [0, 15] using
     * non-uniform (quantile-based) calibration. Two values are packed per byte
     * (nibble packing), achieving 8× compression vs float32.</p>
     */
    SCALAR_INT4,

    /**
     * Scalar quantization to int2 (SQ2).
     *
     * <p>Each float32 dimension is mapped to a 2-bit value [0, 3] using
     * non-uniform (quantile-based) calibration. Four values are packed per byte
     * (crumb packing), achieving 16× compression vs float32.</p>
     */
    SCALAR_INT2,

    /**
     * TurboQuant — random rotation + optimal scalar quantization (4-bit).
     *
     * <p>Applies a fixed random orthogonal rotation to isotropize the vector
     * distribution, then quantizes each rotated coordinate with an optimal
     * scalar quantizer at 4 bits. Achieves 8× compression with ~97%+ recall,
     * outperforming standard SQ4 due to the rotation making coordinates
     * near-independent and uniformly distributed.</p>
     *
     * <p>Based on TurboQuant (Google Research, 2025).</p>
     */
    TURBO_QUANT,

    /**
     * SVASQ — Vectorized Affine Scalar Quantization with FWHT rotation.
     *
     * <p>Combines Fast Walsh-Hadamard Transform (FWHT) rotation with random sign
     * flips to isotropize the vector distribution, then applies per-dimension
     * percentile-clipped affine quantization to signed INT8 [-127, 127].</p>
     *
     * <h3>Memory Layout (per vector)</h3>
     * <pre>
     *   [4 bytes: float32 exact L2 norm²] [paddedDim bytes: signed INT8 codes]
     * </pre>
     * where {@code paddedDim} is the next power-of-two ≥ dimensions.
     *
     * <h3>Key Properties</h3>
     * <ul>
     *   <li>Asymmetric distance computation — query stays in float32, corpus in INT8.</li>
     *   <li>Exact-norm L2 header eliminates quantization error in L2 ranking.</li>
     *   <li>FWHT rotation is O(D log D) with zero multiplications (vs O(D²) for dense rotation).</li>
     *   <li>Panama SIMD kernel: {@code ByteVector.castShape → FMA → reduceLanes} for maximum throughput.</li>
     *   <li>Zero-padding to power-of-2 guarantees no SIMD tail loop.</li>
     * </ul>
     *
     * <p><strong>Note:</strong> {@link #bytesPerVector(int)} is not supported for SVASQ
     * because storage size depends on {@code paddedDim = nextPow2(dimensions)}, not
     * {@code dimensions} alone. Use {@code SvasqParams.bytesPerVector()} or
     * {@code SvasqEncoder.bytesPerVector()} instead.</p>
     */
    SVASQ,

    /**
     * SVASQ-4 — Vectorized Affine Scalar Quantization at INT4 bit width.
     *
     * <p>Same FWHT rotation pipeline as {@link #SVASQ} but quantizes to offset-encoded
     * INT4 [0, 14] and nibble-packs two values per byte, achieving <b>2× additional
     * compression</b> over SVASQ-8 (approximately 6–8× vs float32).</p>
     *
     * <h3>Memory Layout (per vector)</h3>
     * <pre>
     *   [4 bytes: float32 exact L2 norm²] [paddedDim/2 bytes: nibble-packed INT4 codes]
     * </pre>
     *
     * <h3>Key Properties</h3>
     * <ul>
     *   <li>Offset encoding: signed [-7, 7] → unsigned [0, 14] for SIMD-friendly nibble ops.</li>
     *   <li>Tighter clipping (2.5σ vs 3.0σ) to maximize use of 15 quantization levels.</li>
     *   <li>Deinterleaved query layout enables ILP in the SIMD kernel.</li>
     *   <li>With 3× oversampling rescore: ~97–99% recall@10.</li>
     * </ul>
     *
     * <p><strong>Note:</strong> {@link #bytesPerVector(int)} is not supported for SVASQ_4.
     * Use {@code SvasqParams.bytesPerVector()} or {@code Svasq4Encoder.bytesPerVector()} instead.</p>
     */
    SVASQ_4;

    /**
     * Returns the number of bits used to represent each vector dimension.
     *
     * @return bits per dimension for this quantization type
     */
    public int bitsPerDimension() {
        return switch (this) {
            case NONE        -> 32;
            case SCALAR_INT8 -> 8;
            case SCALAR_INT4, TURBO_QUANT -> 4;
            case SCALAR_INT2 -> 2;
            // SVASQ uses 8 bits per padded dimension; paddedDim ≥ dimensions
            case SVASQ        -> 8;
            // SVASQ_4 uses 4 bits per padded dimension, nibble-packed
            case SVASQ_4      -> 4;
        };
    }

    /**
     * Returns the number of discrete quantization levels available.
     *
     * <p>This equals 2^bitsPerDimension — for example, INT8 has 256 levels,
     * INT4 has 16 levels, and INT2 has 4 levels.</p>
     *
     * @return number of quantization levels
     */
    public int levels() {
        return 1 << bitsPerDimension();
    }

    /**
     * Returns the number of bytes required to store a single quantized vector
     * of the given dimensionality.
     *
     * <ul>
     *   <li>NONE: dimensions × 4 (full float32)</li>
     *   <li>SCALAR_INT8: dimensions (one byte per dimension)</li>
     *   <li>SCALAR_INT4: ceil(dimensions / 2) (nibble packing, 2 values per byte)</li>
     *   <li>SCALAR_INT2: ceil(dimensions / 4) (crumb packing, 4 values per byte)</li>
     * </ul>
     *
     * @param dimensions the vector dimensionality
     * @return bytes required per vector
     */
    public int bytesPerVector(int dimensions) {
        return switch (this) {
            case NONE        -> dimensions * 4;
            case SCALAR_INT8 -> dimensions;
            case SCALAR_INT4, TURBO_QUANT -> (dimensions + 1) / 2;
            case SCALAR_INT2 -> (dimensions + 3) / 4;
            // SVASQ storage size = 4 + nextPow2(dimensions), not a simple function of dimensions.
            // Use SvasqParams.bytesPerVector() or SvasqEncoder.bytesPerVector() instead.
            case SVASQ -> throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID,
                            "bytesPerVector", "SVASQ depends on paddedDim. Use SvasqEncoder.bytesPerVector()");
            case SVASQ_4 -> throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID,
                            "bytesPerVector", "SVASQ_4 depends on paddedDim. Use Svasq4Encoder.bytesPerVector()");
        };
    }
}