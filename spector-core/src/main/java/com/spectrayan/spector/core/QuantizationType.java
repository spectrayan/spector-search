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
    SCALAR_INT8
}
