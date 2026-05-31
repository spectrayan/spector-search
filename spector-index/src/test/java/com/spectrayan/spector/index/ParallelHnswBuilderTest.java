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
package com.spectrayan.spector.index;

import com.spectrayan.spector.commons.error.SpectorValidationException;


import com.spectrayan.spector.config.HnswParams;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

import com.spectrayan.spector.core.similarity.SimilarityFunction;

/**
 * Unit tests for {@link ParallelHnswBuilder}.
 */
class ParallelHnswBuilderTest {

    private final ParallelHnswBuilder builder = new ParallelHnswBuilder();

    @Test
    void sequentialBuild_smallDataset() {
        int n = 500;
        int dims = 32;
        float[][] vectors = randomVectors(n, dims, 42L);

        HnswIndex index = builder.build(vectors, HnswParams.DEFAULT, SimilarityFunction.COSINE);

        assertThat(index.size()).isEqualTo(n);
        assertThat(index.dimensions()).isEqualTo(dims);

        // Search should return results
        ScoredResult[] results = index.search(vectors[0], 5);
        assertThat(results).hasSizeGreaterThanOrEqualTo(1);
        // The vector itself should be the top result
        assertThat(results[0].score()).isCloseTo(1.0f, org.assertj.core.data.Offset.offset(0.001f));
    }

    @Test
    void sequentialBuild_belowThreshold() {
        // Below PARALLEL_THRESHOLD should use sequential path
        int n = ParallelHnswBuilder.PARALLEL_THRESHOLD - 1;
        int dims = 16;
        float[][] vectors = randomVectors(n, dims, 123L);

        HnswIndex index = builder.build(vectors, HnswParams.DEFAULT, SimilarityFunction.COSINE);

        assertThat(index.size()).isEqualTo(n);
    }

    @Test
    void parallelBuild_aboveThreshold() {
        int n = ParallelHnswBuilder.PARALLEL_THRESHOLD + 100;
        int dims = 16;
        float[][] vectors = randomVectors(n, dims, 99L);

        HnswIndex index = builder.build(vectors, HnswParams.DEFAULT, SimilarityFunction.COSINE);

        assertThat(index.size()).isEqualTo(n);
        assertThat(index.dimensions()).isEqualTo(dims);

        // Verify graph connectivity: every node at layer 0 should have >= 1 neighbor
        for (int i = 0; i < n; i++) {
            int[] neighbors = index.getNeighborsAtLayer(i, 0);
            assertThat(neighbors.length)
                    .as("Node %d should have at least 1 neighbor at layer 0", i)
                    .isGreaterThanOrEqualTo(1);
        }

        // Verify max connections constraint
        HnswParams params = index.params();
        for (int i = 0; i < n; i++) {
            int[] layer0Neighbors = index.getNeighborsAtLayer(i, 0);
            assertThat(layer0Neighbors.length)
                    .as("Node %d should not exceed maxLevel0Connections", i)
                    .isLessThanOrEqualTo(params.maxLevel0Connections());

            int nodeLevel = index.getLevel(i);
            for (int l = 1; l <= nodeLevel; l++) {
                int[] upperNeighbors = index.getNeighborsAtLayer(i, l);
                assertThat(upperNeighbors.length)
                        .as("Node %d at layer %d should not exceed M", i, l)
                        .isLessThanOrEqualTo(params.m());
            }
        }
    }

    @Test
    void parallelBuild_searchReturnsRelevantResults() {
        int n = ParallelHnswBuilder.PARALLEL_THRESHOLD + 50;
        int dims = 32;
        float[][] vectors = randomVectors(n, dims, 77L);

        HnswIndex index = builder.build(vectors, HnswParams.DEFAULT, SimilarityFunction.COSINE);

        // Searching with an indexed vector should find it
        ScoredResult[] results = index.search(vectors[0], 10);
        assertThat(results).isNotEmpty();
        assertThat(results[0].score()).isCloseTo(1.0f, org.assertj.core.data.Offset.offset(0.01f));
    }

    @Test
    void parallelBuild_euclideanDistance() {
        int n = ParallelHnswBuilder.PARALLEL_THRESHOLD + 50;
        int dims = 16;
        float[][] vectors = randomVectors(n, dims, 55L);

        HnswIndex index = builder.build(vectors, HnswParams.DEFAULT, SimilarityFunction.EUCLIDEAN);

        assertThat(index.size()).isEqualTo(n);

        // Search should work
        ScoredResult[] results = index.search(vectors[0], 5);
        assertThat(results).isNotEmpty();
        // Exact match should have distance 0
        assertThat(results[0].score()).isCloseTo(0.0f, org.assertj.core.data.Offset.offset(0.001f));
    }

    @Test
    void build_nullVectors_throwsException() {
        assertThatThrownBy(() -> builder.build(null, HnswParams.DEFAULT, SimilarityFunction.COSINE))
                .isInstanceOf(SpectorValidationException.class);
    }

    @Test
    void build_emptyVectors_throwsException() {
        assertThatThrownBy(() -> builder.build(new float[0][], HnswParams.DEFAULT, SimilarityFunction.COSINE))
                .isInstanceOf(SpectorValidationException.class);
    }

    @Test
    void build_inconsistentDimensions_throwsException() {
        float[][] vectors = {
                new float[]{1.0f, 2.0f, 3.0f},
                new float[]{1.0f, 2.0f} // different dimensions
        };
        assertThatThrownBy(() -> builder.build(vectors, HnswParams.DEFAULT, SimilarityFunction.COSINE))
                .isInstanceOf(SpectorValidationException.class)
                .hasMessageContaining("dimensions");
    }

    // ─────────────── Helpers ───────────────

    private static float[][] randomVectors(int n, int dims, long seed) {
        Random rng = new Random(seed);
        float[][] vectors = new float[n][dims];
        for (int i = 0; i < n; i++) {
            float norm = 0;
            for (int d = 0; d < dims; d++) {
                vectors[i][d] = rng.nextFloat() * 2 - 1;
                norm += vectors[i][d] * vectors[i][d];
            }
            // Normalize for cosine similarity
            norm = (float) Math.sqrt(norm);
            for (int d = 0; d < dims; d++) {
                vectors[i][d] /= norm;
            }
        }
        return vectors;
    }
}
