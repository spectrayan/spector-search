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
     * VASQ — Vectorized Affine Scalar Quantization with FWHT rotation.
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
     * <p><strong>Note:</strong> {@link #bytesPerVector(int)} is not supported for VASQ
     * because storage size depends on {@code paddedDim = nextPow2(dimensions)}, not
     * {@code dimensions} alone. Use {@code VasqParams.bytesPerVector()} or
     * {@code VasqEncoder.bytesPerVector()} instead.</p>
     */
    VASQ,

    /**
     * VASQ-4 — Vectorized Affine Scalar Quantization at INT4 bit width.
     *
     * <p>Same FWHT rotation pipeline as {@link #VASQ} but quantizes to offset-encoded
     * INT4 [0, 14] and nibble-packs two values per byte, achieving <b>2× additional
     * compression</b> over VASQ-8 (approximately 6–8× vs float32).</p>
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
     * <p><strong>Note:</strong> {@link #bytesPerVector(int)} is not supported for VASQ_4.
     * Use {@code VasqParams.bytesPerVector()} or {@code Vasq4Encoder.bytesPerVector()} instead.</p>
     */
    VASQ_4;

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
            // VASQ uses 8 bits per padded dimension; paddedDim ≥ dimensions
            case VASQ        -> 8;
            // VASQ_4 uses 4 bits per padded dimension, nibble-packed
            case VASQ_4      -> 4;
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
            // VASQ storage size = 4 + nextPow2(dimensions), not a simple function of dimensions.
            // Use VasqParams.bytesPerVector() or VasqEncoder.bytesPerVector() instead.
            case VASQ -> throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID,
                            "bytesPerVector", "VASQ depends on paddedDim. Use VasqEncoder.bytesPerVector()");
            case VASQ_4 -> throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID,
                            "bytesPerVector", "VASQ_4 depends on paddedDim. Use Vasq4Encoder.bytesPerVector()");
        };
    }
}