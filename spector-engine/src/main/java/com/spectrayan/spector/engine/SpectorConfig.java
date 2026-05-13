package com.spectrayan.spector.engine;

import com.spectrayan.spector.core.SimilarityFunction;
import com.spectrayan.spector.index.HnswParams;

/**
 * Immutable configuration for a Spector Search engine instance.
 *
 * @param dimensions         vector dimensionality
 * @param capacity           max number of documents
 * @param similarityFunction distance/similarity metric for vectors
 * @param hnswParams         HNSW index tuning parameters
 */
public record SpectorConfig(
        int dimensions,
        int capacity,
        SimilarityFunction similarityFunction,
        HnswParams hnswParams
) {
    /** Default: 384-dim embeddings, 100K capacity, cosine similarity. */
    public static final SpectorConfig DEFAULT =
            new SpectorConfig(384, 100_000, SimilarityFunction.COSINE, HnswParams.DEFAULT);

    public SpectorConfig {
        if (dimensions <= 0) throw new IllegalArgumentException("dimensions must be positive");
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
    }

    /** Builder-style with custom dimensions. */
    public SpectorConfig withDimensions(int dims) {
        return new SpectorConfig(dims, capacity, similarityFunction, hnswParams);
    }

    /** Builder-style with custom capacity. */
    public SpectorConfig withCapacity(int cap) {
        return new SpectorConfig(dimensions, cap, similarityFunction, hnswParams);
    }

    /** Builder-style with custom similarity function. */
    public SpectorConfig withSimilarityFunction(SimilarityFunction sf) {
        return new SpectorConfig(dimensions, capacity, sf, hnswParams);
    }
}
