package com.spectrayan.spector.core.quantization.vasq;
import com.spectrayan.spector.commons.error.SpectorException;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * VASQ-4 encoder — FWHT rotation + offset-encoded INT4 quantization with nibble packing.
 *
 * <h3>Encoding pipeline</h3>
 * <ol>
 *   <li>Compute exact L2 norm² of the original vector.</li>
 *   <li>FWHT-rotate the vector (sign-flip → butterfly → normalize).</li>
 *   <li>Affine-quantize each rotated dimension: {@code z = round((x - μ) × invScale)}</li>
 *   <li>Clamp to [-7, 7] and offset-encode to [0, 14]: {@code u = z + 7}</li>
 *   <li>Nibble-pack: two unsigned 4-bit values per byte (high nibble first).</li>
 *   <li>Write to off-heap {@link MemorySegment}: 4-byte float32 norm header + nibble-packed codes.</li>
 * </ol>
 *
 * <h3>Memory layout (per vector)</h3>
 * <pre>
 *   [float32 exactNormSq (4 bytes)] [nibble-packed codes (paddedDim/2 bytes)]
 *   Total: 4 + paddedDim/2 bytes per vector
 * </pre>
 *
 * <h3>Thread safety</h3>
 * <p>Instances are immutable after construction. Per-thread scratch buffers are managed
 * via {@link ThreadLocal}, making concurrent encoding safe with zero heap allocation
 * on the hot path.</p>
 *
 * @see VasqEncoder
 * @see Vasq4SimdKernel
 */
public final class Vasq4Encoder {

    /** Offset applied to signed quantized values [-7, 7] → unsigned [0, 14]. */
    static final int OFFSET = 7;

    private final VasqParams params;
    private final int paddedDim;
    private final int bytesPerVector;

    /**
     * Per-thread scratch buffer for the FWHT rotation output.
     * Avoids allocating a new {@code float[paddedDim]} on every encode call.
     */
    private final ThreadLocal<float[]> rotScratch;

    /**
     * Creates a VASQ-4 encoder from pre-calibrated 4-bit parameters.
     *
     * @param params calibrated {@link VasqParams} with {@link VasqParams#BIT_WIDTH_4}
     * @throws SpectorValidationException if params.bitWidth() is not 4
     */
    public Vasq4Encoder(VasqParams params) {
        if (params == null) throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "params");
        if (params.bitWidth() != VasqParams.BIT_WIDTH_4) {
            throw new SpectorValidationException(ErrorCode.BIT_WIDTH_INVALID, "4", params.bitWidth());
        }
        this.params = params;
        this.paddedDim = params.paddedDim();
        this.bytesPerVector = params.bytesPerVector();
        this.rotScratch = ThreadLocal.withInitial(() -> new float[paddedDim]);
    }

    /**
     * Encodes a float32 vector directly into an off-heap {@link MemorySegment}.
     *
     * <p><b>Zero heap allocation</b> on the hot path — uses thread-local scratch
     * for the FWHT rotation, and writes nibble-packed codes directly into the segment.</p>
     *
     * @param vector  the original float32 vector (length = originalDim)
     * @param segment off-heap memory segment to write into
     * @param offset  byte offset within the segment for this vector's storage
     * @throws SpectorValidationException if vector.length ≠ originalDim
     */
    public void encode(float[] vector, MemorySegment segment, long offset) {
        int originalDim = params.originalDim();
        if (vector.length != originalDim) {
            throw new SpectorValidationException(ErrorCode.DIMENSIONS_MISMATCH, originalDim, vector.length);
        }

        float[] means     = params.means();
        float[] invScales = params.invScales();

        // 1. Compute exact L2 norm² (double accumulator for precision)
        double normSqAcc = 0.0;
        for (float v : vector) normSqAcc += (double) v * v;
        segment.set(ValueLayout.JAVA_FLOAT, offset, (float) normSqAcc);

        // 2. FWHT rotate into thread-local scratch (zero allocation)
        float[] rotated = rotScratch.get();
        params.fwht().rotate(vector, rotated);

        // 3. Quantize → clamp → offset-encode → nibble-pack → write
        long codeOffset = offset + 4L;
        int halfDim = paddedDim / 2;

        for (int k = 0; k < halfDim; k++) {
            int d0 = 2 * k;       // even-indexed dimension (high nibble)
            int d1 = 2 * k + 1;   // odd-indexed dimension  (low nibble)

            // Affine quantize: z = round((x - μ) × invScale)
            int z0 = Math.round((rotated[d0] - means[d0]) * invScales[d0]);
            int z1 = Math.round((rotated[d1] - means[d1]) * invScales[d1]);

            // Clamp to [-7, 7]
            z0 = Math.clamp(z0, -OFFSET, OFFSET);
            z1 = Math.clamp(z1, -OFFSET, OFFSET);

            // Offset-encode to [0, 14]
            int u0 = z0 + OFFSET;
            int u1 = z1 + OFFSET;

            // Nibble-pack: high nibble = u0, low nibble = u1
            byte packed = (byte) ((u0 << 4) | (u1 & 0x0F));
            segment.set(ValueLayout.JAVA_BYTE, codeOffset + k, packed);
        }
    }

    /**
     * Decodes an approximate float32 vector from the off-heap segment.
     *
     * <p>Reads nibble-packed codes, reverse offset-encodes, and applies the
     * affine reconstruction: {@code x̂ᵢ ≈ zᵢ × scaleᵢ + μᵢ}.</p>
     *
     * <p>Only the first {@code dimensions} values are returned (padded dimensions excluded).</p>
     *
     * @param segment    off-heap segment containing the encoded vector
     * @param offset     byte offset of the vector's norm header
     * @param dimensions number of dimensions to reconstruct (typically originalDim)
     * @return approximate float32 vector of length {@code dimensions}
     */
    public float[] decode(MemorySegment segment, long offset, int dimensions) {
        float[] scales = params.scales();
        float[] means  = params.means();
        float[] result = new float[dimensions];

        long codeOffset = offset + 4L;

        for (int d = 0; d < dimensions; d++) {
            int k = d / 2;
            byte packed = segment.get(ValueLayout.JAVA_BYTE, codeOffset + k);

            // Extract nibble (high for even d, low for odd d)
            int u = (d % 2 == 0) ? ((packed >>> 4) & 0x0F) : (packed & 0x0F);

            // Reverse offset → signed value
            int z = u - OFFSET;

            // Affine reconstruction
            result[d] = z * scales[d] + means[d];
        }
        return result;
    }

    /**
     * Returns the calibration parameters backing this encoder.
     *
     * @return VASQ-4 params (bitWidth=4)
     */
    public VasqParams params() { return params; }

    /**
     * Returns the number of bytes per encoded vector (4-byte header + paddedDim/2 code bytes).
     *
     * @return bytes per vector
     */
    public int bytesPerVector() { return bytesPerVector; }
}