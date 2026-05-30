package com.spectrayan.spector.core.quantization;

import java.util.Arrays;

/**
 * Non-uniform (quantile-based) quantizer for INT4 and INT2 quantization.
 *
 * <p>Unlike linear quantization that spaces levels uniformly across [min, max],
 * this quantizer places boundaries at data quantiles so that each bucket
 * contains approximately the same number of sample values. This maximizes
 * information retention when only a few levels are available (4 or 16).</p>
 *
 * <h3>Calibration</h3>
 * <p>Call {@link #calibrate(float[][], int, int)} with a representative sample.
 * The quantizer computes per-dimension quantile boundaries and bucket centroids.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>A calibrated quantizer is immutable and safe for concurrent use.</p>
 */
public final class NonUniformQuantizer {

    private final int dimensions;
    private final int levels;
    private final float[][] boundaries; // [dimensions][levels] — upper boundaries per bucket
    private final float[][] centroids;  // [dimensions][levels] — centroid (mean) per bucket

    private NonUniformQuantizer(int dimensions, int levels,
                                 float[][] boundaries, float[][] centroids) {
        this.dimensions = dimensions;
        this.levels = levels;
        this.boundaries = boundaries;
        this.centroids = centroids;
    }

    /**
     * Calibrates quantile-based boundaries from sample vectors.
     *
     * <p>For each dimension, sorts the sample values and partitions them into
     * {@code levels} equal-frequency buckets. Boundaries are set at the bucket
     * edges and centroids are computed as the mean of values within each bucket.</p>
     *
     * @param sampleVectors representative sample of vectors
     * @param dimensions    vector dimensionality
     * @param levels        number of quantization levels (e.g. 16 for INT4, 4 for INT2)
     * @return a calibrated non-uniform quantizer
     * @throws IllegalArgumentException if sample is empty or null, or dimensions &lt; 1, or levels &lt; 2
     */
    public static NonUniformQuantizer calibrate(float[][] sampleVectors,
                                                 int dimensions, int levels) {
        if (sampleVectors == null || sampleVectors.length == 0) {
            throw new IllegalArgumentException(com.spectrayan.spector.commons.error.ErrorCode.EMPTY_COLLECTION.format("sampleVectors"));
        }
        if (dimensions < 1) {
            throw new IllegalArgumentException(com.spectrayan.spector.commons.error.ErrorCode.DIMENSIONS_INVALID.format(0));
        }
        if (levels < 2) {
            throw new IllegalArgumentException(com.spectrayan.spector.commons.error.ErrorCode.ARGUMENT_OUT_OF_RANGE.format("levels", 2, Integer.MAX_VALUE, 0));
        }

        for (float[] vector : sampleVectors) {
            if (vector.length != dimensions) {
                throw new IllegalArgumentException(
                        "Expected " + dimensions + " dims, got " + vector.length);
            }
        }

        int n = sampleVectors.length;
        float[][] boundariesResult = new float[dimensions][levels];
        float[][] centroidsResult = new float[dimensions][levels];

        float[] dimValues = new float[n];

        for (int d = 0; d < dimensions; d++) {
            // Collect all values for this dimension
            for (int i = 0; i < n; i++) {
                dimValues[i] = sampleVectors[i][d];
            }
            Arrays.sort(dimValues);

            if (n >= levels) {
                // Normal case: partition into equal-frequency buckets
                for (int l = 0; l < levels; l++) {
                    int bucketStart = (int) ((long) l * n / levels);
                    int bucketEnd = (int) ((long) (l + 1) * n / levels);

                    // Boundary is the max value in this bucket
                    boundariesResult[d][l] = dimValues[bucketEnd - 1];

                    // Centroid is the mean of values in this bucket
                    double sum = 0.0;
                    for (int i = bucketStart; i < bucketEnd; i++) {
                        sum += dimValues[i];
                    }
                    centroidsResult[d][l] = (float) (sum / (bucketEnd - bucketStart));
                }
            } else {
                // Fewer samples than levels: spread available values across levels
                // and interpolate the rest
                float minVal = dimValues[0];
                float maxVal = dimValues[n - 1];
                float range = maxVal - minVal;

                for (int l = 0; l < levels; l++) {
                    if (range < 1e-10f) {
                        // All values are the same
                        boundariesResult[d][l] = minVal;
                        centroidsResult[d][l] = minVal;
                    } else {
                        // Linearly interpolate boundaries across the range
                        float t = (float) (l + 1) / levels;
                        boundariesResult[d][l] = minVal + t * range;
                        // Centroid is midpoint of this bucket
                        float bucketStart = (l == 0) ? minVal : boundariesResult[d][l - 1];
                        centroidsResult[d][l] = (bucketStart + boundariesResult[d][l]) / 2.0f;
                    }
                }
            }
        }

        return new NonUniformQuantizer(dimensions, levels, boundariesResult, centroidsResult);
    }

    /**
     * Encodes a float vector to quantized level indices.
     *
     * <p>For each dimension, finds the boundary interval closest to the input value.
     * Out-of-range values are clamped to 0 (below min) or levels-1 (above max).</p>
     *
     * @param vector the input float vector
     * @return array of quantized level indices, each in [0, levels-1]
     * @throws IllegalArgumentException if vector length does not match dimensions
     */
    public int[] encode(float[] vector) {
        if (vector.length != dimensions) {
            throw new IllegalArgumentException(
                    "Expected " + dimensions + " dims, got " + vector.length);
        }

        int[] result = new int[dimensions];
        for (int d = 0; d < dimensions; d++) {
            result[d] = encodeValue(vector[d], d);
        }
        return result;
    }

    /**
     * Decodes quantized level indices back to float centroids.
     *
     * <p>Each level index is mapped to its corresponding bucket centroid.</p>
     *
     * @param quantized array of level indices
     * @return reconstructed float vector using bucket centroids
     * @throws IllegalArgumentException if quantized length does not match dimensions
     */
    public float[] decode(int[] quantized) {
        if (quantized.length != dimensions) {
            throw new IllegalArgumentException(
                    "Expected " + dimensions + " dims, got " + quantized.length);
        }

        float[] result = new float[dimensions];
        for (int d = 0; d < dimensions; d++) {
            int level = Math.max(0, Math.min(levels - 1, quantized[d]));
            result[d] = centroids[d][level];
        }
        return result;
    }

    /**
     * Returns the boundaries for a given dimension.
     *
     * @param dimension the dimension index
     * @return copy of the boundary array for that dimension
     * @throws IndexOutOfBoundsException if dimension is out of range
     */
    public float[] boundaries(int dimension) {
        if (dimension < 0 || dimension >= dimensions) {
            throw new IndexOutOfBoundsException(
                    "Dimension " + dimension + " out of range [0, " + (dimensions - 1) + "]");
        }
        return Arrays.copyOf(boundaries[dimension], levels);
    }

    /**
     * Returns the centroids for a given dimension.
     *
     * @param dimension the dimension index
     * @return copy of the centroid array for that dimension
     * @throws IndexOutOfBoundsException if dimension is out of range
     */
    public float[] centroids(int dimension) {
        if (dimension < 0 || dimension >= dimensions) {
            throw new IndexOutOfBoundsException(
                    "Dimension " + dimension + " out of range [0, " + (dimensions - 1) + "]");
        }
        return Arrays.copyOf(centroids[dimension], levels);
    }

    /** Returns the number of dimensions. */
    public int dimensions() {
        return dimensions;
    }

    /** Returns the number of quantization levels. */
    public int levels() {
        return levels;
    }

    /**
     * Encodes a single value for a given dimension by finding the nearest boundary interval.
     * Clamps out-of-range values to 0 or levels-1.
     */
    private int encodeValue(float value, int dimension) {
        float[] dimBounds = boundaries[dimension];

        // If value is at or below the first boundary, assign level 0
        if (value <= dimBounds[0]) {
            // Check if it's closer to level 0 centroid or still in range
            return 0;
        }

        // If value is above the last boundary, clamp to max level
        if (value > dimBounds[levels - 1]) {
            return levels - 1;
        }

        // Binary search for the correct bucket
        // Find the first boundary >= value
        int lo = 0;
        int hi = levels - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (dimBounds[mid] < value) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }

        // lo is the first bucket whose boundary >= value
        // Check if the value is closer to lo's centroid or (lo-1)'s centroid
        if (lo > 0) {
            float distToLo = Math.abs(value - centroids[dimension][lo]);
            float distToPrev = Math.abs(value - centroids[dimension][lo - 1]);
            if (distToPrev < distToLo) {
                return lo - 1;
            }
        }

        return lo;
    }
}
