package com.spectrayan.spector.core;

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
    SCALAR_INT2;

    /**
     * Returns the number of bits used to represent each vector dimension.
     *
     * @return bits per dimension for this quantization type
     */
    public int bitsPerDimension() {
        return switch (this) {
            case NONE -> 32;
            case SCALAR_INT8 -> 8;
            case SCALAR_INT4 -> 4;
            case SCALAR_INT2 -> 2;
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
            case NONE -> dimensions * 4;
            case SCALAR_INT8 -> dimensions;
            case SCALAR_INT4 -> (dimensions + 1) / 2;
            case SCALAR_INT2 -> (dimensions + 3) / 4;
        };
    }
}
