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

import com.spectrayan.spector.commons.error.SpectorException;

import com.spectrayan.spector.commons.error.SpectorValidationException;


import com.spectrayan.spector.config.HnswParams;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spectrayan.spector.core.similarity.SimilarityFunction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * Tests for {@link HnswIndex}.
 */
class HnswIndexTest {

    private static final int DIM = 32;

    @Test
    void emptyIndexReturnsNoResults() {
        try (var idx = new HnswIndex(DIM, 100, SimilarityFunction.COSINE)) {
            ScoredResult[] results = idx.search(randomVector(DIM, 1), 10);
            assertThat(results).isEmpty();
        }
    }

    @Test
    void singleVectorSearch() {
        try (var idx = new HnswIndex(DIM, 100, SimilarityFunction.COSINE)) {
            float[] v = randomVector(DIM, 42);
            idx.add("doc-0", 0, v);

            ScoredResult[] results = idx.search(v, 1);
            assertThat(results).hasSize(1);
            assertThat(results[0].id()).isEqualTo("doc-0");
            assertThat(results[0].score()).isGreaterThan(0.99f);
        }
    }

    @ParameterizedTest
    @EnumSource(SimilarityFunction.class)
    void findsSelfAsTopResult(SimilarityFunction sim) {
        try (var idx = new HnswIndex(DIM, 1000, sim, new HnswParams(16, 100, 100))) {
            Random rng = new Random(42);
            for (int i = 0; i < 100; i++) {
                idx.add("doc-" + i, i, randomVector(DIM, rng));
            }

            // Search for the exact vector at index 42
            float[] query = randomVector(DIM, new Random(42));
            // Skip 42 vectors to match
            for (int i = 0; i < 42; i++) randomVector(DIM, new Random(42));
            // Actually, rebuild the exact vector
            Random rng2 = new Random(42);
            float[] target = null;
            for (int i = 0; i <= 42; i++) {
                target = randomVector(DIM, rng2);
            }

            ScoredResult[] results = idx.search(target, 5);
            assertThat(results).isNotEmpty();
            assertThat(results[0].id()).isEqualTo("doc-42");
        }
    }

    @Test
    void cosineRecallAtK() {
        int n = 500;
        int k = 10;
        int dim = 64;
        var params = new HnswParams(16, 200, 100);

        try (var idx = new HnswIndex(dim, n, SimilarityFunction.COSINE, params)) {
            float[][] allVectors = new float[n][];
            Random rng = new Random(42);

            for (int i = 0; i < n; i++) {
                allVectors[i] = randomVector(dim, rng);
                idx.add("doc-" + i, i, allVectors[i]);
            }

            // Compute true top-K via brute force
            float[] query = randomVector(dim, new Random(999));
            Set<String> trueTopK = bruteForceTopK(allVectors, query, k, SimilarityFunction.COSINE);

            // HNSW search
            ScoredResult[] results = idx.search(query, k);
            Set<String> hnswTopK = new HashSet<>();
            for (var r : results) hnswTopK.add(r.id());

            // Count overlap
            int hits = 0;
            for (String id : trueTopK) {
                if (hnswTopK.contains(id)) hits++;
            }
            float recall = (float) hits / k;

            assertThat(recall).as("Recall@%d should be >= 0.8", k)
                    .isGreaterThanOrEqualTo(0.8f);
        }
    }

    @Test
    void euclideanRecallAtK() {
        int n = 500;
        int k = 10;
        int dim = 64;
        var params = new HnswParams(16, 200, 100);

        try (var idx = new HnswIndex(dim, n, SimilarityFunction.EUCLIDEAN, params)) {
            float[][] allVectors = new float[n][];
            Random rng = new Random(42);

            for (int i = 0; i < n; i++) {
                allVectors[i] = randomVector(dim, rng);
                idx.add("doc-" + i, i, allVectors[i]);
            }

            float[] query = randomVector(dim, new Random(999));
            Set<String> trueTopK = bruteForceTopK(allVectors, query, k, SimilarityFunction.EUCLIDEAN);

            ScoredResult[] results = idx.search(query, k);
            Set<String> hnswTopK = new HashSet<>();
            for (var r : results) hnswTopK.add(r.id());

            int hits = 0;
            for (String id : trueTopK) {
                if (hnswTopK.contains(id)) hits++;
            }
            float recall = (float) hits / k;

            assertThat(recall).as("Recall@%d should be >= 0.8", k)
                    .isGreaterThanOrEqualTo(0.8f);
        }
    }

    @Test
    void wrongDimensionsThrows() {
        try (var idx = new HnswIndex(DIM, 100, SimilarityFunction.COSINE)) {
            assertThatThrownBy(() -> idx.add("x", 0, new float[DIM + 1]))
                    .isInstanceOf(SpectorValidationException.class);
        }
    }

    @Test
    void fullIndexThrows() {
        try (var idx = new HnswIndex(3, 2, SimilarityFunction.COSINE)) {
            idx.add("a", 0, new float[]{1, 0, 0});
            idx.add("b", 1, new float[]{0, 1, 0});
            assertThatThrownBy(() -> idx.add("c", 2, new float[]{0, 0, 1}))
                    .isInstanceOf(SpectorException.class);
        }
    }

    @Test
    void sizeTracking() {
        try (var idx = new HnswIndex(DIM, 100, SimilarityFunction.COSINE)) {
            assertThat(idx.size()).isEqualTo(0);
            idx.add("a", 0, randomVector(DIM, 1));
            assertThat(idx.size()).isEqualTo(1);
            idx.add("b", 1, randomVector(DIM, 2));
            assertThat(idx.size()).isEqualTo(2);
        }
    }

    @Test
    void resultsAreSortedBestFirst() {
        try (var idx = new HnswIndex(DIM, 100, SimilarityFunction.COSINE)) {
            Random rng = new Random(42);
            for (int i = 0; i < 50; i++) {
                idx.add("doc-" + i, i, randomVector(DIM, rng));
            }

            ScoredResult[] results = idx.search(randomVector(DIM, new Random(99)), 10);
            for (int i = 1; i < results.length; i++) {
                assertThat(results[i - 1].score())
                        .as("Results should be sorted descending for cosine")
                        .isGreaterThanOrEqualTo(results[i].score());
            }
        }
    }

    // ─────────────── Helpers ───────────────

    private static Set<String> bruteForceTopK(float[][] vectors, float[] query, int k, SimilarityFunction sim) {
        record Pair(String id, float score) {}
        Pair[] pairs = new Pair[vectors.length];
        for (int i = 0; i < vectors.length; i++) {
            pairs[i] = new Pair("doc-" + i, sim.compute(query, vectors[i]));
        }

        if (sim.higherIsBetter()) {
            java.util.Arrays.sort(pairs, (a, b) -> Float.compare(b.score, a.score));
        } else {
            java.util.Arrays.sort(pairs, (a, b) -> Float.compare(a.score, b.score));
        }

        Set<String> topK = new HashSet<>();
        for (int i = 0; i < k && i < pairs.length; i++) {
            topK.add(pairs[i].id);
        }
        return topK;
    }

    private static float[] randomVector(int dim, long seed) {
        return randomVector(dim, new Random(seed));
    }

    private static float[] randomVector(int dim, Random rng) {
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) v[i] = rng.nextFloat() * 2f - 1f;
        return v;
    }
}
