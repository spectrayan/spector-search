/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.spectrayan.spector.core.cluster;

import com.spectrayan.spector.core.similarity.EuclideanDistance;

import java.util.Arrays;
import java.util.Random;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.commons.error.SpectorException;

/**
 * K-Means++ clustering utility.
 *
 * <p>Provides a single authoritative implementation of K-Means++ seeding and Lloyd's
 * iterations used across all index types in the Spector engine
 * ({@code IvfFlatIndex}, {@code IvfPqIndex}, {@code QuantizedIvfPqIndex},
 * {@code SpectorIndex}). This eliminates the previously duplicated copy of the
 * same algorithm in each class.</p>
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li><b>K-Means++ seeding</b> — the first center is chosen uniformly at random;
 *       each subsequent center is chosen with probability proportional to the squared
 *       distance from the nearest already-selected center.</li>
 *   <li><b>Lloyd's iterations</b> — alternates between assigning each point to its
 *       nearest center and recomputing each center as the mean of its assigned points.
 *       Stops early if no assignment changes (convergence).</li>
 * </ol>
 *
 * <h3>Empty Clusters</h3>
 * <p>If a cluster loses all its members during an iteration, its centroid is kept
 * unchanged (no collapse to NaN). This is the conventional safe fallback.</p>
 *
 * <h3>Allocation Budget</h3>
 * <p>{@code train()} allocates {@code newCenters} and {@code counts} once before
 * the Lloyd's loop — both are reused across all iterations to avoid per-iteration
 * GC pressure. {@code nearestCentroids()} uses a box-free partial selection sort
 * (no {@code Integer[]}), allocating only a {@code float[nc]} distance array and
 * a {@code boolean[nc]} used-flag array — both of negligible size.</p>
 */
public final class KMeans {

    private KMeans() {}

    /**
     * Runs K-Means++ on {@code samples} to produce {@code k} centroids.
     *
     * @param samples       training vectors; must contain at least {@code k} entries
     * @param k             number of clusters (centroids to produce)
     * @param maxIterations maximum Lloyd's iterations (training stops early on convergence)
     * @param seed          random seed for reproducible K-Means++ initialization
     * @return {@code float[k][dimensions]} centroid array
     * @throws SpectorValidationException if {@code samples.length < k}
     */
    public static float[][] train(float[][] samples, int k, int maxIterations, long seed) {
        int n = samples.length;
        if (n < k) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "samples", k, Integer.MAX_VALUE, n);
        }
        int dimensions = samples[0].length;
        float[][] centers = new float[k][dimensions];
        Random rng = new Random(seed);

        // ── K-Means++ seeding ──
        System.arraycopy(samples[rng.nextInt(n)], 0, centers[0], 0, dimensions);
        float[] minDists = new float[n];
        Arrays.fill(minDists, Float.MAX_VALUE);

        for (int c = 1; c < k; c++) {
            double totalDist = 0;
            for (int i = 0; i < n; i++) {
                float d = squaredL2(samples[i], centers[c - 1]);
                if (d < minDists[i]) minDists[i] = d;
                totalDist += minDists[i];
            }
            double target = rng.nextDouble() * totalDist;
            double cumulative = 0;
            int selected = 0;
            for (int i = 0; i < n; i++) {
                cumulative += minDists[i];
                if (cumulative >= target) { selected = i; break; }
            }
            System.arraycopy(samples[selected], 0, centers[c], 0, dimensions);
        }

        // ── Lloyd's iterations ──
        // newCenters and counts are allocated once outside the loop and reset each iteration
        // to avoid k × dimensions float allocations per iteration.
        int[]     assignments = new int[n];
        float[][] newCenters  = new float[k][dimensions];
        int[]     counts      = new int[k];

        for (int iter = 0; iter < maxIterations; iter++) {
            // Assignment step
            boolean changed = false;
            for (int i = 0; i < n; i++) {
                int nearest = nearestCentroid(samples[i], centers);
                if (nearest != assignments[i]) {
                    assignments[i] = nearest;
                    changed = true;
                }
            }
            if (!changed) break; // Converged

            // Reset accumulators in-place — zero allocation
            for (int c = 0; c < k; c++) {
                Arrays.fill(newCenters[c], 0f);
                counts[c] = 0;
            }

            // Accumulate sums per cluster
            for (int i = 0; i < n; i++) {
                int c      = assignments[i];
                float[] nc = newCenters[c];
                float[] s  = samples[i];
                counts[c]++;
                for (int d = 0; d < dimensions; d++) {
                    nc[d] += s[d];
                }
            }

            // Compute means with multiply-by-inverse (avoids repeated division)
            for (int c = 0; c < k; c++) {
                if (counts[c] > 0) {
                    float inv  = 1f / counts[c];
                    float[] nc = newCenters[c];
                    float[] cc = centers[c];
                    for (int d = 0; d < dimensions; d++) {
                        cc[d] = nc[d] * inv;
                    }
                }
                // Empty cluster: keep previous centroid (safe fallback, avoids NaN)
            }
        }

        return centers;
    }

    /**
     * Returns the index of the nearest centroid to {@code vector} by squared L2 distance.
     *
     * @param vector    the query vector
     * @param centroids {@code float[k][dimensions]} centroid array
     * @return index into {@code centroids} of the nearest centroid
     */
    public static int nearestCentroid(float[] vector, float[][] centroids) {
        int best = 0;
        float bestDist = Float.MAX_VALUE;
        for (int c = 0; c < centroids.length; c++) {
            float d = squaredL2(vector, centroids[c]);
            if (d < bestDist) { bestDist = d; best = c; }
        }
        return best;
    }

    /**
     * Returns the indices of the {@code count} nearest centroids to {@code query},
     * sorted closest-first by squared L2 distance.
     *
     * <p>Uses a box-free partial selection sort — O(nc × count) with zero boxing
     * allocations. Correct and efficient when {@code count ≪ nc} (e.g. nProbe=16,
     * nCentroids=256 → 4096 comparisons). Replaces the previous approach that
     * box-allocated {@code Integer[nc]} on every call.</p>
     *
     * <p>If {@code count >= centroids.length}, all centroids are returned sorted.</p>
     *
     * @param query     the query vector
     * @param centroids {@code float[k][dimensions]} centroid array
     * @param count     number of nearest centroids to return
     * @return int array of length {@code min(count, centroids.length)}, closest first
     */
    public static int[] nearestCentroids(float[] query, float[][] centroids, int count) {
        int nc     = centroids.length;
        int actual = Math.min(count, nc);

        // Compute all distances once — float[nc], stack-friendly
        float[] dists = new float[nc];
        for (int c = 0; c < nc; c++) {
            dists[c] = squaredL2(query, centroids[c]);
        }

        // Partial selection sort — no boxing, no Comparator, no Integer[]
        int[]     result = new int[actual];
        boolean[] used   = new boolean[nc];
        for (int r = 0; r < actual; r++) {
            float bestDist = Float.MAX_VALUE;
            int   bestIdx  = -1;
            for (int c = 0; c < nc; c++) {
                if (!used[c] && dists[c] < bestDist) {
                    bestDist = dists[c];
                    bestIdx  = c;
                }
            }
            result[r]     = bestIdx;
            used[bestIdx] = true;
        }
        return result;
    }

    /**
     * Computes the squared Euclidean (L2) distance between two vectors.
     *
     * <p>Returns the squared distance (no {@code sqrt}) for efficiency in comparisons
     * where the relative order is all that matters.</p>
     *
     * <p>Delegates to the SIMD-accelerated {@link EuclideanDistance#computeSquared(float[], float[])}
     * kernel, which uses the Java Vector API (Project Panama) for hardware-optimized
     * computation. This is critical because {@code squaredL2} is called on every vector
     * insertion (centroid routing) and every search (nProbe selection).</p>
     *
     * @param a first vector
     * @param b second vector
     * @return squared L2 distance: {@code Σ (a[i] − b[i])²}
     */
    public static float squaredL2(float[] a, float[] b) {
        return EuclideanDistance.computeSquared(a, b);
    }
}