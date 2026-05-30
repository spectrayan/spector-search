package com.spectrayan.spector.core.quantization;

import java.util.Arrays;

/**
 * Scalar quantizer — maps float32 vectors to int8 (byte) vectors.
 *
 * <p>Uses per-dimension min/max calibration to linearly map each float
 * value to the [0, 255] byte range. This achieves a 4× memory reduction
 * with minimal information loss for typical embedding distributions.</p>
 *
 * <h3>Calibration</h3>
 * <p>Call {@link #calibrate(float[][], int)} with a representative sample
 * of vectors. The quantizer learns per-dimension min/max bounds and
 * computes scales for encoding.</p>
 *
 * <h3>Encoding Formula</h3>
 * <pre>
 *   quantized[i] = clamp(round((value[i] - min[i]) / scale[i]), 0, 255)
 *   scale[i] = (max[i] - min[i]) / 255.0
 * </pre>
 *
 * <h3>Thread Safety</h3>
 * <p>A calibrated quantizer is immutable and safe for concurrent use.</p>
 */
public final class ScalarQuantizer {

    private final int dimensions;
    private final float[] mins;       // per-dimension minimum
    private final float[] maxs;       // per-dimension maximum
    private final float[] scales;     // (max - min) / 255
    private final float[] invScales;  // 255 / (max - min) — for fast encoding

    private ScalarQuantizer(int dimensions, float[] mins, float[] maxs) {
        this.dimensions = dimensions;
        this.mins = mins;
        this.maxs = maxs;
        this.scales = new float[dimensions];
        this.invScales = new float[dimensions];

        for (int i = 0; i < dimensions; i++) {
            float range = maxs[i] - mins[i];
            if (range < 1e-10f) {
                // Near-constant dimension — avoid division by zero
                scales[i] = 1.0f;
                invScales[i] = 0.0f;
            } else {
                scales[i] = range / 255.0f;
                invScales[i] = 255.0f / range;
            }
        }
    }

    /**
     * Calibrates a quantizer from a sample of vectors.
     *
     * <p>Computes per-dimension min and max values from the sample,
     * optionally expanding the range slightly to accommodate future
     * out-of-distribution vectors.</p>
     *
     * @param sampleVectors representative vector sample (at least 100 recommended)
     * @param dimensions    vector dimensionality
     * @return a calibrated quantizer
     * @throws IllegalArgumentException if sample is empty or dimensions mismatch
     */
    public static ScalarQuantizer calibrate(float[][] sampleVectors, int dimensions) {
        if (sampleVectors == null || sampleVectors.length == 0) {
            throw new IllegalArgumentException(com.spectrayan.spector.commons.error.ErrorCode.EMPTY_COLLECTION.format("sampleVectors"));
        }

        float[] mins = new float[dimensions];
        float[] maxs = new float[dimensions];
        Arrays.fill(mins, Float.MAX_VALUE);
        Arrays.fill(maxs, -Float.MAX_VALUE);

        for (float[] vector : sampleVectors) {
            if (vector.length != dimensions) {
                throw new IllegalArgumentException(
                        "Expected " + dimensions + " dims, got " + vector.length);
            }
            for (int d = 0; d < dimensions; d++) {
                if (vector[d] < mins[d]) mins[d] = vector[d];
                if (vector[d] > maxs[d]) maxs[d] = vector[d];
            }
        }

        // Expand range by 5% to handle slight distribution shifts
        for (int d = 0; d < dimensions; d++) {
            float range = maxs[d] - mins[d];
            float margin = range * 0.025f; // 2.5% each side
            mins[d] -= margin;
            maxs[d] += margin;
        }

        return new ScalarQuantizer(dimensions, mins, maxs);
    }

    /**
     * Creates a quantizer with explicit min/max bounds (for deserialization).
     *
     * @param dimensions number of dimensions
     * @param mins       per-dimension minimums
     * @param maxs       per-dimension maximums
     * @return a quantizer with the given bounds
     */
    public static ScalarQuantizer fromBounds(int dimensions, float[] mins, float[] maxs) {
        if (mins.length != dimensions || maxs.length != dimensions) {
            throw new IllegalArgumentException("mins/maxs length must match dimensions");
        }
        return new ScalarQuantizer(dimensions,
                Arrays.copyOf(mins, dimensions),
                Arrays.copyOf(maxs, dimensions));
    }

    /**
     * Encodes a float32 vector to a byte (int8) vector.
     *
     * @param vector the input float vector
     * @return quantized byte array
     */
    public byte[] encode(float[] vector) {
        byte[] result = new byte[dimensions];
        encode(vector, 0, result, 0);
        return result;
    }

    /**
     * Encodes a float32 vector into an existing byte buffer (zero-allocation).
     *
     * @param src       source float array
     * @param srcOffset offset into source
     * @param dst       destination byte array
     * @param dstOffset offset into destination
     */
    public void encode(float[] src, int srcOffset, byte[] dst, int dstOffset) {
        for (int i = 0; i < dimensions; i++) {
            float normalized = (src[srcOffset + i] - mins[i]) * invScales[i];
            int quantized = Math.round(normalized);
            // Clamp to [0, 255] and store as unsigned byte
            dst[dstOffset + i] = (byte) Math.max(0, Math.min(255, quantized));
        }
    }

    /**
     * Decodes a quantized byte vector back to float32.
     *
     * <p>Useful for debugging and exact re-ranking verification.</p>
     *
     * @param quantized the quantized byte array
     * @return reconstructed float array (approximate)
     */
    public float[] decode(byte[] quantized) {
        float[] result = new float[dimensions];
        decode(quantized, 0, result, 0);
        return result;
    }

    /**
     * Decodes quantized bytes into an existing float buffer.
     *
     * @param src       source byte array
     * @param srcOffset offset into source
     * @param dst       destination float array
     * @param dstOffset offset into destination
     */
    public void decode(byte[] src, int srcOffset, float[] dst, int dstOffset) {
        for (int i = 0; i < dimensions; i++) {
            int unsigned = Byte.toUnsignedInt(src[srcOffset + i]);
            dst[dstOffset + i] = unsigned * scales[i] + mins[i];
        }
    }

    /** Returns the number of dimensions. */
    public int dimensions() { return dimensions; }

    /** Returns a copy of the per-dimension minimums. */
    public float[] mins() { return Arrays.copyOf(mins, dimensions); }

    /** Returns a copy of the per-dimension maximums. */
    public float[] maxs() { return Arrays.copyOf(maxs, dimensions); }

    /** Returns a copy of the per-dimension scales. */
    public float[] scales() { return Arrays.copyOf(scales, dimensions); }

    /**
     * Returns the memory saved ratio compared to float32.
     *
     * @return ratio (e.g. 0.25 means 75% savings)
     */
    public float compressionRatio() {
        return 1.0f / 4.0f; // byte / float = 1/4
    }
}
