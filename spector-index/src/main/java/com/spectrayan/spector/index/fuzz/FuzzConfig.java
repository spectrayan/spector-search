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
package com.spectrayan.spector.index.fuzz;

import java.nio.file.Path;
import java.util.List;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;

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
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "minOperations", 10000, Integer.MAX_VALUE, minOperations);
        }
        if (targetIndexes == null || targetIndexes.isEmpty()) {
            throw new SpectorValidationException(ErrorCode.EMPTY_COLLECTION, "targetIndexes");
        }
        if (dimensions < 2) {
            throw new SpectorValidationException(ErrorCode.DIMENSIONS_INVALID, dimensions);
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
