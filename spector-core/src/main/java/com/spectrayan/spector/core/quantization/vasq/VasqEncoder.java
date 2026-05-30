package com.spectrayan.spector.core.quantization.vasq;
import com.spectrayan.spector.commons.error.SpectorException;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Encodes float32 vectors into the VASQ off-heap binary format.
 *
 * <h3>Memory Layout (per vector)</h3>
 * <pre>
 *   ┌─────────────────────┬──────────────────────────────────────────┐
 *   │ float32 exactNormSq │ INT8[paddedDim] signed quantized codes   │
 *   │ (4 bytes, offset 0) │ (paddedDim bytes, offset 4)              │
 *   └─────────────────────┴──────────────────────────────────────────┘
 * </pre>
 *
 * <h3>Encoding Steps</h3>
 * <ol>
 *   <li>Compute {@code exactNormSq = ‖x‖²} on the original float32 vector.</li>
 *   <li>Rotate: sign-flip, FWHT, normalize → {@code x_rot ∈ ℝ^paddedDim}.</li>
 *   <li>Quantize each dimension: {@code zᵢ = clip(round((x_rot_i - μᵢ) × invScaleᵢ), -127, 127)}.</li>
 *   <li>Write the 4-byte norm header and {@code paddedDim} signed byte codes.</li>
 * </ol>
 *
 * <h3>Allocation Budget</h3>
 * <p>The rotate step requires a {@code float[paddedDim]} scratch buffer. This encoder
 * uses a per-instance {@link ThreadLocal} so the buffer is allocated once per thread
 * and reused across all subsequent encode calls — eliminating the hot-path allocation
 * that previously occurred on every {@link #encode(float[], MemorySegment, long)} call.</p>
 *
 * <p>Instances are immutable after construction and safe for concurrent use
 * (each thread gets its own scratch buffer via ThreadLocal).</p>
 */
public final class VasqEncoder {

    private final VasqParams params;

    /**
     * Per-thread scratch buffer for the FWHT rotate step.
     * Allocated once per thread on first use; sized to {@code paddedDim}.
     */
    private final ThreadLocal<float[]> rotateScratch;

    /**
     * Creates an encoder backed by the given calibration parameters.
     *
     * @param params calibrated VASQ parameters (non-null)
     */
    public VasqEncoder(VasqParams params) {
        if (params == null) throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "params");
        this.params = params;
        final int paddedDim = params.paddedDim();
        this.rotateScratch  = ThreadLocal.withInitial(() -> new float[paddedDim]);
    }

    /**
     * Encodes a float32 vector, writing the result directly into an off-heap {@link MemorySegment}.
     *
     * <p>The segment must have at least {@code offset + bytesPerVector()} bytes available.</p>
     *
     * <p>Uses a thread-local scratch buffer for the FWHT rotate step — zero per-call
     * heap allocations on the hot path.</p>
     *
     * @param vector  the float32 input vector (length must equal {@link VasqParams#originalDim()})
     * @param segment the off-heap memory segment to write into
     * @param offset  byte offset within the segment for this vector's header
     * @throws SpectorValidationException if vector.length ≠ originalDim
     */
    public void encode(float[] vector, MemorySegment segment, long offset) {
        int originalDim  = params.originalDim();
        int paddedDim    = params.paddedDim();
        float[] means    = params.means();
        float[] invScales = params.invScales();

        if (vector.length != originalDim) {
            throw new SpectorValidationException(ErrorCode.DIMENSIONS_MISMATCH, originalDim, vector.length);
        }

        // 1. Exact L2 norm squared (pre-rotation; rotation is orthogonal so ‖x‖=‖Rx‖)
        double normSqAcc = 0.0;
        for (float v : vector) normSqAcc += (double) v * v;
        segment.set(ValueLayout.JAVA_FLOAT, offset, (float) normSqAcc);

        // 2. Rotate into thread-local scratch — zero allocation per call
        float[] rotated = rotateScratch.get();
        params.fwht().rotate(vector, rotated);

        // 3. Quantize to signed INT8 [-127, 127] and write into segment
        for (int i = 0; i < paddedDim; i++) {
            int q = Math.round((rotated[i] - means[i]) * invScales[i]);
            // Clamp to [-127, 127] — symmetric range avoids INT8_MIN=-128 asymmetry
            q = q < -127 ? -127 : (q > 127 ? 127 : q);
            segment.set(ValueLayout.JAVA_BYTE, offset + 4L + i, (byte) q);
        }
    }

    /**
     * Returns the number of bytes per encoded vector:
     * {@code 4 (float32 norm header) + paddedDim (signed INT8 codes)}.
     *
     * @return bytes per vector
     */
    public int bytesPerVector() {
        return params.bytesPerVector();
    }

    /**
     * Returns the calibration parameters backing this encoder.
     *
     * @return VASQ params
     */
    public VasqParams params() {
        return params;
    }
}