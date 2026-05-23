package com.spectrayan.spector.core;

import java.util.Random;

/**
 * Random orthogonal rotation for isotropizing vector distributions.
 *
 * <p>Applies a fixed random orthogonal transform to vectors before quantization.
 * This spreads information across all coordinates, making per-coordinate scalar
 * quantization near-optimal — the key insight behind TurboQuant/PolarQuant.</p>
 *
 * <h3>Algorithm</h3>
 * <p>Generates a random orthogonal matrix via QR decomposition of a random
 * Gaussian matrix. The rotation is deterministic given a seed, so both encode
 * and decode use the same transform.</p>
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

    private final int dimensions;
    private final float[][] matrix;  // orthogonal matrix [dims][dims]

    private RandomRotation(int dimensions, float[][] matrix) {
        this.dimensions = dimensions;
        this.matrix = matrix;
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
            throw new IllegalArgumentException("dimensions must be >= 1");
        }

        Random rng = new Random(seed);
        float[][] q = qrOrthogonal(dimensions, rng);
        return new RandomRotation(dimensions, q);
    }

    /**
     * Rotates a vector: result = R × vector.
     *
     * @param vector input vector (length must equal dimensions)
     * @return rotated vector
     */
    public float[] rotate(float[] vector) {
        if (vector.length != dimensions) {
            throw new IllegalArgumentException(
                    "Expected " + dimensions + " dims, got " + vector.length);
        }
        float[] result = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            float sum = 0;
            for (int j = 0; j < dimensions; j++) {
                sum += matrix[i][j] * vector[j];
            }
            result[i] = sum;
        }
        return result;
    }

    /**
     * Rotates a vector in-place into a destination buffer.
     *
     * @param vector input vector
     * @param result output buffer (must have length >= dimensions)
     */
    public void rotate(float[] vector, float[] result) {
        for (int i = 0; i < dimensions; i++) {
            float sum = 0;
            for (int j = 0; j < dimensions; j++) {
                sum += matrix[i][j] * vector[j];
            }
            result[i] = sum;
        }
    }

    /**
     * Inverse rotation: result = R^T × vector.
     *
     * <p>Since R is orthogonal, R^{-1} = R^T.</p>
     *
     * @param vector rotated vector
     * @return original (unrotated) vector
     */
    public float[] inverseRotate(float[] vector) {
        if (vector.length != dimensions) {
            throw new IllegalArgumentException(
                    "Expected " + dimensions + " dims, got " + vector.length);
        }
        float[] result = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            float sum = 0;
            for (int j = 0; j < dimensions; j++) {
                sum += matrix[j][i] * vector[j]; // transpose: [j][i] instead of [i][j]
            }
            result[i] = sum;
        }
        return result;
    }

    /** Returns the dimensionality. */
    public int dimensions() { return dimensions; }

    /** Returns the rotation matrix (defensive copy). */
    public float[][] matrix() {
        float[][] copy = new float[dimensions][];
        for (int i = 0; i < dimensions; i++) {
            copy[i] = matrix[i].clone();
        }
        return copy;
    }

    /** Returns the seed is not stored; use the matrix directly. */
    // No getSeed() — we store the full matrix for deserialization flexibility.

    // ─────────────── QR Decomposition (Gram-Schmidt) ───────────────

    /**
     * Generates a random orthogonal matrix via QR decomposition of a Gaussian random matrix.
     * Uses modified Gram-Schmidt for numerical stability.
     */
    private static float[][] qrOrthogonal(int n, Random rng) {
        // Generate random Gaussian matrix
        float[][] a = new float[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                a[i][j] = (float) rng.nextGaussian();
            }
        }

        // Modified Gram-Schmidt to orthonormalize columns
        float[][] q = new float[n][n];

        for (int j = 0; j < n; j++) {
            // Copy column j of A into q[j]
            for (int i = 0; i < n; i++) {
                q[j][i] = a[i][j]; // q[j] = column j of A (stored as row for cache)
            }

            // Subtract projections onto previous columns
            for (int k = 0; k < j; k++) {
                float dot = dot(q[k], q[j], n);
                for (int i = 0; i < n; i++) {
                    q[j][i] -= dot * q[k][i];
                }
            }

            // Normalize
            float norm = norm(q[j], n);
            if (norm < 1e-10f) {
                // Degenerate — regenerate this column (extremely unlikely)
                for (int i = 0; i < n; i++) {
                    q[j][i] = (i == j) ? 1.0f : 0.0f;
                }
            } else {
                float invNorm = 1.0f / norm;
                for (int i = 0; i < n; i++) {
                    q[j][i] *= invNorm;
                }
            }
        }

        // Transpose so matrix[i][j] means row i, column j (for rotate = M * v)
        float[][] result = new float[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                result[i][j] = q[i][j];
            }
        }
        return result;
    }

    private static float dot(float[] a, float[] b, int n) {
        float sum = 0;
        for (int i = 0; i < n; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    private static float norm(float[] v, int n) {
        return (float) Math.sqrt(dot(v, v, n));
    }
}
