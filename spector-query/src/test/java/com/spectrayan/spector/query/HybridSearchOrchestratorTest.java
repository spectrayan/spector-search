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
package com.spectrayan.spector.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.spectrayan.spector.core.similarity.SimilarityFunction;
import com.spectrayan.spector.index.BM25Index;
import com.spectrayan.spector.index.HnswIndex;
import com.spectrayan.spector.index.ScoredResult;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Random;

/**
 * Tests for {@link HybridSearchOrchestrator}.
 */
class HybridSearchOrchestratorTest {

    private static final int DIM = 32;
    private BM25Index bm25;
    private HnswIndex hnsw;

    @BeforeEach
    void setUp() {
        bm25 = new BM25Index();
        hnsw = new HnswIndex(DIM, 1000, SimilarityFunction.COSINE);
    }

    @AfterEach
    void tearDown() {
        bm25.close();
        hnsw.close();
    }

    @Test
    void keywordOnlyMode() {
        bm25.index("d1", "java programming language");
        bm25.index("d2", "python machine learning");

        var orch = new HybridSearchOrchestrator(bm25, hnsw);
        SearchResponse response = orch.search(SearchQuery.keyword("java", 10));

        assertThat(response.mode()).isEqualTo(SearchQuery.SearchMode.KEYWORD);
        assertThat(response.results()).hasSizeGreaterThanOrEqualTo(1);
        assertThat(response.results()[0].id()).isEqualTo("d1");
    }

    @Test
    void vectorOnlyMode() {
        float[] v = randomVector(DIM, 42);
        hnsw.add("d1", 0, v);
        hnsw.add("d2", 1, randomVector(DIM, 99));

        var orch = new HybridSearchOrchestrator(bm25, hnsw);
        SearchResponse response = orch.search(SearchQuery.vector(v, 10));

        assertThat(response.mode()).isEqualTo(SearchQuery.SearchMode.VECTOR);
        assertThat(response.results()).hasSizeGreaterThanOrEqualTo(1);
        assertThat(response.results()[0].id()).isEqualTo("d1");
    }

    @Test
    void hybridModeFusesBothResults() {
        // Index same docs in both indexes
        Random rng = new Random(42);
        String[] docs = {
                "java virtual machine performance optimization",
                "python machine learning deep neural networks",
                "java concurrent programming virtual threads",
                "database query optimization indexing",
                "search engine information retrieval"
        };

        for (int i = 0; i < docs.length; i++) {
            bm25.index("doc-" + i, docs[i]);
            hnsw.add("doc-" + i, i, randomVector(DIM, rng));
        }

        float[] queryVector = randomVector(DIM, new Random(99));
        var orch = new HybridSearchOrchestrator(bm25, hnsw);
        SearchResponse response = orch.search(
                SearchQuery.hybrid("java virtual", queryVector, 5));

        assertThat(response.mode()).isEqualTo(SearchQuery.SearchMode.HYBRID);
        assertThat(response.results()).isNotEmpty();
        assertThat(response.queryTimeMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void hybridFallsBackToKeywordWhenNoVector() {
        bm25.index("d1", "hello world");

        var orch = new HybridSearchOrchestrator(bm25, hnsw);
        SearchResponse response = orch.search(
                SearchQuery.hybrid("hello", null, 10));

        assertThat(response.results()).hasSizeGreaterThanOrEqualTo(1);
    }

    @Test
    void hybridFallsBackToVectorWhenNoText() {
        float[] v = randomVector(DIM, 42);
        hnsw.add("d1", 0, v);

        var orch = new HybridSearchOrchestrator(bm25, hnsw);
        SearchResponse response = orch.search(
                SearchQuery.hybrid(null, v, 10));

        assertThat(response.results()).hasSizeGreaterThanOrEqualTo(1);
    }

    @Test
    void emptyIndexesReturnEmpty() {
        var orch = new HybridSearchOrchestrator(bm25, hnsw);
        SearchResponse response = orch.search(SearchQuery.keyword("nothing", 10));
        assertThat(response.results()).isEmpty();
    }

    @Test
    void nullIndexesHandledGracefully() {
        var orch = new HybridSearchOrchestrator(null, null);
        SearchResponse response = orch.search(SearchQuery.keyword("test", 10));
        assertThat(response.results()).isEmpty();
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
