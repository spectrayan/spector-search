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

import static org.assertj.core.api.Assertions.assertThat;

import com.spectrayan.spector.embed.TokenEmbeddingProvider;
import com.spectrayan.spector.embed.TokenEmbeddingResult;
import com.spectrayan.spector.index.ColBERTReranker.RerankCandidate;
import com.spectrayan.spector.index.ColBERTReranker.RerankResult;

import org.junit.jupiter.api.*;

import java.util.*;

/**
 * Performance benchmarks for SPLADE/ColBERT/SIMD retrieval components.
 *
 * <p>Follows the same pattern as {@code PerformanceBenchmarkTest}: JIT warm-up,
 * then timed iterations with wall-clock assertions.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RetrievalBenchmarkTest {

    // ══════════════════════════════════════════════════════════════
    // SIMD Score Accumulator
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("SIMD addArrays — 100K elements under 1ms")
    void simd_addArrays_100K_under_1ms() {
        int n = 100_000;
        float[] dst = new float[n];
        float[] src = new float[n];
        Random rng = new Random(42);
        for (int i = 0; i < n; i++) {
            dst[i] = rng.nextFloat();
            src[i] = rng.nextFloat();
        }

        // Warm up
        for (int i = 0; i < 5_000; i++) {
            SIMDScoreAccumulator.addArrays(dst, src, n);
        }

        // Reset dst
        for (int i = 0; i < n; i++) dst[i] = rng.nextFloat();

        long start = System.nanoTime();
        SIMDScoreAccumulator.addArrays(dst, src, n);
        long elapsed = System.nanoTime() - start;

        System.out.printf("SIMD addArrays 100K: %,d µs%n", elapsed / 1000);
        assertThat(elapsed / 1_000_000).as("100K addArrays < 1ms").isLessThan(1);
    }

    @Test
    @Order(2)
    @DisplayName("SIMD fmaArrays — 100K elements under 1ms")
    void simd_fmaArrays_100K_under_1ms() {
        int n = 100_000;
        float[] dst = new float[n];
        float[] src = new float[n];
        Random rng = new Random(42);
        for (int i = 0; i < n; i++) {
            dst[i] = rng.nextFloat();
            src[i] = rng.nextFloat();
        }

        // Warm up
        for (int i = 0; i < 5_000; i++) {
            SIMDScoreAccumulator.fmaArrays(dst, src, 0.5f, n);
        }

        for (int i = 0; i < n; i++) dst[i] = rng.nextFloat();

        long start = System.nanoTime();
        SIMDScoreAccumulator.fmaArrays(dst, src, 0.5f, n);
        long elapsed = System.nanoTime() - start;

        System.out.printf("SIMD fmaArrays 100K: %,d µs%n", elapsed / 1000);
        assertThat(elapsed / 1_000_000).as("100K fmaArrays < 1ms").isLessThan(1);
    }

    @Test
    @Order(3)
    @DisplayName("SIMD maxValue — 100K elements under 500µs")
    void simd_maxValue_100K_under_500us() {
        int n = 100_000;
        float[] arr = new float[n];
        Random rng = new Random(42);
        for (int i = 0; i < n; i++) arr[i] = rng.nextFloat();

        // Warm up
        for (int i = 0; i < 5_000; i++) {
            SIMDScoreAccumulator.maxValue(arr, n);
        }

        long start = System.nanoTime();
        float max = SIMDScoreAccumulator.maxValue(arr, n);
        long elapsed = System.nanoTime() - start;

        System.out.printf("SIMD maxValue 100K: %,d µs (max=%.4f)%n", elapsed / 1000, max);
        assertThat(elapsed / 1000).as("100K maxValue < 500µs").isLessThan(500);
    }

    // ══════════════════════════════════════════════════════════════
    // SpladeIndex
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(4)
    @DisplayName("SpladeIndex — 10K docs, single query under 5ms")
    void splade_10K_search_under_5ms() {
        SpladeIndex index = new SpladeIndex();
        Random rng = new Random(42);

        // Build 10K docs with ~100 terms each
        for (int d = 0; d < 10_000; d++) {
            Map<String, Float> sparse = new HashMap<>();
            for (int t = 0; t < 100; t++) {
                sparse.put("term-" + rng.nextInt(5000), rng.nextFloat() * 3.0f);
            }
            index.indexSparse("doc-" + d, sparse);
        }

        // Build query with 20 terms
        Map<String, Float> query = new HashMap<>();
        for (int t = 0; t < 20; t++) {
            query.put("term-" + rng.nextInt(5000), rng.nextFloat() * 2.0f);
        }

        // Warm up
        for (int i = 0; i < 100; i++) {
            index.searchSparse(query, 10);
        }

        long start = System.nanoTime();
        ScoredResult[] results = index.searchSparse(query, 10);
        long elapsed = System.nanoTime() - start;

        System.out.printf("SpladeIndex 10K search: %,d µs → %d results%n",
                elapsed / 1000, results.length);
        assertThat(elapsed / 1_000_000).as("10K SPLADE search < 5ms").isLessThan(5);
        assertThat(results).isNotEmpty();

        index.close();
    }

    @Test
    @Order(5)
    @DisplayName("SpladeIndex — bulk index 1K docs × 100 terms under 500ms")
    void splade_bulkIndex_1K_under_500ms() {
        SpladeIndex index = new SpladeIndex();
        Random rng = new Random(42);

        List<Map<String, Float>> sparseVecs = new ArrayList<>(1000);
        for (int d = 0; d < 1000; d++) {
            Map<String, Float> sparse = new HashMap<>();
            for (int t = 0; t < 100; t++) {
                sparse.put("term-" + rng.nextInt(5000), rng.nextFloat() * 3.0f);
            }
            sparseVecs.add(sparse);
        }

        long start = System.nanoTime();
        for (int d = 0; d < 1000; d++) {
            index.indexSparse("doc-" + d, sparseVecs.get(d));
        }
        long elapsed = System.nanoTime() - start;

        System.out.printf("SpladeIndex bulk 1K×100: %,d ms (%d docs)%n",
                elapsed / 1_000_000, index.size());
        assertThat(elapsed / 1_000_000).as("1K bulk index < 500ms").isLessThan(500);
        assertThat(index.size()).isEqualTo(1000);

        index.close();
    }

    // ══════════════════════════════════════════════════════════════
    // ColBERT MaxSim
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(6)
    @DisplayName("ColBERT MaxSim — rerank 50 candidates × 128-dim under 50ms")
    void colbert_maxSim_50_under_50ms() {
        int tokenDims = 128;
        var provider = new BenchmarkTokenProvider(tokenDims);
        var reranker = new ColBERTReranker(provider);

        // Build 50 candidates with varying text length
        List<RerankCandidate> candidates = new ArrayList<>(50);
        for (int i = 0; i < 50; i++) {
            StringBuilder sb = new StringBuilder();
            for (int w = 0; w < 20 + i; w++) sb.append("word").append(w).append(" ");
            candidates.add(new RerankCandidate("doc-" + i, sb.toString(), 0.5f + i * 0.01f));
        }

        // Warm up
        for (int i = 0; i < 10; i++) {
            reranker.rerank("search query terms", candidates, 10);
        }

        long start = System.nanoTime();
        List<RerankResult> results = reranker.rerank("search query terms", candidates, 10);
        long elapsed = System.nanoTime() - start;

        System.out.printf("ColBERT rerank 50×%d-dim: %,d ms → %d results%n",
                tokenDims, elapsed / 1_000_000, results.size());
        assertThat(elapsed / 1_000_000).as("50 candidates reranking < 50ms").isLessThan(50);
        assertThat(results).hasSize(10);
    }

    // ── Benchmark token provider ──

    /**
     * Fast deterministic token embedding provider for benchmarks.
     * Uses hash-seeded random vectors — not unit-normalized (speed over accuracy).
     */
    static class BenchmarkTokenProvider implements TokenEmbeddingProvider {
        private final int dims;

        BenchmarkTokenProvider(int dims) {
            this.dims = dims;
        }

        @Override
        public TokenEmbeddingResult encode(String text) {
            String[] tokens = text.split("\\s+");
            float[][] embeddings = new float[tokens.length][dims];
            for (int t = 0; t < tokens.length; t++) {
                Random rng = new Random(tokens[t].hashCode());
                for (int d = 0; d < dims; d++) {
                    embeddings[t][d] = rng.nextFloat() - 0.5f;
                }
            }
            return new TokenEmbeddingResult(embeddings, tokens, tokens.length, "benchmark-" + dims);
        }

        @Override
        public int tokenDimensions() {
            return dims;
        }

        @Override
        public String modelName() {
            return "benchmark-" + dims;
        }
    }
}
