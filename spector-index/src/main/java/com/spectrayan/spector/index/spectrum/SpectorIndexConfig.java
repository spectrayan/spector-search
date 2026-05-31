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
package com.spectrayan.spector.index.spectrum;

import com.spectrayan.spector.core.similarity.SimilarityFunction;
import com.spectrayan.spector.config.HnswParams;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Configuration for the {@link SpectorIndex} adaptive vector index.
 *
 * <h3>Adaptive Shard Strategy</h3>
 * <p>Each IVF centroid's shard operates in one of two modes:</p>
 * <ul>
 *   <li><b>Flat mode</b> (size &lt; {@code shardThreshold}): exhaustive SIMD scan over
 *       float32 residuals. For small shards, contiguous memory access outperforms HNSW
 *       pointer-chasing by 5–10×.</li>
 *   <li><b>HNSW mode</b> (size ≥ {@code shardThreshold}): a local SVASQ-quantized HNSW
 *       graph is built from the accumulated residuals. Flat float32 storage is released.</li>
 * </ul>
 *
 * <h3>Residual Quantization</h3>
 * <p>Vectors are stored as residuals ({@code r = x − centroid}) and quantized with SVASQ.
 * Residuals are much tighter than absolute coordinates, giving INT8 residual quantization
 * the spatial precision of INT12–INT16 absolute quantization.</p>
 *
 * @param nCentroids         number of IVF Voronoi cells (clusters)
 * @param nProbe             number of closest cells to probe at query time (≥ 16 for 95%+ recall)
 * @param shardThreshold     shard size at which flat scan promotes to HNSW (default: 20 000)
 * @param oversamplingFactor HNSW oversampling for SVASQ re-ranking (default: 3)
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
     * 3× SVASQ oversampling, 25 K-Means iterations, cosine similarity, standard HNSW params.
     */
    public static final SpectorIndexConfig DEFAULT = new SpectorIndexConfig(
            256, 16, 20_000, 3, 25,
            SimilarityFunction.COSINE,
            HnswParams.DEFAULT
    );

    public SpectorIndexConfig {
        if (nCentroids < 2)
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "nCentroids", 2, Integer.MAX_VALUE, nCentroids);
        if (nProbe < 1 || nProbe > nCentroids)
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "nProbe", 1, 0, nProbe);
        if (shardThreshold < 1)
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "shardThreshold", 1, Integer.MAX_VALUE, shardThreshold);
        if (oversamplingFactor < 1)
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "oversamplingFactor", 1, Integer.MAX_VALUE, oversamplingFactor);
        if (kMeansIterations < 1)
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "kMeansIterations", 1, Integer.MAX_VALUE, kMeansIterations);
        if (similarityFunction == null)
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "similarityFunction");
        if (hnswParams == null)
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "hnswParams");
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
