package com.spectrayan.spector.index.fuzz;

import java.nio.file.Path;
import java.util.List;

/**
 * Configuration for an index fuzz testing run.
 *
 * @param minOperations minimum number of random operations to execute per run (≥10,000)
 * @param seed          random seed for reproducibility
 * @param targetIndexes which index types to exercise
 * @param dimensions    base vector dimensionality for generated vectors
 * @param outputDir     directory to persist reproducing inputs and crash state
 */
public record FuzzConfig(
        int minOperations,
        long seed,
        List<IndexType> targetIndexes,
        int dimensions,
        Path outputDir
) {
    public FuzzConfig {
        if (minOperations < 10_000) {
            throw new IllegalArgumentException("minOperations must be at least 10,000, got " + minOperations);
        }
        if (targetIndexes == null || targetIndexes.isEmpty()) {
            throw new IllegalArgumentException("targetIndexes must not be empty");
        }
        if (dimensions < 2) {
            throw new IllegalArgumentException("dimensions must be at least 2, got " + dimensions);
        }
    }

    /**
     * Creates a default config suitable for CI testing.
     */
    public static FuzzConfig defaultConfig(Path outputDir) {
        return new FuzzConfig(
                10_000,
                System.nanoTime(),
                List.of(IndexType.HNSW, IndexType.IVF_FLAT),
                32,
                outputDir
        );
    }
}
