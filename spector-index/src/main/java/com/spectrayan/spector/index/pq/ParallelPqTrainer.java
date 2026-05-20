package com.spectrayan.spector.index.pq;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Parallel Product Quantization trainer with SIMD-accelerated K-Means.
 *
 * <p>Trains PQ codebooks by splitting D-dimensional vectors into M subspaces
 * and running K-Means independently on each subspace. Key optimizations:</p>
 * <ul>
 *   <li><b>SIMD acceleration</b>: Uses the Java Vector API for squared L2 distance
 *       computations during the K-Means assignment step</li>
 *   <li><b>Parallel subspace training</b>: Each subspace is trained on a separate
 *       virtual thread (one per subspace, via virtual thread executor)</li>
 *   <li><b>Scalar fallback</b>: Automatically falls back to scalar distance computation
 *       when SIMD hardware is unavailable</li>
 * </ul>
 *
 * <p>Produces codebooks of shape {@code [M][256][D/M]} where M is the number of
 * subspaces, 256 is the number of centroids per subspace (8-bit codes), and
 * D/M is the sub-dimension.</p>
 *
 * @see ProductQuantizer
 */
public final class ParallelPqTrainer {

    /** Standard number of centroids per subspace (8-bit codes). */
    public static final int KSUB = 256;

    /** Default maximum K-Means iterations. */
    private static final int DEFAULT_MAX_ITERATIONS = 25;

    private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;

    /**
     * Whether SIMD acceleration is available at runtime.
     * Falls back to scalar if the preferred species has fewer than 2 lanes
     * (indicating no useful SIMD support).
     */
    private static final boolean SIMD_AVAILABLE = SPECIES.length() >= 2;

    private final int maxIterations;
    private final long seed;

    /**
     * Creates a trainer with default settings (25 max iterations, seed=42).
     */
    public ParallelPqTrainer() {
        this(DEFAULT_MAX_ITERATIONS, 42L);
    }

    /**
     * Creates a trainer with custom settings.
     *
     * @param maxIterations maximum K-Means iterations per subspace
     * @param seed          random seed for reproducible initialization
     */
    public ParallelPqTrainer(int maxIterations, long seed) {
        if (maxIterations <= 0) {
            throw new IllegalArgumentException("maxIterations must be positive: " + maxIterations);
        }
        this.maxIterations = maxIterations;
        this.seed = seed;
    }

    /**
     * Trains PQ codebooks in parallel across subspaces.
     *
     * @param vectors       training vectors (at least {@code KSUB} recommended)
     * @param numSubspaces  number of subspaces (M). Must divide dimensions evenly.
     * @param numCentroids  number of centroids per subspace (typically 256)
     * @param maxIterations maximum K-Means iterations (overrides constructor value)
     * @return codebooks of shape [M][numCentroids][D/M]
     * @throws IllegalArgumentException if inputs are invalid
     */
    public float[][][] train(float[][] vectors, int numSubspaces, int numCentroids, int maxIterations) {
        validateInputs(vectors, numSubspaces, numCentroids);

        int dimensions = vectors[0].length;
        int dsub = dimensions / numSubspaces;
        int actualK = Math.min(numCentroids, vectors.length);
        int iters = maxIterations > 0 ? maxIterations : this.maxIterations;

        float[][][] codebooks = new float[numSubspaces][][];

        // Parallelize sub-quantizer training across virtual threads (one per subspace)
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<float[][]>> futures = new ArrayList<>(numSubspaces);

            for (int m = 0; m < numSubspaces; m++) {
                final int offset = m * dsub;
                // Each subspace gets its own seed derived from the base seed
                final long subspaceSeed = seed + m;

                futures.add(executor.submit(() -> trainSubspace(
                        vectors, offset, dsub, actualK, iters, subspaceSeed)));
            }

            for (int m = 0; m < numSubspaces; m++) {
                float[][] centroids = futures.get(m).get();
                // Pad to numCentroids if actualK < numCentroids
                if (centroids.length < numCentroids) {
                    float[][] padded = new float[numCentroids][dsub];
                    for (int k = 0; k < centroids.length; k++) {
                        System.arraycopy(centroids[k], 0, padded[k], 0, dsub);
                    }
                    codebooks[m] = padded;
                } else {
                    codebooks[m] = centroids;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("PQ training interrupted", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("PQ subspace training failed", e.getCause());
        }

        return codebooks;
    }

    /**
     * Trains PQ codebooks using default maxIterations from constructor.
     *
     * @param vectors      training vectors
     * @param numSubspaces number of subspaces (M)
     * @param numCentroids number of centroids per subspace
     * @return codebooks of shape [M][numCentroids][D/M]
     */
    public float[][][] train(float[][] vectors, int numSubspaces, int numCentroids) {
        return train(vectors, numSubspaces, numCentroids, this.maxIterations);
    }

    /**
     * Returns whether SIMD acceleration is being used.
     *
     * @return true if SIMD is available and active
     */
    public static boolean isSimdAccelerated() {
        return SIMD_AVAILABLE;
    }

    // ─────────────── Subspace Training ───────────────

    /**
     * Trains a single subspace using K-Means with SIMD-accelerated distance.
     */
    private float[][] trainSubspace(float[][] vectors, int offset, int dsub,
                                    int k, int maxIters, long subspaceSeed) {
        int n = vectors.length;
        Random rng = new Random(subspaceSeed);

        // Extract sub-vectors for this subspace
        float[][] subVectors = new float[n][dsub];
        for (int i = 0; i < n; i++) {
            System.arraycopy(vectors[i], offset, subVectors[i], 0, dsub);
        }

        // Initialize centroids with K-Means++
        float[][] centroids = kMeansPlusPlusInit(subVectors, k, dsub, rng);
        int[] assignments = new int[n];

        for (int iter = 0; iter < maxIters; iter++) {
            // Assign step: find nearest centroid for each vector
            boolean changed = false;
            for (int i = 0; i < n; i++) {
                int nearest = findNearestCentroid(subVectors[i], centroids, dsub);
                if (nearest != assignments[i]) {
                    assignments[i] = nearest;
                    changed = true;
                }
            }
            if (!changed) break;

            // Update step: recompute centroids
            float[][] newCentroids = new float[k][dsub];
            int[] counts = new int[k];
            for (int i = 0; i < n; i++) {
                int c = assignments[i];
                counts[c]++;
                for (int d = 0; d < dsub; d++) {
                    newCentroids[c][d] += subVectors[i][d];
                }
            }
            for (int c = 0; c < k; c++) {
                if (counts[c] > 0) {
                    for (int d = 0; d < dsub; d++) {
                        newCentroids[c][d] /= counts[c];
                    }
                    centroids[c] = newCentroids[c];
                }
                // Empty clusters retain their previous centroid
            }
        }

        return centroids;
    }

    // ─────────────── Distance Computation ───────────────

    /**
     * Finds the nearest centroid to a given vector using SIMD or scalar fallback.
     */
    private static int findNearestCentroid(float[] vector, float[][] centroids, int dims) {
        int best = 0;
        float bestDist = Float.MAX_VALUE;
        for (int k = 0; k < centroids.length; k++) {
            float dist = squaredL2(vector, 0, centroids[k], 0, dims);
            if (dist < bestDist) {
                bestDist = dist;
                best = k;
            }
        }
        return best;
    }

    /**
     * Computes squared L2 distance with SIMD acceleration when available.
     * Falls back to scalar computation otherwise.
     *
     * @param a       first vector
     * @param aOffset offset into a
     * @param b       second vector
     * @param bOffset offset into b
     * @param length  number of elements
     * @return squared L2 distance
     */
    static float squaredL2(float[] a, int aOffset, float[] b, int bOffset, int length) {
        if (SIMD_AVAILABLE) {
            return squaredL2Simd(a, aOffset, b, bOffset, length);
        }
        return squaredL2Scalar(a, aOffset, b, bOffset, length);
    }

    /**
     * SIMD-accelerated squared L2 distance using the Java Vector API.
     */
    private static float squaredL2Simd(float[] a, int aOffset, float[] b, int bOffset, int length) {
        int laneCount = SPECIES.length();
        FloatVector sum = FloatVector.zero(SPECIES);

        // Main vectorized loop
        int i = 0;
        int limit = SPECIES.loopBound(length);
        for (; i < limit; i += laneCount) {
            FloatVector va = FloatVector.fromArray(SPECIES, a, aOffset + i);
            FloatVector vb = FloatVector.fromArray(SPECIES, b, bOffset + i);
            FloatVector diff = va.sub(vb);
            sum = diff.fma(diff, sum); // sum += diff * diff
        }

        // Tail: masked operations for remaining elements
        if (i < length) {
            VectorMask<Float> mask = SPECIES.indexInRange(i, length);
            FloatVector va = FloatVector.fromArray(SPECIES, a, aOffset + i, mask);
            FloatVector vb = FloatVector.fromArray(SPECIES, b, bOffset + i, mask);
            FloatVector diff = va.sub(vb, mask);
            sum = sum.add(diff.mul(diff, mask));
        }

        return sum.reduceLanes(VectorOperators.ADD);
    }

    /**
     * Scalar fallback for squared L2 distance when SIMD is unavailable.
     */
    static float squaredL2Scalar(float[] a, int aOffset, float[] b, int bOffset, int length) {
        float sum = 0f;
        for (int i = 0; i < length; i++) {
            float diff = a[aOffset + i] - b[bOffset + i];
            sum += diff * diff;
        }
        return sum;
    }

    // ─────────────── K-Means++ Initialization ───────────────

    /**
     * K-Means++ initialization for better convergence.
     */
    private static float[][] kMeansPlusPlusInit(float[][] data, int k, int dims, Random rng) {
        int n = data.length;
        float[][] centroids = new float[k][dims];

        // First centroid: random selection
        System.arraycopy(data[rng.nextInt(n)], 0, centroids[0], 0, dims);

        float[] minDists = new float[n];
        Arrays.fill(minDists, Float.MAX_VALUE);

        for (int c = 1; c < k; c++) {
            // Compute distances to nearest existing centroid
            double totalDist = 0;
            for (int i = 0; i < n; i++) {
                float d = squaredL2(data[i], 0, centroids[c - 1], 0, dims);
                if (d < minDists[i]) {
                    minDists[i] = d;
                }
                totalDist += minDists[i];
            }

            // Weighted random selection proportional to distance
            double target = rng.nextDouble() * totalDist;
            double cumulative = 0;
            int selected = 0;
            for (int i = 0; i < n; i++) {
                cumulative += minDists[i];
                if (cumulative >= target) {
                    selected = i;
                    break;
                }
            }
            System.arraycopy(data[selected], 0, centroids[c], 0, dims);
        }

        return centroids;
    }

    // ─────────────── Validation ───────────────

    private static void validateInputs(float[][] vectors, int numSubspaces, int numCentroids) {
        if (vectors == null || vectors.length == 0) {
            throw new IllegalArgumentException("Training vectors must not be null or empty");
        }
        if (numSubspaces <= 0) {
            throw new IllegalArgumentException("numSubspaces must be positive: " + numSubspaces);
        }
        if (numCentroids <= 0 || numCentroids > KSUB) {
            throw new IllegalArgumentException(
                    "numCentroids must be between 1 and " + KSUB + ": " + numCentroids);
        }
        int dimensions = vectors[0].length;
        if (dimensions <= 0) {
            throw new IllegalArgumentException("Vector dimensions must be positive");
        }
        if (dimensions % numSubspaces != 0) {
            throw new IllegalArgumentException(
                    "dimensions (" + dimensions + ") must be divisible by numSubspaces (" + numSubspaces + ")");
        }
    }
}
