package com.spectrayan.spector.core.quantization.vasq;
import com.spectrayan.spector.commons.error.SpectorException;

import java.util.Arrays;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Immutable calibration parameters for the VASQ quantizer.
 *
 * <p>Produced by {@link VasqCalibrator#calibrate} from a representative sample corpus.
 * Contains all parameters needed for encoding vectors and preparing query states.</p>
 *
 * <h3>Parameter Semantics</h3>
 * <p>All arrays are indexed over the <em>padded</em> dimension (length = {@link #paddedDim()}),
 * i.e. over the FWHT-rotated space. Statistics for padded dimensions beyond the original
 * dimension are near-zero (the FWHT distributes zero-padded values uniformly).</p>
 *
 * <ul>
 *   <li>{@link #means()} — per-dimension mean in rotated space (μᵢ)</li>
 *   <li>{@link #scales()} — per-dimension dequantization scale (σᵢ = clipSigmas·σᵢ/127)</li>
 *   <li>{@link #invScales()} — per-dimension quantization scale (1/σᵢ, precomputed for encode speed)</li>
 * </ul>
 *
 * <p>Instances are immutable and safe for concurrent use.</p>
 */
public final class VasqParams {

    /** Number of ±1 sign-flip seed to use when no explicit seed is provided. */
    public static final long DEFAULT_SEED = 42L;

    /** Standard VASQ bit width — signed INT8 [-127, 127], 1 byte per dimension. */
    public static final int BIT_WIDTH_8 = 8;

    /** Half-precision VASQ bit width — offset-encoded INT4 [0, 14], nibble-packed. */
    public static final int BIT_WIDTH_4 = 4;

    private final int originalDim;
    private final int paddedDim;
    private final int bitWidth;       // 8 (VASQ-8) or 4 (VASQ-4)
    private final float[] means;      // μᵢ per rotated dim  [paddedDim]
    private final float[] scales;     // scaleᵢ per rotated dim  [paddedDim]
    private final float[] invScales;  // invScaleᵢ per rotated dim  [paddedDim]
    private final VasqFwht fwht;

    /**
     * Package-private constructor for VASQ-8 (INT8) — backward-compatible.
     *
     * <p>Created exclusively by {@link VasqCalibrator}. Defaults to
     * {@link #BIT_WIDTH_8} (signed INT8).</p>
     */
    VasqParams(int originalDim, int paddedDim,
               float[] means, float[] scales, float[] invScales,
               VasqFwht fwht) {
        this(originalDim, paddedDim, means, scales, invScales, fwht, BIT_WIDTH_8);
    }

    /**
     * Package-private constructor with explicit bit width.
     *
     * @param bitWidth {@link #BIT_WIDTH_8} for signed INT8 or {@link #BIT_WIDTH_4} for offset INT4
     */
    VasqParams(int originalDim, int paddedDim,
               float[] means, float[] scales, float[] invScales,
               VasqFwht fwht, int bitWidth) {
        if (bitWidth != BIT_WIDTH_8 && bitWidth != BIT_WIDTH_4) {
            throw new SpectorValidationException(ErrorCode.BIT_WIDTH_INVALID, "4, 8", bitWidth);
        }
        this.originalDim = originalDim;
        this.paddedDim   = paddedDim;
        this.bitWidth    = bitWidth;
        this.means       = means;
        this.scales      = scales;
        this.invScales   = invScales;
        this.fwht        = fwht;
    }

    /**
     * The original (unpadded) vector dimensionality.
     *
     * @return original dimension count
     */
    public int originalDim() { return originalDim; }

    /**
     * The FWHT-padded dimension (next power-of-two ≥ originalDim).
     *
     * @return padded dimension
     */
    public int paddedDim() { return paddedDim; }

    /**
     * The quantization bit width: 8 for VASQ-8 (INT8), 4 for VASQ-4 (INT4).
     *
     * @return bit width (4 or 8)
     */
    public int bitWidth() { return bitWidth; }

    /**
     * Per-dimension means in the rotated space (μᵢ).
     *
     * <p><strong>Do not modify the returned array.</strong></p>
     *
     * @return means array of length {@link #paddedDim()}
     */
    public float[] means() { return means; }

    /**
     * Per-dimension dequantization scales (scaleᵢ = clipSigmas·σᵢ/127).
     *
     * <p>Used in query preparation: {@code q̃ᵢ = q_rot_i × scaleᵢ}.<br>
     * <strong>Do not modify the returned array.</strong></p>
     *
     * @return scales array of length {@link #paddedDim()}
     */
    public float[] scales() { return scales; }

    /**
     * Per-dimension quantization inverse-scales (invScaleᵢ = 127/(clipSigmas·σᵢ)).
     *
     * <p>Used in encoding: {@code zᵢ = round((x_rot_i - μᵢ) × invScaleᵢ)}.<br>
     * Precomputed to avoid division in the encode hot path.<br>
     * <strong>Do not modify the returned array.</strong></p>
     *
     * @return invScales array of length {@link #paddedDim()}
     */
    public float[] invScales() { return invScales; }

    /**
     * The FWHT rotator configured with this calibration's seed.
     *
     * @return FWHT instance
     */
    public VasqFwht fwht() { return fwht; }

    /**
     * Returns the number of bytes required to store one encoded vector in a MemorySegment.
     *
     * <ul>
     *   <li>VASQ-8: {@code 4 (float32 norm) + paddedDim (1 byte per dim)}</li>
     *   <li>VASQ-4: {@code 4 (float32 norm) + paddedDim/2 (nibble-packed, 2 dims per byte)}</li>
     * </ul>
     *
     * @return bytes per vector
     */
    public int bytesPerVector() {
        int codeBytes = (bitWidth == BIT_WIDTH_4) ? paddedDim / 2 : paddedDim;
        return 4 + codeBytes;
    }

    /**
     * Returns the number of bytes used to store the quantized codes (excluding the norm header).
     *
     * @return code bytes per vector
     */
    public int codeBytesPerVector() {
        return (bitWidth == BIT_WIDTH_4) ? paddedDim / 2 : paddedDim;
    }

    @Override
    public String toString() {
        return String.format(
                "VasqParams{originalDim=%d, paddedDim=%d, bitWidth=%d, bytesPerVector=%d}",
                originalDim, paddedDim, bitWidth, bytesPerVector());
    }
}