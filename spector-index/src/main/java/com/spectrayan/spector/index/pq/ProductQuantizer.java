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
package com.spectrayan.spector.index.pq;

import com.spectrayan.spector.core.similarity.SimilarityFunction;

import java.util.Arrays;
import java.util.Random;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Product Quantizer (PQ) for extreme vector compression.
 *
 * <p>Splits a D-dimensional vector into M sub-vectors and quantizes each
 * independently using a codebook of {@code ksub} centroids trained via K-Means.
 * Each sub-vector is represented by a single byte (256 centroids), so an entire
 * vector is compressed to M bytes.</p>
 *
 * <h3>Compression Ratios</h3>
 * <table>
 *   <tr><td>Dims</td><td>M</td><td>Original</td><td>PQ</td><td>Ratio</td></tr>
 *   <tr><td>128</td><td>16</td><td>512B</td><td>16B</td><td>32×</td></tr>
 *   <tr><td>384</td><td>48</td><td>1536B</td><td>48B</td><td>32×</td></tr>
 *   <tr><td>768</td><td>96</td><td>3072B</td><td>96B</td><td>32×</td></tr>
 * </table>
 *
 * <h3>ADC (Asymmetric Distance Computation)</h3>
 * <p>At query time, a distance lookup table is precomputed for the query vector
 * (M × ksub float distances). Then each database vector (M bytes) can be scored
 * with M table lookups + additions — no float decompression needed.</p>
 *
 * @see PqDistanceTable
 */
public final class ProductQuantizer {

    /** Standard number of centroids per subspace (8-bit codes). */
    public static final int KSUB = 256;

    /** Max K-Means iterations during training. */
    private static final int MAX_KMEANS_ITERS = 25;

    private final int dimensions;
    private final int numSubspaces;     // M
    private final int subDimension;     // dsub = dims / M
    private final float[][][] codebooks; // [M][KSUB][dsub] — centroids per subspace

    private ProductQuantizer(int dimensions, int numSubspaces, float[][][] codebooks) {
        this.dimensions = dimensions;
        this.numSubspaces = numSubspaces;
        this.subDimension = dimensions / numSubspaces;
        this.codebooks = codebooks;
    }

    /**
     * Trains a product quantizer from sample vectors.
     *
     * @param samples       training vectors (at least {@code KSUB} samples recommended)
     * @param dimensions    vector dimensionality
     * @param numSubspaces  number of subspaces (M). Must divide dimensions evenly.
     * @return a trained product quantizer
     */
    public static ProductQuantizer train(float[][] samples, int dimensions, int numSubspaces) {
        if (samples.length == 0) {
            throw new SpectorValidationException(ErrorCode.EMPTY_COLLECTION, "trainingSamples");
        }
        if (dimensions % numSubspaces != 0) {
            throw new SpectorValidationException(ErrorCode.DIMENSIONS_MISMATCH, "dimensions (" + dimensions + ") must be divisible by numSubspaces (" + numSubspaces + ")");
        }

        int dsub = dimensions / numSubspaces;
        float[][][] codebooks = new float[numSubspaces][KSUB][dsub];
        Random rng = new Random(42);

        // Train each subspace independently
        for (int m = 0; m < numSubspaces; m++) {
            // Extract sub-vectors for this subspace
            int offset = m * dsub;
            float[][] subVectors = new float[samples.length][dsub];
            for (int i = 0; i < samples.length; i++) {
                System.arraycopy(samples[i], offset, subVectors[i], 0, dsub);
            }

            // Run K-Means to find KSUB centroids
            int actualK = Math.min(KSUB, samples.length);
            float[][] centroids = kMeans(subVectors, actualK, dsub, rng);

            // Copy centroids (pad with zeros if fewer than KSUB)
            for (int k = 0; k < actualK; k++) {
                System.arraycopy(centroids[k], 0, codebooks[m][k], 0, dsub);
            }
        }

        return new ProductQuantizer(dimensions, numSubspaces, codebooks);
    }

    /**
     * Encodes a vector to a PQ code (M bytes).
     *
     * @param vector the input vector (must have length {@code dimensions})
     * @return PQ code of length M (each byte is a centroid index 0-255)
     */
    public byte[] encode(float[] vector) {
        byte[] code = new byte[numSubspaces];
        for (int m = 0; m < numSubspaces; m++) {
            int offset = m * subDimension;
            code[m] = (byte) nearestCentroid(vector, offset, codebooks[m]);
        }
        return code;
    }

    /**
     * Batch-encodes multiple vectors.
     *
     * @param vectors array of input vectors
     * @return array of PQ codes
     */
    public byte[][] encodeBatch(float[][] vectors) {
        byte[][] codes = new byte[vectors.length][];
        for (int i = 0; i < vectors.length; i++) {
            codes[i] = encode(vectors[i]);
        }
        return codes;
    }

    /**
     * Decodes a PQ code back to an approximate vector.
     *
     * <p>Reconstructs the vector by concatenating the centroids for each
     * subspace index. This is a lossy reconstruction.</p>
     *
     * @param code the PQ code (length M)
     * @return reconstructed vector (length {@code dimensions})
     */
    public float[] decode(byte[] code) {
        float[] vector = new float[dimensions];
        for (int m = 0; m < numSubspaces; m++) {
            int centroidIdx = Byte.toUnsignedInt(code[m]);
            System.arraycopy(codebooks[m][centroidIdx], 0, vector, m * subDimension, subDimension);
        }
        return vector;
    }

    /**
     * Precomputes an ADC (Asymmetric Distance Computation) lookup table
     * for a query vector.
     *
     * <p>The table has shape [M][KSUB] where entry [m][k] is the squared
     * L2 distance between the query sub-vector m and centroid k of subspace m.
     * This allows scoring any PQ code with just M table lookups.</p>
     *
     * @param query the query vector
     * @return distance table [M][KSUB]
     */
    public float[][] computeDistanceTable(float[] query) {
        float[][] table = new float[numSubspaces][KSUB];
        for (int m = 0; m < numSubspaces; m++) {
            int offset = m * subDimension;
            for (int k = 0; k < KSUB; k++) {
                float dist = 0;
                for (int d = 0; d < subDimension; d++) {
                    float diff = query[offset + d] - codebooks[m][k][d];
                    dist += diff * diff;
                }
                table[m][k] = dist;
            }
        }
        return table;
    }

    /**
     * Computes the approximate distance from a query to a PQ-coded vector
     * using a precomputed distance table.
     *
     * @param table the ADC distance table (from {@link #computeDistanceTable})
     * @param code  the PQ code of the database vector
     * @return approximate squared L2 distance
     */
    public static float adcDistance(float[][] table, byte[] code) {
        float dist = 0;
        for (int m = 0; m < code.length; m++) {
            dist += table[m][Byte.toUnsignedInt(code[m])];
        }
        return dist;
    }

    // ─────────────── Accessors ───────────────

    /** Returns the number of subspaces (M). */
    public int numSubspaces() { return numSubspaces; }

    /** Returns the sub-dimension (dims / M). */
    public int subDimension() { return subDimension; }

    /** Returns the total dimensionality. */
    public int dimensions() { return dimensions; }

    /** Returns the codebooks [M][KSUB][dsub]. */
    public float[][][] codebooks() { return codebooks; }

    /** Compression ratio vs float32. */
    public float compressionRatio() {
        return (float) numSubspaces / (dimensions * Float.BYTES);
    }

    // ─────────────── K-Means ───────────────

    private static float[][] kMeans(float[][] data, int k, int dims, Random rng) {
        int n = data.length;

        // Initialize centroids with K-Means++ initialization
        float[][] centroids = kMeansPlusPlusInit(data, k, dims, rng);
        int[] assignments = new int[n];

        for (int iter = 0; iter < MAX_KMEANS_ITERS; iter++) {
            // Assign step
            boolean changed = false;
            for (int i = 0; i < n; i++) {
                int nearest = nearestCentroidIdx(data[i], 0, centroids, dims);
                if (nearest != assignments[i]) {
                    assignments[i] = nearest;
                    changed = true;
                }
            }
            if (!changed) break;

            // Update step
            float[][] newCentroids = new float[k][dims];
            int[] counts = new int[k];
            for (int i = 0; i < n; i++) {
                int c = assignments[i];
                counts[c]++;
                for (int d = 0; d < dims; d++) {
                    newCentroids[c][d] += data[i][d];
                }
            }
            for (int c = 0; c < k; c++) {
                if (counts[c] > 0) {
                    for (int d = 0; d < dims; d++) {
                        newCentroids[c][d] /= counts[c];
                    }
                    centroids[c] = newCentroids[c];
                }
            }
        }

        return centroids;
    }

    /** K-Means++ initialization for better convergence. */
    private static float[][] kMeansPlusPlusInit(float[][] data, int k, int dims, Random rng) {
        int n = data.length;
        float[][] centroids = new float[k][dims];

        // First centroid: random
        System.arraycopy(data[rng.nextInt(n)], 0, centroids[0], 0, dims);

        float[] minDists = new float[n];
        Arrays.fill(minDists, Float.MAX_VALUE);

        for (int c = 1; c < k; c++) {
            // Compute distances to nearest existing centroid
            double totalDist = 0;
            for (int i = 0; i < n; i++) {
                float d = squaredL2(data[i], 0, centroids[c - 1], dims);
                if (d < minDists[i]) minDists[i] = d;
                totalDist += minDists[i];
            }

            // Weighted random selection
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

    private int nearestCentroid(float[] vector, int offset, float[][] centroids) {
        return nearestCentroidIdx(vector, offset, centroids, subDimension);
    }

    private static int nearestCentroidIdx(float[] vector, int offset, float[][] centroids, int dims) {
        int best = 0;
        float bestDist = Float.MAX_VALUE;
        for (int k = 0; k < centroids.length; k++) {
            float dist = squaredL2(vector, offset, centroids[k], dims);
            if (dist < bestDist) {
                bestDist = dist;
                best = k;
            }
        }
        return best;
    }

    private static float squaredL2(float[] a, int offsetA, float[] b, int dims) {
        float sum = 0;
        for (int d = 0; d < dims; d++) {
            float diff = a[offsetA + d] - b[d];
            sum += diff * diff;
        }
        return sum;
    }
}