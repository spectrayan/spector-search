package com.spectrayan.spector.core.quantization.vasq;
import com.spectrayan.spector.commons.error.SpectorException;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Calibrates VASQ quantization parameters from a representative sample corpus.
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>Rotate all sample vectors using {@link VasqFwht} (sign-flip + FWHT + normalize).</li>
 *   <li>For each rotated dimension {@code j}:
 *     <ul>
 *       <li>Compute the {@code clip_percentile}-th and {@code (1-clip_percentile)}-th
 *           percentiles as clipping bounds.</li>
 *       <li>Compute the mean and standard deviation of values within the clipped range.</li>
 *       <li>Derive {@code scaleᵢ = CLIP_SIGMAS × σᵢ / 127} and
 *           {@code invScaleᵢ = 127 / (CLIP_SIGMAS × σᵢ)}.</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>This calibration is equivalent to the whitepaper's Algorithm 1 (VASQ-Calibrate)
 * with two key differences: (a) calibration is done in the <em>rotated</em> space
 * (fixing the quant.md bug), and (b) the scale is derived from the clipped std dev
 * rather than raw min/max (giving better accuracy for Gaussian/sub-Gaussian embeddings).</p>
 *
 * <h3>Overloads</h3>
 * <ul>
 *   <li>{@link #calibrate(List, int, long)} — from a List of float[] vectors</li>
 *   <li>{@link #calibrate(float[][], int, int, long)} — from a float[][] array slice
 *       (avoids {@code Arrays.copyOf} + List wrapper)</li>
 *   <li>{@link #calibrate(float[], int, int, long)} — from a flat flattened buffer
 *       (used by SpectorShard to pass its contiguous flatData directly)</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * <p>Stateless — all methods are static and safe for concurrent use.</p>
 */
public final class VasqCalibrator {

    /** Percentile clipping boundary: clip at 0.1th and 99.9th percentiles. */
    static final float CLIP_PERCENTILE = 0.001f;

    /**
     * Number of standard deviations the quantization range covers.
     * {@code 3.0} covers 99.7% of a Gaussian distribution within [-127, 127].
     */
    static final float CLIP_SIGMAS = 3.0f;

    /**
     * Tighter clipping for VASQ-4 (INT4, 15 levels).
     * {@code 2.5} reduces outlier exposure when only 15 quantization levels are available.
     */
    static final float CLIP_SIGMAS_4BIT = 2.5f;

    /** Maximum sample vectors used for calibration. */
    static final int MAX_SAMPLE_SIZE = 10_000;

    /** Maximum quantization level for INT8 [-127, 127]. */
    private static final int MAX_LEVEL_INT8 = 127;

    /** Maximum quantization level for INT4 [0, 14] offset-encoded (signed range [-7, 7]). */
    private static final int MAX_LEVEL_INT4 = 7;

    /** Minimum allowed std dev to prevent division by zero on zero-variance dims. */
    private static final float MIN_STD = 1e-6f;

    private VasqCalibrator() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Calibrates VASQ parameters from a list of sample vectors.
     *
     * <p>The sample is capped at {@link #MAX_SAMPLE_SIZE} vectors. If the list is larger,
     * vectors are drawn uniformly at random (seeded for reproducibility).</p>
     *
     * @param sampleVectors representative sample (at least 100 vectors recommended)
     * @param originalDim   vector dimensionality
     * @param seed          FWHT sign-flip seed; must match the seed used at encode time
     * @return calibrated {@link VasqParams}
     * @throws SpectorValidationException if sampleVectors is empty or dimensions don't match
     */
    public static VasqParams calibrate(List<float[]> sampleVectors,
                                        int originalDim, long seed) {
        if (sampleVectors == null || sampleVectors.isEmpty()) {
            throw new SpectorValidationException(ErrorCode.EMPTY_COLLECTION, "sampleVectors");
        }
        // Subsample if needed
        List<float[]> sample = subsampleList(sampleVectors, MAX_SAMPLE_SIZE, seed);
        int n = sample.size();
        for (float[] v : sample) {
            if (v.length != originalDim) {
                throw new SpectorValidationException(ErrorCode.DIMENSIONS_MISMATCH, originalDim, v.length);
            }
        }
        VasqFwht fwht = new VasqFwht(originalDim, seed);
        int paddedDim  = fwht.paddedDim();

        // Rotate all samples — one float[] per vector (unavoidable for column-wise stats)
        float[][] rotated = new float[n][paddedDim];
        float[] tempVec   = new float[originalDim]; // reused per vector
        for (int i = 0; i < n; i++) {
            float[] src = sample.get(i);
            System.arraycopy(src, 0, tempVec, 0, originalDim);
            fwht.rotate(tempVec, rotated[i]);
        }
        return computeParams(rotated, n, paddedDim, originalDim, fwht);
    }

    /**
     * Convenience overload using {@link VasqParams#DEFAULT_SEED}.
     */
    public static VasqParams calibrate(List<float[]> sampleVectors, int originalDim) {
        return calibrate(sampleVectors, originalDim, VasqParams.DEFAULT_SEED);
    }

    /**
     * Calibrates VASQ parameters from a {@code float[][]} array, using only the
     * first {@code n} rows. Avoids the {@code Arrays.copyOf} + {@code List} wrapper
     * required by the List overload.
     *
     * <p>Used by {@link com.spectrayan.spector.index.QuantizedHnswIndex#calibrateVasq()}
     * to pass its {@code calibrationBuffer[0..calibrationCount-1]} directly.</p>
     *
     * @param samples     array of sample vectors (only indices [0, n) are used)
     * @param n           number of valid vectors in {@code samples}
     * @param originalDim vector dimensionality
     * @param seed        FWHT sign-flip seed
     * @return calibrated {@link VasqParams}
     */
    public static VasqParams calibrate(float[][] samples, int n,
                                        int originalDim, long seed) {
        if (samples == null || n <= 0) {
            throw new SpectorValidationException(ErrorCode.EMPTY_COLLECTION, "samples");
        }
        int useN = Math.min(n, MAX_SAMPLE_SIZE);
        // Subsample if needed — Fisher-Yates partial shuffle on the indices
        int[] indices = subsampleIndices(n, useN, seed);

        VasqFwht fwht = new VasqFwht(originalDim, seed);
        int paddedDim  = fwht.paddedDim();

        float[][] rotated = new float[useN][paddedDim];
        float[] tempVec   = new float[originalDim];
        for (int i = 0; i < useN; i++) {
            float[] src = samples[indices[i]];
            if (src.length != originalDim) {
                throw new SpectorValidationException(ErrorCode.DIMENSIONS_MISMATCH, originalDim, src.length);
            }
            System.arraycopy(src, 0, tempVec, 0, originalDim);
            fwht.rotate(tempVec, rotated[i]);
        }
        return computeParams(rotated, useN, paddedDim, originalDim, fwht);
    }

    /**
     * Convenience overload using {@link VasqParams#DEFAULT_SEED}.
     */
    public static VasqParams calibrate(float[][] samples, int n, int originalDim) {
        return calibrate(samples, n, originalDim, VasqParams.DEFAULT_SEED);
    }

    /**
     * Calibrates VASQ parameters from a <em>flat</em> contiguous float buffer.
     *
     * <p>The buffer stores vectors consecutively: vector {@code i} occupies
     * {@code flatData[i × originalDim .. (i+1) × originalDim - 1]}. This is the
     * layout used by {@link com.spectrayan.spector.index.spectrum.SpectorShard}'s
     * flat residual store, allowing calibration without copying into {@code float[][]}.</p>
     *
     * @param flatData    contiguous float buffer (length ≥ {@code n × originalDim})
     * @param n           number of vectors stored in {@code flatData}
     * @param originalDim per-vector dimensionality
     * @param seed        FWHT sign-flip seed
     * @return calibrated {@link VasqParams}
     */
    public static VasqParams calibrate(float[] flatData, int n,
                                        int originalDim, long seed) {
        if (flatData == null || n <= 0 || originalDim <= 0) {
            throw new SpectorValidationException(ErrorCode.EMPTY_COLLECTION, "flatData");
        }
        int useN    = Math.min(n, MAX_SAMPLE_SIZE);
        int[] idxs  = subsampleIndices(n, useN, seed);

        VasqFwht fwht = new VasqFwht(originalDim, seed);
        int paddedDim  = fwht.paddedDim();

        float[][] rotated = new float[useN][paddedDim];
        float[] tempVec   = new float[originalDim]; // one temp vector, reused per sample
        for (int i = 0; i < useN; i++) {
            int base = idxs[i] * originalDim;
            System.arraycopy(flatData, base, tempVec, 0, originalDim);
            fwht.rotate(tempVec, rotated[i]);
        }
        return computeParams(rotated, useN, paddedDim, originalDim, fwht);
    }

    /**
     * Convenience overload using {@link VasqParams#DEFAULT_SEED}.
     */
    public static VasqParams calibrate(float[] flatData, int n, int originalDim) {
        return calibrate(flatData, n, originalDim, VasqParams.DEFAULT_SEED);
    }

    // ── VASQ-4 (INT4) calibration API ────────────────────────────────────────

    /**
     * Calibrates VASQ-4 (INT4) parameters from a list of sample vectors.
     *
     * <p>Produces {@link VasqParams} with {@link VasqParams#BIT_WIDTH_4}.
     * Scales are computed for signed range [-7, 7] with tighter clipping
     * ({@link #CLIP_SIGMAS_4BIT}) to maximize use of the 15 available levels.</p>
     *
     * @param sampleVectors representative sample
     * @param originalDim   vector dimensionality
     * @param seed          FWHT sign-flip seed
     * @return calibrated {@link VasqParams} with bitWidth=4
     */
    public static VasqParams calibrate4bit(List<float[]> sampleVectors,
                                            int originalDim, long seed) {
        if (sampleVectors == null || sampleVectors.isEmpty()) {
            throw new SpectorValidationException(ErrorCode.EMPTY_COLLECTION, "sampleVectors");
        }
        List<float[]> sample = subsampleList(sampleVectors, MAX_SAMPLE_SIZE, seed);
        int n = sample.size();
        for (float[] v : sample) {
            if (v.length != originalDim) {
                throw new SpectorValidationException(ErrorCode.DIMENSIONS_MISMATCH, originalDim, v.length);
            }
        }
        VasqFwht fwht  = new VasqFwht(originalDim, seed);
        int paddedDim  = fwht.paddedDim();

        float[][] rotated = new float[n][paddedDim];
        float[] tempVec   = new float[originalDim];
        for (int i = 0; i < n; i++) {
            System.arraycopy(sample.get(i), 0, tempVec, 0, originalDim);
            fwht.rotate(tempVec, rotated[i]);
        }
        return computeParams(rotated, n, paddedDim, originalDim, fwht,
                MAX_LEVEL_INT4, CLIP_SIGMAS_4BIT, VasqParams.BIT_WIDTH_4);
    }

    /** Convenience overload using {@link VasqParams#DEFAULT_SEED}. */
    public static VasqParams calibrate4bit(List<float[]> sampleVectors, int originalDim) {
        return calibrate4bit(sampleVectors, originalDim, VasqParams.DEFAULT_SEED);
    }

    /**
     * Calibrates VASQ-4 (INT4) parameters from a {@code float[][]} array.
     *
     * @param samples     array of sample vectors (only indices [0, n) are used)
     * @param n           number of valid vectors
     * @param originalDim vector dimensionality
     * @param seed        FWHT sign-flip seed
     * @return calibrated {@link VasqParams} with bitWidth=4
     */
    public static VasqParams calibrate4bit(float[][] samples, int n,
                                            int originalDim, long seed) {
        if (samples == null || n <= 0) {
            throw new SpectorValidationException(ErrorCode.EMPTY_COLLECTION, "samples");
        }
        int useN    = Math.min(n, MAX_SAMPLE_SIZE);
        int[] idxs  = subsampleIndices(n, useN, seed);

        VasqFwht fwht  = new VasqFwht(originalDim, seed);
        int paddedDim  = fwht.paddedDim();

        float[][] rotated = new float[useN][paddedDim];
        float[] tempVec   = new float[originalDim];
        for (int i = 0; i < useN; i++) {
            float[] src = samples[idxs[i]];
            if (src.length != originalDim) {
                throw new SpectorValidationException(ErrorCode.DIMENSIONS_MISMATCH, originalDim, src.length);
            }
            System.arraycopy(src, 0, tempVec, 0, originalDim);
            fwht.rotate(tempVec, rotated[i]);
        }
        return computeParams(rotated, useN, paddedDim, originalDim, fwht,
                MAX_LEVEL_INT4, CLIP_SIGMAS_4BIT, VasqParams.BIT_WIDTH_4);
    }

    /** Convenience overload using {@link VasqParams#DEFAULT_SEED}. */
    public static VasqParams calibrate4bit(float[][] samples, int n, int originalDim) {
        return calibrate4bit(samples, n, originalDim, VasqParams.DEFAULT_SEED);
    }

    /**
     * Calibrates VASQ-4 parameters from a flat contiguous float buffer.
     *
     * @see #calibrate(float[], int, int, long)
     */
    public static VasqParams calibrate4bit(float[] flatData, int n,
                                            int originalDim, long seed) {
        if (flatData == null || n <= 0 || originalDim <= 0) {
            throw new SpectorValidationException(ErrorCode.EMPTY_COLLECTION, "flatData");
        }
        int useN    = Math.min(n, MAX_SAMPLE_SIZE);
        int[] idxs  = subsampleIndices(n, useN, seed);

        VasqFwht fwht  = new VasqFwht(originalDim, seed);
        int paddedDim  = fwht.paddedDim();

        float[][] rotated = new float[useN][paddedDim];
        float[] tempVec   = new float[originalDim];
        for (int i = 0; i < useN; i++) {
            int base = idxs[i] * originalDim;
            System.arraycopy(flatData, base, tempVec, 0, originalDim);
            fwht.rotate(tempVec, rotated[i]);
        }
        return computeParams(rotated, useN, paddedDim, originalDim, fwht,
                MAX_LEVEL_INT4, CLIP_SIGMAS_4BIT, VasqParams.BIT_WIDTH_4);
    }

    /** Convenience overload using {@link VasqParams#DEFAULT_SEED}. */
    public static VasqParams calibrate4bit(float[] flatData, int n, int originalDim) {
        return calibrate4bit(flatData, n, originalDim, VasqParams.DEFAULT_SEED);
    }

    // ── Core computation (shared by all overloads) ────────────────────────────

    /**
     * Computes per-dimension percentile-clipped mean + std from pre-rotated samples,
     * then derives VASQ scale parameters.
     *
     * <p>Delegates to the parameterized overload with INT8 defaults (maxLevel=127, clipSigmas=3.0).</p>
     */
    private static VasqParams computeParams(float[][] rotated, int n, int paddedDim,
                                             int originalDim, VasqFwht fwht) {
        return computeParams(rotated, n, paddedDim, originalDim, fwht,
                MAX_LEVEL_INT8, CLIP_SIGMAS, VasqParams.BIT_WIDTH_8);
    }

    /**
     * Core scale computation parameterized by quantization range and clipping.
     *
     * @param maxLevel   maximum absolute quantization level (127 for INT8, 7 for INT4)
     * @param clipSigmas number of standard deviations the range covers
     * @param bitWidth   {@link VasqParams#BIT_WIDTH_8} or {@link VasqParams#BIT_WIDTH_4}
     */
    private static VasqParams computeParams(float[][] rotated, int n, int paddedDim,
                                             int originalDim, VasqFwht fwht,
                                             int maxLevel, float clipSigmas, int bitWidth) {
        float[] means     = new float[paddedDim];
        float[] scales    = new float[paddedDim];
        float[] invScales = new float[paddedDim];
        float[] colBuf    = new float[n];

        for (int j = 0; j < paddedDim; j++) {
            // Collect column j
            for (int i = 0; i < n; i++) colBuf[i] = rotated[i][j];

            // Sort in-place — no Arrays.copyOf allocation
            Arrays.sort(colBuf, 0, n);
            float lo = colBuf[(int) (CLIP_PERCENTILE * (n - 1))];
            float hi = colBuf[(int) ((1f - CLIP_PERCENTILE) * (n - 1))];

            // Mean of clipped values (colBuf is now sorted, but sum/count are order-independent)
            double sum = 0;
            int cnt = 0;
            for (int i = 0; i < n; i++) {
                float v = colBuf[i];
                if (v >= lo && v <= hi) { sum += v; cnt++; }
            }
            if (cnt == 0) {
                means[j]     = 0f;
                scales[j]    = 1f / maxLevel;
                invScales[j] = (float) maxLevel;
                continue;
            }
            means[j] = (float) (sum / cnt);

            // Std dev of clipped values (Bessel-corrected)
            double var = 0;
            for (int i = 0; i < n; i++) {
                float v = colBuf[i];
                if (v >= lo && v <= hi) {
                    double d = v - means[j];
                    var += d * d;
                }
            }
            float std = (float) Math.sqrt(var / Math.max(1, cnt - 1));
            std = Math.max(std, MIN_STD);

            scales[j]    = clipSigmas * std / maxLevel;
            invScales[j] = maxLevel / (clipSigmas * std);
        }

        return new VasqParams(originalDim, paddedDim, means, scales, invScales, fwht, bitWidth);
    }

    // ── Sampling helpers ──────────────────────────────────────────────────────

    /**
     * Returns up to {@code maxSize} elements from the list, drawn uniformly at random.
     */
    private static List<float[]> subsampleList(List<float[]> list, int maxSize, long seed) {
        if (list.size() <= maxSize) return list;
        Random rng = new Random(seed);
        float[][] arr = list.toArray(new float[0][]);
        for (int i = 0; i < maxSize; i++) {
            int j = i + rng.nextInt(arr.length - i);
            float[] tmp = arr[i]; arr[i] = arr[j]; arr[j] = tmp;
        }
        return Arrays.asList(Arrays.copyOf(arr, maxSize));
    }

    /**
     * Returns up to {@code maxSize} distinct indices in [0, n), sampled without replacement.
     * If {@code maxSize >= n}, returns all indices in their natural order.
     */
    private static int[] subsampleIndices(int n, int maxSize, long seed) {
        if (maxSize >= n) {
            int[] all = new int[n];
            for (int i = 0; i < n; i++) all[i] = i;
            return all;
        }
        Random rng = new Random(seed);
        int[] indices = new int[n];
        for (int i = 0; i < n; i++) indices[i] = i;
        // Fisher-Yates partial shuffle
        for (int i = 0; i < maxSize; i++) {
            int j = i + rng.nextInt(n - i);
            int tmp = indices[i]; indices[i] = indices[j]; indices[j] = tmp;
        }
        return Arrays.copyOf(indices, maxSize);
    }
}