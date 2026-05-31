package com.spectrayan.spector.core.quantization.vasq;
import com.spectrayan.spector.commons.error.SpectorException;

import java.util.Arrays;
import java.util.Random;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Fast Walsh-Hadamard Transform (FWHT) with random sign flip for variance isotropization.
 *
 * <p>Applies the following pipeline to an input vector of {@code originalDim} floats:</p>
 * <ol>
 *   <li>Zero-pad to {@link #paddedDim()} (next power-of-two ≥ originalDim).</li>
 *   <li>Element-wise multiply by a fixed ±1 sign array (pseudo-random, seeded).</li>
 *   <li>In-place iterative Walsh-Hadamard butterfly transform — O(N log N) additions, zero multiplications.</li>
 *   <li>Normalize by {@code 1/√N} so the transform is orthogonal (preserves L2 norm).</li>
 * </ol>
 *
 * <p>The combined transform is an orthogonal linear map, meaning:</p>
 * <ul>
 *   <li>‖rotate(v)‖ = ‖v‖  (exact, up to float32 rounding)</li>
 *   <li>⟨rotate(a), rotate(b)⟩ ≈ ⟨a, b⟩  (inner products preserved)</li>
 *   <li>The random sign flip ensures the WHT basis is randomized, providing
 *       isotropization guarantees equivalent to a random orthogonal rotation.</li>
 * </ul>
 *
 * <p>Instances are immutable after construction and safe for concurrent use.</p>
 */
public final class VasqFwht {

    private final int originalDim;
    private final int paddedDim;
    private final float[] signFlip;   // ±1f per padded dimension, fixed at construction
    private final float normFactor;   // 1 / sqrt(paddedDim)

    /**
     * Constructs a FWHT rotator for vectors of the given dimensionality.
     *
     * @param originalDim the actual vector dimensionality (e.g. 768)
     * @param seed        random seed for the sign flip array; use a fixed constant
     *                    (e.g. {@code 42L}) for reproducibility across restarts
     */
    public VasqFwht(int originalDim, long seed) {
        if (originalDim < 1) throw new SpectorValidationException(ErrorCode.DIMENSIONS_INVALID, 0);
        this.originalDim = originalDim;
        this.paddedDim = nextPowerOfTwo(originalDim);
        this.normFactor = (float) (1.0 / Math.sqrt(paddedDim));

        Random rng = new Random(seed);
        this.signFlip = new float[paddedDim];
        for (int i = 0; i < paddedDim; i++) {
            signFlip[i] = rng.nextBoolean() ? 1f : -1f;
        }
    }

    /**
     * Rotates a vector, returning a new {@code float[paddedDim]} array.
     *
     * <p>The input {@code src} must have exactly {@code originalDim} elements.
     * The output is zero-padded, sign-flipped, WHT-transformed, and normalized.</p>
     *
     * @param src the input vector (length must equal {@link #originalDim()})
     * @return rotated vector of length {@link #paddedDim()}
     * @throws SpectorValidationException if src.length ≠ originalDim
     */
    public float[] rotate(float[] src) {
        if (src.length != originalDim) {
            throw new SpectorValidationException(ErrorCode.DIMENSIONS_MISMATCH, originalDim, src.length);
        }
        float[] dst = new float[paddedDim]; // zero-filled by JVM
        rotate(src, dst);
        return dst;
    }

    /**
     * Rotates a vector into a pre-allocated buffer (zero-copy variant).
     *
     * <p>The destination {@code dst} must have length ≥ {@link #paddedDim()}.
     * Any existing content beyond {@code originalDim} is treated as zero (padding).</p>
     *
     * @param src the input vector (length must equal {@link #originalDim()})
     * @param dst the output buffer (length must equal {@link #paddedDim()})
     * @throws SpectorValidationException if src.length ≠ originalDim or dst.length ≠ paddedDim
     */
    public void rotate(float[] src, float[] dst) {
        if (src.length != originalDim) {
            throw new SpectorValidationException(ErrorCode.DIMENSIONS_MISMATCH, originalDim, src.length);
        }
        if (dst.length != paddedDim) {
            throw new SpectorValidationException(ErrorCode.DIMENSIONS_MISMATCH, paddedDim, dst.length);
        }

        // 1. Copy src into dst, zero-pad the rest
        System.arraycopy(src, 0, dst, 0, originalDim);
        Arrays.fill(dst, originalDim, paddedDim, 0f);

        // 2. Apply random sign flip
        for (int i = 0; i < paddedDim; i++) {
            dst[i] *= signFlip[i];
        }

        // 3. In-place Walsh-Hadamard butterfly transform
        applyFwht(dst);

        // 4. Normalize: multiply by 1/sqrt(paddedDim) — makes transform orthogonal
        for (int i = 0; i < paddedDim; i++) {
            dst[i] *= normFactor;
        }
    }

    /**
     * The original (unpadded) vector dimensionality passed to the constructor.
     *
     * @return original dimension count
     */
    public int originalDim() {
        return originalDim;
    }

    /**
     * The padded dimensionality used internally (next power-of-two ≥ originalDim).
     *
     * <p>Encoded vectors are {@link #paddedDim()} bytes long (one signed INT8 per padded dim),
     * plus a 4-byte float32 exact-norm header.</p>
     *
     * @return padded dimension count
     */
    public int paddedDim() {
        return paddedDim;
    }

    /**
     * Returns a copy of the sign-flip array for serialization / inspection.
     *
     * @return ±1f array of length {@link #paddedDim()}
     */
    public float[] signFlip() {
        return Arrays.copyOf(signFlip, paddedDim);
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    /**
     * In-place iterative Walsh-Hadamard Transform.
     *
     * <p>The standard Cooley-Tukey-style butterfly decomposition:
     * for each stride {@code h}, process pairs (data[j], data[j+h]) simultaneously.
     * Requires exactly {@code N log₂ N} additions and zero multiplications.</p>
     *
     * @param data array of length equal to a power of two (guaranteed by caller)
     */
    public static void applyFwht(float[] data) {
        int n = data.length;
        for (int h = 1; h < n; h <<= 1) {
            for (int i = 0; i < n; i += h << 1) {
                for (int j = i; j < i + h; j++) {
                    float x = data[j];
                    float y = data[j + h];
                    data[j]     = x + y;
                    data[j + h] = x - y;
                }
            }
        }
    }

    /**
     * Returns the smallest power of two that is ≥ n.
     *
     * @param n positive integer
     * @return next power of two
     */
    public static int nextPowerOfTwo(int n) {
        if (n <= 1) return 1;
        int p = 1;
        while (p < n) p <<= 1;
        return p;
    }
}