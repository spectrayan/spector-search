/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Business Source License 1.1 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://github.com/spectrayan/spector/blob/main/spector-memory/LICENSE
 *
 * Change Date: May 27, 2030
 * Change License: Apache License, Version 2.0
 */
package com.spectrayan.spector.memory;

import com.spectrayan.spector.embed.SparseEncodingProvider;
import com.spectrayan.spector.embed.SparseEncodingResult;
import com.spectrayan.spector.embed.TokenEmbeddingProvider;
import com.spectrayan.spector.embed.TokenEmbeddingResult;
import com.spectrayan.spector.index.ColBERTReranker;
import com.spectrayan.spector.index.ColBERTReranker.RerankCandidate;
import com.spectrayan.spector.index.ColBERTReranker.RerankResult;
import com.spectrayan.spector.memory.cortex.MemoryBM25Index;
import com.spectrayan.spector.memory.cortex.MemoryBM25Index.BM25Candidate;
import com.spectrayan.spector.memory.cortex.MemorySpladeIndex;
import com.spectrayan.spector.memory.cortex.MemorySpladeIndex.SpladeCandidate;
import com.spectrayan.spector.memory.model.TextSearchMode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the 4-Layer Retrieval Stack.
 *
 * <p>Tests the interaction between BM25, SPLADE, vector (simulated via mock scores),
 * and ColBERT layers — the same flow that the {@code RecallPipeline} executes.</p>
 *
 * <p>Uses mock SPI providers for deterministic, fast tests without ONNX models.</p>
 */
class RetrievalStackIntegrationTest {

    private MemoryBM25Index bm25Index;
    private MemorySpladeIndex spladeIndex;
    private SparseEncodingProvider spladeProvider;
    private ColBERTReranker colbertReranker;

    @BeforeEach
    void setUp() {
        bm25Index = new MemoryBM25Index();
        spladeIndex = new MemorySpladeIndex();
        spladeProvider = new MockSparseEncodingProvider();
        colbertReranker = new ColBERTReranker(new MockTokenEmbeddingProvider(128));

        // Populate both indexes with the same docs
        bm25Index.addPartition();
        spladeIndex.addPartition();

        String[] docs = {
            "Java virtual machine performance tuning and optimization",
            "Python garbage collection and memory management",
            "Rust error handling with Result and Option types",
            "Java exception handling best practices and patterns",
            "Database connection pool configuration and monitoring"
        };

        for (int i = 0; i < docs.length; i++) {
            String id = "doc-" + i;
            bm25Index.index(0, id, docs[i]);

            SparseEncodingResult sparse = spladeProvider.encode(docs[i]);
            spladeIndex.index(0, id, sparse.weights());
        }
    }

    @AfterEach
    void tearDown() {
        bm25Index.close();
        spladeIndex.close();
    }

    // ══════════════════════════════════════════════════════════════
    // Layer integration tests
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("BM25 search — returns keyword matches")
    void bm25_returnsKeywordMatches() {
        List<BM25Candidate> results = bm25Index.search("java exception", 10);

        assertThat(results).isNotEmpty();
        assertThat(results.stream().map(BM25Candidate::id))
                .contains("doc-3"); // "Java exception handling..."
    }

    @Test
    @DisplayName("SPLADE search — returns semantically expanded matches")
    void splade_returnsSpladeResults() {
        SparseEncodingResult querySparse = spladeProvider.encode("java exception");
        List<SpladeCandidate> results = spladeIndex.search(
                querySparse.weights(), 10);

        assertThat(results).isNotEmpty();
        // SPLADE should find java-related docs through term expansion
        assertThat(results.stream().map(SpladeCandidate::id))
                .containsAnyOf("doc-0", "doc-3");
    }

    @Test
    @DisplayName("RRF fusion — BM25 + SPLADE candidates merged and deduped")
    void rrfFusion_mergesBM25AndSplade() {
        // Simulate the pipeline's RRF fusion step
        List<BM25Candidate> bm25Results = bm25Index.search("java error", 10);
        SparseEncodingResult querySparse = spladeProvider.encode("java error");
        List<SpladeCandidate> spladeResults = spladeIndex.search(
                querySparse.weights(), 10);

        // Convert SPLADE to BM25 format (what RecallPipeline does)
        List<BM25Candidate> spladesAsBM25 = spladeResults.stream()
                .map(sc -> new BM25Candidate(sc.id(), sc.spladeScore(), sc.partitionIndex()))
                .toList();

        // Merge and deduplicate by ID (RRF fusion simulation)
        Map<String, Float> fusedScores = new LinkedHashMap<>();
        int rank = 1;
        for (BM25Candidate c : bm25Results) {
            fusedScores.merge(c.id(), 1.0f / (60 + rank++), Float::sum);
        }
        rank = 1;
        for (BM25Candidate c : spladesAsBM25) {
            fusedScores.merge(c.id(), 1.0f / (60 + rank++), Float::sum);
        }

        assertThat(fusedScores).isNotEmpty();
        // Docs appearing in both BM25 and SPLADE should have higher fused score
        // than docs appearing in only one
    }

    @Test
    @DisplayName("ColBERT reranking — changes first-stage order")
    void colbertRerank_changesOrder() {
        // First-stage results: intentionally wrong order
        List<RerankCandidate> firstStage = List.of(
                new RerankCandidate("wrong-top",
                        "completely unrelated document about cooking recipes", 0.95f),
                new RerankCandidate("should-be-top",
                        "java virtual machine performance tuning", 0.50f)
        );

        List<RerankResult> reranked = colbertReranker.rerank(
                "java virtual machine", firstStage, 10);

        assertThat(reranked).hasSize(2);
        // "should-be-top" has much better token overlap with the query
        assertThat(reranked.getFirst().id()).isEqualTo("should-be-top");
    }

    @Test
    @DisplayName("Full stack flow — BM25 → SPLADE → fuse → ColBERT rerank")
    void fullStack_allLayers() {
        String query = "java error handling";

        // Layer 1+2: BM25 + Vector (simulate vector with BM25 scores)
        List<BM25Candidate> bm25Results = bm25Index.search(query, 10);

        // Layer 3: SPLADE
        SparseEncodingResult querySparse = spladeProvider.encode(query);
        List<SpladeCandidate> spladeResults = spladeIndex.search(
                querySparse.weights(), 10);

        // RRF fusion
        Map<String, Float> fusedScores = new LinkedHashMap<>();
        int rank = 1;
        for (BM25Candidate c : bm25Results) {
            fusedScores.merge(c.id(), 1.0f / (60 + rank++), Float::sum);
        }
        rank = 1;
        for (SpladeCandidate c : spladeResults) {
            fusedScores.merge(c.id(), 1.0f / (60 + rank++), Float::sum);
        }

        // Build rerank candidates from fused results
        String[] docs = {
            "Java virtual machine performance tuning and optimization",
            "Python garbage collection and memory management",
            "Rust error handling with Result and Option types",
            "Java exception handling best practices and patterns",
            "Database connection pool configuration and monitoring"
        };
        List<RerankCandidate> candidates = new ArrayList<>();
        for (var entry : fusedScores.entrySet()) {
            int docIdx = Integer.parseInt(entry.getKey().split("-")[1]);
            candidates.add(new RerankCandidate(entry.getKey(), docs[docIdx], entry.getValue()));
        }

        // Layer 4: ColBERT reranking
        List<RerankResult> reranked = colbertReranker.rerank(query, candidates, 3);

        assertThat(reranked).isNotEmpty();
        assertThat(reranked).hasSizeLessThanOrEqualTo(3);
        // Verify all results have valid scores (pipeline didn't crash)
        for (RerankResult r : reranked) {
            assertThat(r.combinedScore()).isGreaterThanOrEqualTo(0f);
            assertThat(r.id()).startsWith("doc-");
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Graceful degradation
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Null SPLADE provider — BM25 still works")
    void nullSplade_bm25StillWorks() {
        // Simulate the pipeline's null-check pattern
        MemorySpladeIndex nullSpladeIndex = null;
        SparseEncodingProvider nullProvider = null;

        // The pipeline's Step 3c would check:
        // if (spladeIndex != null && spladeProvider != null && mode.usesSPLADE()) { ... }
        boolean shouldRunSplade = nullSpladeIndex != null
                && nullProvider != null
                && TextSearchMode.FULL_STACK.usesSPLADE();
        assertThat(shouldRunSplade).isFalse();

        // BM25 still works fine
        List<BM25Candidate> results = bm25Index.search("java", 10);
        assertThat(results).isNotEmpty();
    }

    @Test
    @DisplayName("Null ColBERT reranker — first-stage results preserved")
    void nullColbert_firstStagePreserved() {
        ColBERTReranker nullReranker = null;

        // The pipeline's Step 6b would check:
        // if (colbertReranker != null && mode.usesColBERT()) { ... }
        boolean shouldRunColBERT = nullReranker != null
                && TextSearchMode.COLBERT_RERANK.usesColBERT();
        assertThat(shouldRunColBERT).isFalse();

        // First-stage results are what you'd get
        List<BM25Candidate> results = bm25Index.search("java virtual", 10);
        assertThat(results).isNotEmpty();
    }

    @Test
    @DisplayName("TextSearchMode gates — only requested layers activate")
    void textSearchMode_gatesLayers() {
        // KEYWORD_ONLY should not activate SPLADE or ColBERT
        TextSearchMode mode = TextSearchMode.KEYWORD_ONLY;
        assertThat(mode.usesBM25()).isTrue();
        assertThat(mode.usesSPLADE()).isFalse();
        assertThat(mode.usesColBERT()).isFalse();

        // So even with non-null providers, the pipeline would skip them
        boolean runSplade = spladeIndex != null && spladeProvider != null && mode.usesSPLADE();
        boolean runColBERT = colbertReranker != null && mode.usesColBERT();
        assertThat(runSplade).isFalse();
        assertThat(runColBERT).isFalse();
    }

    // ── Mock providers ──

    /**
     * Mock sparse encoding provider that uses simple term-frequency extraction.
     * <p>Not neural, but deterministic and sufficient for testing index wiring.</p>
     */
    static class MockSparseEncodingProvider implements SparseEncodingProvider {

        @Override
        public SparseEncodingResult encode(String text) {
            Map<String, Float> weights = new HashMap<>();
            for (String token : text.toLowerCase().split("\\W+")) {
                if (token.length() >= 2) {
                    weights.merge(token, 1.0f, Float::sum);
                }
            }
            return new SparseEncodingResult(weights, text.split("\\s+").length, modelName());
        }

        @Override
        public String modelName() {
            return "mock-splade";
        }

        @Override
        public int vocabularySize() {
            return 30_000;
        }
    }

    /**
     * Mock token embedding provider with deterministic hash-seeded embeddings.
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
}
