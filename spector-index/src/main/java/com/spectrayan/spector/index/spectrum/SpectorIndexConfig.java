package com.spectrayan.spector.index.spectrum;

import com.spectrayan.spector.core.similarity.SimilarityFunction;
import com.spectrayan.spector.config.HnswParams;

/**
 * Configuration for the {@link SpectorIndex} adaptive vector index.
 *
 * <h3>Adaptive Shard Strategy</h3>
 * <p>Each IVF centroid's shard operates in one of two modes:</p>
 * <ul>
 *   <li><b>Flat mode</b> (size &lt; {@code shardThreshold}): exhaustive SIMD scan over
 *       float32 residuals. For small shards, contiguous memory access outperforms HNSW
 *       pointer-chasing by 5–10×.</li>
 *   <li><b>HNSW mode</b> (size ≥ {@code shardThreshold}): a local VASQ-quantized HNSW
 *       graph is built from the accumulated residuals. Flat float32 storage is released.</li>
 * </ul>
 *
 * <h3>Residual Quantization</h3>
 * <p>Vectors are stored as residuals ({@code r = x − centroid}) and quantized with VASQ.
 * Residuals are much tighter than absolute coordinates, giving INT8 residual quantization
 * the spatial precision of INT12–INT16 absolute quantization.</p>
 *
 * @param nCentroids         number of IVF Voronoi cells (clusters)
 * @param nProbe             number of closest cells to probe at query time (≥ 16 for 95%+ recall)
 * @param shardThreshold     shard size at which flat scan promotes to HNSW (default: 20 000)
 * @param oversamplingFactor HNSW oversampling for VASQ re-ranking (default: 3)
 * @param kMeansIterations   K-Means++ iterations for centroid training (default: 25)
 * @param similarityFunction distance metric to use throughout
 * @param hnswParams         HNSW construction/search params for promoted shards
 */
public record SpectorIndexConfig(
        int nCentroids,
        int nProbe,
        int shardThreshold,
        int oversamplingFactor,
        int kMeansIterations,
        SimilarityFunction similarityFunction,
        HnswParams hnswParams
) {

    /**
     * Default configuration: 256 centroids, nprobe=16, flat→HNSW at 20K vectors,
     * 3× VASQ oversampling, 25 K-Means iterations, cosine similarity, standard HNSW params.
     */
    public static final SpectorIndexConfig DEFAULT = new SpectorIndexConfig(
            256, 16, 20_000, 3, 25,
            SimilarityFunction.COSINE,
            HnswParams.DEFAULT
    );

    public SpectorIndexConfig {
        if (nCentroids < 2)
            throw new IllegalArgumentException("nCentroids must be ≥ 2, got " + nCentroids);
        if (nProbe < 1 || nProbe > nCentroids)
            throw new IllegalArgumentException("nProbe must be in [1, nCentroids], got " + nProbe);
        if (shardThreshold < 1)
            throw new IllegalArgumentException("shardThreshold must be ≥ 1, got " + shardThreshold);
        if (oversamplingFactor < 1)
            throw new IllegalArgumentException("oversamplingFactor must be ≥ 1, got " + oversamplingFactor);
        if (kMeansIterations < 1)
            throw new IllegalArgumentException("kMeansIterations must be ≥ 1, got " + kMeansIterations);
        if (similarityFunction == null)
            throw new NullPointerException("similarityFunction must not be null");
        if (hnswParams == null)
            throw new NullPointerException("hnswParams must not be null");
    }

    /** Returns a copy with a different {@code nProbe}. */
    public SpectorIndexConfig withNProbe(int newNProbe) {
        return new SpectorIndexConfig(nCentroids, newNProbe, shardThreshold, oversamplingFactor,
                kMeansIterations, similarityFunction, hnswParams);
    }

    /** Returns a copy with a different {@code shardThreshold}. */
    public SpectorIndexConfig withShardThreshold(int newThreshold) {
        return new SpectorIndexConfig(nCentroids, nProbe, newThreshold, oversamplingFactor,
                kMeansIterations, similarityFunction, hnswParams);
    }

    /** Returns a copy with a different {@code oversamplingFactor}. */
    public SpectorIndexConfig withOversamplingFactor(int newFactor) {
        return new SpectorIndexConfig(nCentroids, nProbe, shardThreshold, newFactor,
                kMeansIterations, similarityFunction, hnswParams);
    }
}
