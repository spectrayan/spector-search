package com.spectrayan.spector.core.simd;
import com.spectrayan.spector.commons.error.SpectorException;

import java.util.Random;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorSpecies;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Random orthogonal rotation for isotropizing vector distributions.
 *
 * <p>Applies a fixed random orthogonal transform to vectors before quantization.
 * This spreads information across all coordinates, making per-coordinate scalar
 * quantization near-optimal — the key insight behind TurboQuant/PolarQuant.</p>
 *
 * <h3>Performance</h3>
 * <ul>
 *   <li>Matrix stored as a flat 1D array for cache-line-friendly sequential access</li>
 *   <li>Matrix-vector multiply uses Java Vector API (SIMD) for the inner dot product</li>
 *   <li>Inverse rotation uses a pre-transposed copy to avoid cache-hostile column access</li>
 *   <li>Generation (QR decomposition) is O(n³) but only runs once at calibration time</li>
 * </ul>
 *
 * <h3>Why not virtual threads?</h3>
 * <p>The rotation is a pure CPU-bound matrix-vector multiply. For typical embedding
 * dimensions (384–1536), the work is too small to benefit from thread scheduling
 * overhead. SIMD vectorization gives 4–8× speedup without any threading cost.</p>
 *
 * <h3>Properties</h3>
 * <ul>
 *   <li>Preserves L2 norms and inner products (orthogonal transform)</li>
 *   <li>Makes coordinate distributions more uniform/isotropic</li>
 *   <li>Deterministic given a seed (reproducible)</li>
 *   <li>Inverse rotation is just the transpose</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   var rotation = RandomRotation.generate(384, 42L);
 *   float[] rotated = rotation.rotate(originalVector);
 *   float[] restored = rotation.inverseRotate(rotated);
 * }</pre>
 */
public final class RandomRotation {

    private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;

    private final int dimensions;
    private final float[] matrix;           // row-major flat array [dims * dims]
    private final float[] matrixTransposed; // column-major (transposed) for inverse rotation

    private RandomRotation(int dimensions, float[] matrix, float[] matrixTransposed) {
        this.dimensions = dimensions;
        this.matrix = matrix;
        this.matrixTransposed = matrixTransposed;
    }

    /**
     * Generates a random orthogonal rotation matrix via QR decomposition.
     *
     * @param dimensions vector dimensionality
     * @param seed       random seed for reproducibility
     * @return a random rotation
     */
    public static RandomRotation generate(int dimensions, long seed) {
        if (dimensions < 1) {
            throw new SpectorValidationException(ErrorCode.DIMENSIONS_INVALID, 0);
        }

        Random rng = new Random(seed);
        float[] flat = qrOrthogonalFlat(dimensions, rng);
        float[] transposed = transpose(flat, dimensions);
        return new RandomRotation(dimensions, flat, transposed);
    }

    /**
     * Rotates a vector: result = R × vector.
     *
     * <p>Uses SIMD-accelerated dot products for each row of the matrix.</p>
     *
     * @param vector input vector (length must equal dimensions)
     * @return rotated vector
     */
    public float[] rotate(float[] vector) {
        if (vector.length != dimensions) {
            throw new SpectorValidationException(ErrorCode.DIMENSIONS_MISMATCH, dimensions, vector.length);
        }
        float[] result = new float[dimensions];
        matvecSimd(matrix, vector, result, dimensions);
        return result;
    }

    /**
     * Rotates a vector in-place into a destination buffer.
     *
     * @param vector input vector
     * @param result output buffer (must have length >= dimensions)
     */
    public void rotate(float[] vector, float[] result) {
        matvecSimd(matrix, vector, result, dimensions);
    }

    /**
     * Inverse rotation: result = R^T × vector.
     *
     * <p>Since R is orthogonal, R^{-1} = R^T. Uses the pre-transposed matrix
     * for cache-friendly row access during the multiply.</p>
     *
     * @param vector rotated vector
     * @return original (unrotated) vector
     */
    public float[] inverseRotate(float[] vector) {
        if (vector.length != dimensions) {
            throw new SpectorValidationException(ErrorCode.DIMENSIONS_MISMATCH, dimensions, vector.length);
        }
        float[] result = new float[dimensions];
        matvecSimd(matrixTransposed, vector, result, dimensions);
        return result;
    }

    /**
     * Inverse rotation into a destination buffer.
     *
     * @param vector rotated vector
     * @param result output buffer
     */
    public void inverseRotate(float[] vector, float[] result) {
        matvecSimd(matrixTransposed, vector, result, dimensions);
    }

    /** Returns the dimensionality. */
    public int dimensions() { return dimensions; }

    /** Returns the rotation matrix as a 2D array (defensive copy). */
    public float[][] matrix() {
        float[][] copy = new float[dimensions][dimensions];
        for (int i = 0; i < dimensions; i++) {
            System.arraycopy(matrix, i * dimensions, copy[i], 0, dimensions);
        }
        return copy;
    }

    // ─────────────── SIMD Matrix-Vector Multiply ───────────────

    /**
     * Computes result = M × v using SIMD lanes for the inner dot product.
     *
     * <p>For each row i of M, computes dot(M[i], v) using vectorized
     * fused multiply-add operations. Falls back to scalar for the tail
     * elements that don't fill a full SIMD lane.</p>
     */
    private static void matvecSimd(float[] mat, float[] vec, float[] result, int n) {
        int laneCount = SPECIES.length();
        int simdBound = SPECIES.loopBound(n);

        for (int i = 0; i < n; i++) {
            int rowOffset = i * n;
            FloatVector acc = FloatVector.zero(SPECIES);

            // SIMD loop: process laneCount elements per iteration
            int j = 0;
            for (; j < simdBound; j += laneCount) {
                FloatVector mv = FloatVector.fromArray(SPECIES, mat, rowOffset + j);
                FloatVector vv = FloatVector.fromArray(SPECIES, vec, j);
                acc = mv.fma(vv, acc); // fused multiply-add: acc += mv * vv
            }

            // Reduce SIMD lanes to scalar
            float sum = acc.reduceLanes(jdk.incubator.vector.VectorOperators.ADD);

            // Scalar tail
            for (; j < n; j++) {
                sum += mat[rowOffset + j] * vec[j];
            }

            result[i] = sum;
        }
    }

    // ─────────────── Matrix Utilities ───────────────

    /**
     * Transposes a flat row-major matrix.
     */
    private static float[] transpose(float[] mat, int n) {
        float[] t = new float[n * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                t[j * n + i] = mat[i * n + j];
            }
        }
        return t;
    }

    // ─────────────── QR Decomposition (Modified Gram-Schmidt) ───────────────

    /**
     * Generates a random orthogonal matrix via QR decomposition of a Gaussian random matrix.
     * Returns a flat row-major array.
     *
     * <p>Uses modified Gram-Schmidt for numerical stability. This runs once during
     * calibration so O(n³) is acceptable.</p>
     */
    private static float[] qrOrthogonalFlat(int n, Random rng) {
        // Generate random Gaussian matrix as columns stored as rows (for cache locality)
        float[][] cols = new float[n][n];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                cols[j][i] = (float) rng.nextGaussian();
            }
        }

        // Modified Gram-Schmidt: orthonormalize columns
        for (int j = 0; j < n; j++) {
            // Subtract projections onto previous columns
            for (int k = 0; k < j; k++) {
                float dot = simdDot(cols[k], cols[j], n);
                simdSubScaled(cols[j], cols[k], dot, n);
            }

            // Normalize
            float norm = (float) Math.sqrt(simdDot(cols[j], cols[j], n));
            if (norm < 1e-10f) {
                // Degenerate — use identity column (extremely unlikely)
                java.util.Arrays.fill(cols[j], 0.0f);
                cols[j][j] = 1.0f;
            } else {
                float invNorm = 1.0f / norm;
                simdScale(cols[j], invNorm, n);
            }
        }

        // Pack into flat row-major matrix: result[i][j] = cols[j][i]
        // (transpose from column-storage to row-major)
        float[] result = new float[n * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                result[i * n + j] = cols[j][i];
            }
        }
        return result;
    }

    /** SIMD dot product of two arrays. */
    private static float simdDot(float[] a, float[] b, int n) {
        int laneCount = SPECIES.length();
        int simdBound = SPECIES.loopBound(n);
        FloatVector acc = FloatVector.zero(SPECIES);

        int i = 0;
        for (; i < simdBound; i += laneCount) {
            FloatVector va = FloatVector.fromArray(SPECIES, a, i);
            FloatVector vb = FloatVector.fromArray(SPECIES, b, i);
            acc = va.fma(vb, acc);
        }

        float sum = acc.reduceLanes(jdk.incubator.vector.VectorOperators.ADD);
        for (; i < n; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    /** SIMD: a[i] -= scale * b[i] */
    private static void simdSubScaled(float[] a, float[] b, float scale, int n) {
        int laneCount = SPECIES.length();
        int simdBound = SPECIES.loopBound(n);
        FloatVector sv = FloatVector.broadcast(SPECIES, scale);

        int i = 0;
        for (; i < simdBound; i += laneCount) {
            FloatVector va = FloatVector.fromArray(SPECIES, a, i);
            FloatVector vb = FloatVector.fromArray(SPECIES, b, i);
            va.sub(vb.mul(sv)).intoArray(a, i);
        }
        for (; i < n; i++) {
            a[i] -= scale * b[i];
        }
    }

    /** SIMD: a[i] *= scale */
    private static void simdScale(float[] a, float scale, int n) {
        int laneCount = SPECIES.length();
        int simdBound = SPECIES.loopBound(n);
        FloatVector sv = FloatVector.broadcast(SPECIES, scale);

        int i = 0;
        for (; i < simdBound; i += laneCount) {
            FloatVector va = FloatVector.fromArray(SPECIES, a, i);
            va.mul(sv).intoArray(a, i);
        }
        for (; i < n; i++) {
            a[i] *= scale;
        }
    }
}