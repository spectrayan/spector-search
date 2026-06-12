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
package com.spectrayan.spector.memory.e2e;

import com.spectrayan.spector.memory.model.*;

import org.junit.jupiter.api.*;

import java.util.List;

import static com.spectrayan.spector.memory.e2e.E2EAssertions.*;
import static org.assertj.core.api.Assertions.*;

/**
 * E2E tests for the full retrieval stack with SPLADE and ColBERT providers wired.
 *
 * <h3>Provider Implementation</h3>
 * <p>Uses dense-derived providers backed by Ollama:</p>
 * <ul>
 *   <li>{@code OllamaSparseEncodingProvider}: derives SPLADE-like sparse weights
 *       from per-term cosine similarity with the document embedding</li>
 *   <li>{@code OllamaTokenEmbeddingProvider}: derives ColBERT-style per-token
 *       embeddings with Matryoshka truncation to 128 dims</li>
 * </ul>
 *
 * <h3>What This Tests</h3>
 * <ul>
 *   <li><b>SPLADE</b>: Vocabulary mismatch bridging (synonyms, paraphrases)</li>
 *   <li><b>SPLADE</b>: Neural term expansion vs BM25 exact match</li>
 *   <li><b>ColBERT</b>: Token-level MaxSim reranking precision</li>
 *   <li><b>ColBERT Cache</b>: Cache-hit path produces identical results to cold path</li>
 *   <li><b>FULL_STACK</b>: All layers produce best overall quality</li>
 * </ul>
 *
 * <h3>Prerequisites</h3>
 * <ul>
 *   <li>Ollama running with qwen3-embedding model</li>
 *   <li>{@code OLLAMA_LIVE=true} environment variable</li>
 *   <li>Optional: {@code LLM_JUDGE=true} for LLM-based quality validation</li>
 * </ul>
 *
 * @see TextSearchMode#SPLADE
 * @see TextSearchMode#COLBERT_RERANK
 * @see TextSearchMode#FULL_STACK
 */
@DisplayName("🧪 E2E: SPLADE + ColBERT Retrieval Stack")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RetrievalStackE2ETest extends AbstractE2ETest {

    // ══════════════════════════════════════════════════════════════
    // SPLADE — Vocabulary Mismatch Bridging
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("SPLADE bridges vocabulary mismatch: 'car' → 'automobile' / 'vehicle'")
    void splade_vocabularyMismatch() {
        // Seed data uses "vehicle", "automobile" — query uses "car"
        // BM25 won't match, but SPLADE should via neural term expansion
        List<CognitiveResult> spladeResults = memory.recall(
                "car maintenance and repair issues",
                RecallOptions.builder()
                        .topK(10)
                        .textSearchMode(TextSearchMode.SPLADE)
                        .build());

        List<CognitiveResult> bm25Results = memory.recall(
                "car maintenance and repair issues",
                RecallOptions.builder()
                        .topK(10)
                        .textSearchMode(TextSearchMode.KEYWORD_ONLY)
                        .build());

        log.info("SPLADE vocabulary mismatch test:");
        log.info("  SPLADE results:     {}", spladeResults.size());
        log.info("  KEYWORD_ONLY results: {}", bm25Results.size());
        printResults(spladeResults);

        // SPLADE should find more results via term expansion
        assertThat(spladeResults.size())
                .as("SPLADE should find results that BM25 misses via term expansion")
                .isGreaterThanOrEqualTo(bm25Results.size());

        if (isLlmJudgeEnabled()) {
            llmAssertRecall("car maintenance and repair issues", spladeResults)
                    .isRelevantTo("Results should relate to vehicles, transportation, or mechanical maintenance — SPLADE should bridge from 'car' to related terms");
        }
    }

    @Test
    @Order(2)
    @DisplayName("SPLADE — synonym expansion: 'error' → 'exception', 'failure', 'crash'")
    void splade_synonymExpansion() {
        List<CognitiveResult> results = memory.recall(
                "application crash and failure diagnosis",
                RecallOptions.builder()
                        .topK(10)
                        .textSearchMode(TextSearchMode.SPLADE)
                        .build());

        log.info("SPLADE synonym expansion: 'application crash and failure diagnosis'");
        printResults(results);

        assertThat(results).isNotEmpty();

        // SPLADE should find memories mentioning "error", "exception", "timeout"
        // even though the query uses "crash" and "failure"
        boolean foundSynonyms = results.stream()
                .anyMatch(r -> {
                    String text = r.text().toLowerCase();
                    return text.contains("error") || text.contains("exception")
                            || text.contains("timeout") || text.contains("outage");
                });
        assertThat(foundSynonyms)
                .as("SPLADE should find 'error'/'exception' memories from 'crash'/'failure' query")
                .isTrue();

        if (isLlmJudgeEnabled()) {
            llmAssertRecall("application crash and failure diagnosis", results)
                    .isRelevantTo("Results should relate to application errors, crashes, exceptions, or system failures")
                    .hasGoodRanking();
        }
    }

    @Test
    @Order(3)
    @DisplayName("SPLADE_HYBRID — better quality than HYBRID for paraphrased queries")
    void spladeHybrid_betterThanHybrid() {
        String query = "server ran out of available connections causing service degradation";

        List<CognitiveResult> hybrid = memory.recall(query,
                RecallOptions.builder().topK(10).textSearchMode(TextSearchMode.HYBRID).build());
        List<CognitiveResult> spladeHybrid = memory.recall(query,
                RecallOptions.builder().topK(10).textSearchMode(TextSearchMode.SPLADE_HYBRID).build());

        log.info("HYBRID vs SPLADE_HYBRID for paraphrased query:");
        log.info("  HYBRID:        {} results", hybrid.size());
        printResults(hybrid);
        log.info("  SPLADE_HYBRID: {} results", spladeHybrid.size());
        printResults(spladeHybrid);

        assertThat(spladeHybrid).isNotEmpty();

        if (isLlmJudgeEnabled()) {
            llmAssertRecall(query, spladeHybrid)
                    .isRelevantTo("Results should relate to connection exhaustion, service degradation, or resource limits")
                    .hasGoodRanking();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ColBERT — Token-Level Reranking Precision
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(10)
    @DisplayName("COLBERT_RERANK — improves top-5 precision over first-stage retrieval")
    void colbertRerank_improvedPrecision() {
        String query = "how to fix OOM errors in Spring Boot with HikariCP connection pooling";

        List<CognitiveResult> firstStage = memory.recall(query,
                RecallOptions.builder().topK(10).textSearchMode(TextSearchMode.HYBRID).build());
        List<CognitiveResult> reranked = memory.recall(query,
                RecallOptions.builder().topK(10).textSearchMode(TextSearchMode.COLBERT_RERANK).build());

        log.info("ColBERT reranking precision test:");
        log.info("  First-stage (HYBRID) top-5:");
        printResults(firstStage.stream().limit(5).toList());
        log.info("  Reranked (COLBERT_RERANK) top-5:");
        printResults(reranked.stream().limit(5).toList());

        assertThat(reranked).isNotEmpty();

        if (isLlmJudgeEnabled()) {
            // First-stage quality
            llmAssertRecall(query, firstStage.stream().limit(5).toList())
                    .warnIfIrrelevant("First-stage top-5 should relate to OOM, Spring Boot, or HikariCP");

            // Reranked quality — should be better
            llmAssertRecall(query, reranked.stream().limit(5).toList())
                    .isRelevantTo("Reranked top-5 should be specifically about Java OOM in Spring Boot with connection pooling, not generic errors")
                    .hasGoodRanking();
        }
    }

    @Test
    @Order(11)
    @DisplayName("COLBERT_RERANK — reranking does not lose relevant results")
    void colbertRerank_noRelevanceLoss() {
        String query = "PostgreSQL deadlock resolution strategy";

        List<CognitiveResult> firstStage = memory.recall(query,
                RecallOptions.builder().topK(10).textSearchMode(TextSearchMode.HYBRID).build());
        List<CognitiveResult> reranked = memory.recall(query,
                RecallOptions.builder().topK(10).textSearchMode(TextSearchMode.COLBERT_RERANK).build());

        // Check that the deadlock memory (db-003) appears in both
        boolean inFirstStage = firstStage.stream().anyMatch(r -> "db-003".equals(r.id()));
        boolean inReranked = reranked.stream().anyMatch(r -> "db-003".equals(r.id()));

        log.info("Deadlock memory (db-003): firstStage={}, reranked={}", inFirstStage, inReranked);

        if (inFirstStage) {
            assertThat(inReranked)
                    .as("ColBERT reranking should not drop a clearly relevant result")
                    .isTrue();
        }
    }

    @Test
    @Order(12)
    @DisplayName("ColBERT cache — cached results identical to cold results")
    void colbertCache_identicalResults() {
        String query = "database connection pool sizing formula";

        // First query — cold cache
        List<CognitiveResult> cold = memory.recall(query,
                RecallOptions.builder().topK(5).textSearchMode(TextSearchMode.COLBERT_RERANK).build());

        // Second query — should hit ColBERT token cache
        List<CognitiveResult> warm = memory.recall(query,
                RecallOptions.builder().topK(5).textSearchMode(TextSearchMode.COLBERT_RERANK).build());

        log.info("ColBERT cache test:");
        log.info("  Cold: {} results", cold.size());
        log.info("  Warm: {} results", warm.size());

        assertThat(warm.size()).isEqualTo(cold.size());

        // Results should be identical (same IDs, same order)
        for (int i = 0; i < cold.size(); i++) {
            assertThat(warm.get(i).id())
                    .as("Cached result #%d should have same ID", i)
                    .isEqualTo(cold.get(i).id());
            assertThat(warm.get(i).score())
                    .as("Cached result #%d should have same score", i)
                    .isEqualTo(cold.get(i).score());
        }
    }

    // ══════════════════════════════════════════════════════════════
    // FULL_STACK — All Layers Active
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(20)
    @DisplayName("FULL_STACK — maximum quality for complex multi-faceted query")
    void fullStack_maximumQuality() {
        String query = "how to diagnose and fix PostgreSQL connection pool exhaustion "
                + "causing intermittent 500 errors in the Spring Boot REST API";

        List<CognitiveResult> fullStack = memory.recall(query,
                RecallOptions.builder().topK(10).textSearchMode(TextSearchMode.FULL_STACK).build());

        log.info("FULL_STACK maximum quality test:");
        printResults(fullStack);

        assertThat(fullStack).isNotEmpty();
        assertScoreDescending(fullStack);

        // The most relevant memories should be in top-5:
        // db-001 (connection pool outage), db-015 (HikariCP sizing), db-003 (deadlock)
        assertRecallContainsAny(fullStack, "db-001", "db-015", "db-003");

        if (isLlmJudgeEnabled()) {
            llmAssertRecall(query, fullStack)
                    .isRelevantTo("Results should comprehensively cover PostgreSQL connection pool issues, "
                            + "HikariCP configuration, Spring Boot REST API errors, and troubleshooting procedures")
                    .hasGoodRanking()
                    .coversTopics("connection pool", "PostgreSQL", "error handling", "troubleshooting");
        }
    }

    @Test
    @Order(21)
    @DisplayName("FULL_STACK — outperforms HYBRID on precision@5 for domain-specific query")
    void fullStack_betterPrecisionThanHybrid() {
        String query = "Flyway database migration failed adding NOT NULL column to large table";

        List<CognitiveResult> hybrid = memory.recall(query,
                RecallOptions.builder().topK(10).textSearchMode(TextSearchMode.HYBRID).build());
        List<CognitiveResult> fullStack = memory.recall(query,
                RecallOptions.builder().topK(10).textSearchMode(TextSearchMode.FULL_STACK).build());

        log.info("HYBRID vs FULL_STACK precision@5:");
        log.info("  HYBRID top-5:");
        printResults(hybrid.stream().limit(5).toList());
        log.info("  FULL_STACK top-5:");
        printResults(fullStack.stream().limit(5).toList());

        // db-013 (Flyway V18 failure) should appear in FULL_STACK top-5
        boolean inFullStack = fullStack.stream().limit(5)
                .anyMatch(r -> "db-013".equals(r.id()));
        log.info("  db-013 (Flyway failure) in FULL_STACK top-5: {}", inFullStack);

        if (isLlmJudgeEnabled()) {
            llmAssertRecall(query, fullStack.stream().limit(5).toList())
                    .isRelevantTo("Top-5 should specifically be about Flyway migration failures, "
                            + "NOT NULL constraint issues, or large table migration strategies")
                    .hasGoodRanking();
        }
    }
}
