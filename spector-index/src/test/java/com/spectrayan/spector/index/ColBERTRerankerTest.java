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
import static org.assertj.core.api.Assertions.within;

import com.spectrayan.spector.embed.TokenEmbeddingProvider;
import com.spectrayan.spector.embed.TokenEmbeddingResult;
import com.spectrayan.spector.index.ColBERTReranker.RerankCandidate;
import com.spectrayan.spector.index.ColBERTReranker.RerankResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

/**
 * Tests for {@link ColBERTReranker}.
 *
 * <p>Covers MaxSim scoring, reranking with various alpha values,
 * edge cases (empty candidates, zero tokens, provider exceptions),
 * and mathematical correctness.</p>
 */
class ColBERTRerankerTest {

    private static final int TOKEN_DIMS = 128;

    private ColBERTReranker reranker;

    @BeforeEach
    void setUp() {
        reranker = new ColBERTReranker(new MockTokenEmbeddingProvider(TOKEN_DIMS));
    }

    // ══════════════════════════════════════════════════════════════
    // Reranking — happy path
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Rerank — candidate with higher MaxSim moves to top")
    void rerank_orderChanges() {
        // "java virtual machine" should match "java virtual" query better
        // than "python programming" because of token overlap
        var candidates = List.of(
                new RerankCandidate("python-doc", "python programming language", 0.9f),
                new RerankCandidate("java-doc", "java virtual machine performance", 0.5f)
        );

        List<RerankResult> results = reranker.rerank("java virtual", candidates, 10);

        assertThat(results).hasSize(2);
        // java-doc should be ranked higher after ColBERT reranking
        // due to token-level overlap with the query
        assertThat(results.getFirst().id()).isEqualTo("java-doc");
    }

    @Test
    @DisplayName("Rerank — combined score formula: α·maxSim + (1-α)·firstStage")
    void rerank_combinesScores() {
        var candidates = List.of(
                new RerankCandidate("d1", "exact match terms", 0.8f)
        );

        List<RerankResult> results = reranker.rerank("exact match terms", candidates, 10, 0.5f);

        assertThat(results).hasSize(1);
        RerankResult r = results.getFirst();
        float expected = 0.5f * r.maxSimScore() + 0.5f * r.firstStageScore();
        assertThat(r.combinedScore()).isCloseTo(expected, within(1e-5f));
    }

    @Test
    @DisplayName("Rerank — α=0.0 uses first-stage only")
    void rerank_alpha0_firstStageOnly() {
        var candidates = List.of(
                new RerankCandidate("high-first", "unrelated text", 0.9f),
                new RerankCandidate("low-first", "the exact query terms here", 0.1f)
        );

        List<RerankResult> results = reranker.rerank("exact query terms", candidates, 10, 0.0f);

        // α=0 means only first-stage score matters
        assertThat(results.getFirst().id()).isEqualTo("high-first");
    }

    @Test
    @DisplayName("Rerank — α=1.0 uses MaxSim only")
    void rerank_alpha1_maxSimOnly() {
        var candidates = List.of(
                new RerankCandidate("high-first", "unrelated text", 0.9f),
                new RerankCandidate("low-first", "matching query exactly", 0.1f)
        );

        List<RerankResult> results = reranker.rerank("matching query exactly", candidates, 10, 1.0f);

        // α=1 means only ColBERT MaxSim matters
        assertThat(results.getFirst().id()).isEqualTo("low-first");
        assertThat(results.getFirst().combinedScore())
                .isCloseTo(results.getFirst().maxSimScore(), within(1e-5f));
    }

    @Test
    @DisplayName("Rerank — topK limits results")
    void rerank_topKLimits() {
        var candidates = List.of(
                new RerankCandidate("d1", "text one", 0.9f),
                new RerankCandidate("d2", "text two", 0.8f),
                new RerankCandidate("d3", "text three", 0.7f),
                new RerankCandidate("d4", "text four", 0.6f),
                new RerankCandidate("d5", "text five", 0.5f)
        );

        List<RerankResult> results = reranker.rerank("text", candidates, 3);
        assertThat(results).hasSize(3);
    }

    // ══════════════════════════════════════════════════════════════
    // Negative / Edge cases
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Rerank — empty candidates returns empty")
    void rerank_emptyCandidates() {
        List<RerankResult> results = reranker.rerank("query", List.of(), 10);
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("Rerank — single candidate returned with combined score")
    void rerank_singleCandidate() {
        var candidates = List.of(
                new RerankCandidate("only", "single document text", 0.7f)
        );

        List<RerankResult> results = reranker.rerank("single document", candidates, 10);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().combinedScore()).isGreaterThan(0);
    }

    @Test
    @DisplayName("Rerank — provider exception keeps first-stage score")
    void rerank_providerException() {
        var failingReranker = new ColBERTReranker(new FailingTokenEmbeddingProvider());

        var candidates = List.of(
                new RerankCandidate("d1", "some text", 0.8f)
        );

        // Should not throw — graceful degradation
        List<RerankResult> results = failingReranker.rerank("query", candidates, 10);
        assertThat(results).hasSize(1);
        // MaxSim should be 0 (provider failed), so combined = (1-α) * firstStage
        assertThat(results.getFirst().maxSimScore()).isEqualTo(0f);
    }

    // ══════════════════════════════════════════════════════════════
    // MaxSim scoring — mathematical correctness
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("maxSimScore — identical vectors → score = numTokens")
    void maxSimScore_identicalVectors() {
        // Each token vector dot-product with itself = 1.0 (unit vectors)
        float[][] tokens = makeUnitVectors(3, 128);
        float score = ColBERTReranker.maxSimScore(tokens, tokens);
        // sum of max dot products for each query token = 3 × 1.0 = 3.0
        assertThat(score).isCloseTo(3.0f, within(0.01f));
    }

    @Test
    @DisplayName("maxSimScore — orthogonal vectors → score ≈ 0")
    void maxSimScore_orthogonalVectors() {
        // Construct two orthogonal sets by using different basis vectors
        float[][] qTokens = {{1, 0, 0, 0}, {0, 1, 0, 0}};
        float[][] dTokens = {{0, 0, 1, 0}, {0, 0, 0, 1}};

        float score = ColBERTReranker.maxSimScore(qTokens, dTokens);
        assertThat(score).isCloseTo(0.0f, within(1e-6f));
    }

    @Test
    @DisplayName("maxSimScore — single token each → equals dot product")
    void maxSimScore_singleTokenEach() {
        float[][] qTokens = {{1.0f, 2.0f, 3.0f}};
        float[][] dTokens = {{4.0f, 5.0f, 6.0f}};

        float score = ColBERTReranker.maxSimScore(qTokens, dTokens);
        // dot product = 1*4 + 2*5 + 3*6 = 4 + 10 + 18 = 32
        assertThat(score).isCloseTo(32.0f, within(0.01f));
    }

    // ══════════════════════════════════════════════════════════════
    // SIMD dot product — correctness
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("simdDotProduct — matches scalar reference for 128-dim")
    void simdDotProduct_matchesScalar() {
        int dims = 128;
        Random rng = new Random(42);
        float[] a = new float[dims], b = new float[dims];
        for (int i = 0; i < dims; i++) {
            a[i] = rng.nextFloat() - 0.5f;
            b[i] = rng.nextFloat() - 0.5f;
        }

        float simdResult = ColBERTReranker.simdDotProduct(a, b);

        // Scalar reference
        float scalarResult = 0;
        for (int i = 0; i < dims; i++) scalarResult += a[i] * b[i];

        assertThat(simdResult).isCloseTo(scalarResult, within(1e-4f));
    }

    @Test
    @DisplayName("simdDotProduct — empty vectors → 0.0")
    void simdDotProduct_emptyVectors() {
        assertThat(ColBERTReranker.simdDotProduct(new float[0], new float[0]))
                .isEqualTo(0.0f);
    }

    // ── Test helpers ──

    /** Creates n unit vectors of given dimensionality. */
    private static float[][] makeUnitVectors(int n, int dims) {
        float[][] vecs = new float[n][dims];
        Random rng = new Random(123);
        for (int t = 0; t < n; t++) {
            float norm = 0;
            for (int d = 0; d < dims; d++) {
                vecs[t][d] = rng.nextFloat() - 0.5f;
                norm += vecs[t][d] * vecs[t][d];
            }
            norm = (float) Math.sqrt(norm);
            for (int d = 0; d < dims; d++) vecs[t][d] /= norm;
        }
        return vecs;
    }

    // ── Mock providers ──

    /**
     * Deterministic mock that produces hash-seeded, normalized per-token embeddings.
     * <p>Same text always produces the same embeddings for reproducible tests.</p>
     */
    static class MockTokenEmbeddingProvider implements TokenEmbeddingProvider {
        private final int dims;

        MockTokenEmbeddingProvider(int dims) {
            this.dims = dims;
        }

        @Override
        public TokenEmbeddingResult encode(String text) {
            String[] tokens = text.split("\\s+");
            float[][] embeddings = new float[tokens.length][dims];
            for (int t = 0; t < tokens.length; t++) {
                Random rng = new Random(tokens[t].hashCode());
                float norm = 0;
                for (int d = 0; d < dims; d++) {
                    embeddings[t][d] = rng.nextFloat() - 0.5f;
                    norm += embeddings[t][d] * embeddings[t][d];
                }
                norm = (float) Math.sqrt(norm);
                if (norm > 0) {
                    for (int d = 0; d < dims; d++) embeddings[t][d] /= norm;
                }
            }
            return new TokenEmbeddingResult(embeddings, tokens, tokens.length, "mock-colbert-" + dims);
        }

        @Override
        public int tokenDimensions() {
            return dims;
        }

        @Override
        public String modelName() {
            return "mock-colbert-" + dims;
        }
    }

    /**
     * Token embedding provider that always throws — for testing graceful degradation.
     */
    static class FailingTokenEmbeddingProvider implements TokenEmbeddingProvider {

        private boolean firstCall = true;

        @Override
        public TokenEmbeddingResult encode(String text) {
            // Fail on document encoding (not query encoding)
            if (firstCall) {
                firstCall = false;
                // Return valid query tokens
                return new TokenEmbeddingResult(
                        new float[][]{{1.0f, 0f, 0f}}, new String[]{"query"}, 1, "failing-mock");
            }
            throw new RuntimeException("Simulated model failure");
        }

        @Override
        public int tokenDimensions() {
            return 3;
        }

        @Override
        public String modelName() {
            return "failing-mock";
        }
    }
}
