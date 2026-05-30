package com.spectrayan.spector.memory.cortex;

import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes memories to IVF centroid partitions for spatial pre-filtering.
 *
 * <h3>Biological Analog: Cortical Columns</h3>
 * <p>Physical grouping of neurons by function. The brain clusters related concepts
 * into cortical columns for efficient activation — Spector clusters memories by
 * vector proximity for cache-efficient scanning.</p>
 *
 * <h3>Dual-Path Routing</h3>
 * <p>This provides <b>Path A: Spatial Routing</b> ("Where").
 * The synaptic tag Bloom filter provides <b>Path B: Semantic Filtering</b> ("What").
 * Together, they enable two-stage pre-filtering before expensive SIMD distance
 * computation.</p>
 *
 * <h3>Centroid Drift (Neurogenesis)</h3>
 * <p>Over time, new topics emerge. The {@link #recalibrate(float[][], int)}
 * method recalculates centroid positions from actual vector distributions,
 * splitting partitions that exceed a variance threshold — analogous to
 * hippocampal neurogenesis.</p>
 *
 * @see com.spectrayan.spector.memory.synapse.SynapticHeaderConstants#OFFSET_CENTROID_ID
 */
public final class CentroidRouter {

    private static final Logger log = LoggerFactory.getLogger(CentroidRouter.class);

    /** Maximum number of centroids (IVF partitions). */
    private static final int MAX_CENTROIDS = 256;

    /** Default number of initial centroids. */
    private static final int DEFAULT_K = 16;

    /** Variance threshold for partition splitting during recalibration. */
    private static final float SPLIT_VARIANCE_THRESHOLD = 2.0f;

    private final int dimensions;
    private float[][] centroids;
    private int activeCentroids;

    /**
     * Creates a router with default centroid count.
     *
     * @param dimensions vector dimensions
     */
    public CentroidRouter(int dimensions) {
        this(dimensions, DEFAULT_K);
    }

    /**
     * Creates a router with a specified number of initial centroids.
     *
     * @param dimensions      vector dimensions
     * @param initialCentroids number of initial centroid slots
     */
    public CentroidRouter(int dimensions, int initialCentroids) {
        this.dimensions = dimensions;
        this.activeCentroids = Math.min(initialCentroids, MAX_CENTROIDS);
        this.centroids = new float[MAX_CENTROIDS][dimensions];
        log.info("CentroidRouter initialized: dimensions={}, initialCentroids={}",
                dimensions, activeCentroids);
    }

    /**
     * Assigns the nearest centroid ID for a given vector.
     *
     * <p>Computes L2 distance to all active centroids and returns the ID of
     * the nearest. This value is written to the {@code centroid_id} field
     * at offset 24 in the cognitive header.</p>
     *
     * @param vector the memory vector
     * @return centroid ID (0 to activeCentroids-1), or 0 if no centroids are initialized
     */
    public int assignCentroid(float[] vector) {
        if (activeCentroids == 0) return 0;

        int bestId = 0;
        float bestDist = Float.MAX_VALUE;

        for (int c = 0; c < activeCentroids; c++) {
            float dist = l2Distance(vector, centroids[c]);
            if (dist < bestDist) {
                bestDist = dist;
                bestId = c;
            }
        }

        return bestId;
    }

    /**
     * Updates centroid positions from a sample of vectors.
     *
     * <p>This is analogous to <b>neurogenesis</b> — the brain creates new neurons
     * to accommodate new categories of experience. Called periodically by the
     * ReflectDaemon during sleep consolidation.</p>
     *
     * <p>Algorithm:</p>
     * <ol>
     *   <li>Assign each sample vector to its nearest centroid</li>
     *   <li>Recompute centroid positions as cluster means</li>
     *   <li>If any partition's internal variance exceeds the threshold, split it</li>
     * </ol>
     *
     * @param sampleVectors representative sample of recent memory vectors
     * @param iterations    number of Lloyd's iterations (default: 5)
     * @return number of active centroids after recalibration
     */
    public int recalibrate(float[][] sampleVectors, int iterations) {
        if (sampleVectors == null || sampleVectors.length == 0) return activeCentroids;
        if (activeCentroids == 0) {
            // Bootstrap: initialize first centroids from sample
            activeCentroids = Math.min(DEFAULT_K, sampleVectors.length);
            for (int c = 0; c < activeCentroids; c++) {
                System.arraycopy(sampleVectors[c], 0, centroids[c], 0, dimensions);
            }
        }

        // Run mini k-means (Lloyd's algorithm)
        for (int iter = 0; iter < iterations; iter++) {
            // Accumulate per-centroid sums and counts
            float[][] sums = new float[activeCentroids][dimensions];
            int[] counts = new int[activeCentroids];
            float[] variances = new float[activeCentroids];

            for (float[] vec : sampleVectors) {
                int nearest = assignCentroid(vec);
                counts[nearest]++;
                for (int d = 0; d < dimensions; d++) {
                    sums[nearest][d] += vec[d];
                }
            }

            // Update centroid positions
            for (int c = 0; c < activeCentroids; c++) {
                if (counts[c] > 0) {
                    for (int d = 0; d < dimensions; d++) {
                        centroids[c][d] = sums[c][d] / counts[c];
                    }
                }
            }

            // Compute variance for splitting check (last iteration only)
            if (iter == iterations - 1) {
                for (float[] vec : sampleVectors) {
                    int nearest = assignCentroid(vec);
                    variances[nearest] += l2Distance(vec, centroids[nearest]);
                }
                for (int c = 0; c < activeCentroids; c++) {
                    if (counts[c] > 0) {
                        variances[c] /= counts[c];
                    }
                }

                // Split high-variance partitions
                for (int c = 0; c < activeCentroids && activeCentroids < MAX_CENTROIDS; c++) {
                    if (variances[c] > SPLIT_VARIANCE_THRESHOLD && counts[c] > 10) {
                        splitCentroid(c);
                        log.info("Centroid {} split (variance={:.3f}). Active centroids: {}",
                                c, variances[c], activeCentroids);
                    }
                }
            }
        }

        log.debug("Recalibration complete: {} active centroids", activeCentroids);
        return activeCentroids;
    }

    /**
     * Returns whether a centroid is geometrically close enough to a query vector
     * to warrant scanning its partition.
     *
     * @param centroidId    the centroid to check
     * @param queryVector   the query vector
     * @param maxDistance    maximum L2 distance threshold for partition inclusion
     * @return true if the partition should be scanned
     */
    public boolean shouldScanPartition(int centroidId, float[] queryVector, float maxDistance) {
        if (centroidId < 0 || centroidId >= activeCentroids) return true; // safety: scan if unknown
        return l2Distance(queryVector, centroids[centroidId]) <= maxDistance;
    }

    /**
     * Returns the number of active centroids.
     */
    public int activeCentroids() {
        return activeCentroids;
    }

    /**
     * Returns the centroid vector for a given ID.
     */
    public float[] centroid(int id) {
        if (id < 0 || id >= activeCentroids) throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "centroidId", 0, activeCentroids - 1, id);
        return centroids[id].clone();
    }

    // ── Internal ──

    private void splitCentroid(int centroidId) {
        // Create a new centroid by perturbing the existing one
        int newId = activeCentroids++;
        for (int d = 0; d < dimensions; d++) {
            centroids[newId][d] = centroids[centroidId][d] + 0.01f * (d % 2 == 0 ? 1 : -1);
        }
    }

    private float l2Distance(float[] a, float[] b) {
        float sum = 0f;
        for (int i = 0; i < a.length; i++) {
            float diff = a[i] - b[i];
            sum += diff * diff;
        }
        return (float) Math.sqrt(sum);
    }
}
