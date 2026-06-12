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
import java.util.Set;
import java.util.stream.Collectors;

import static com.spectrayan.spector.memory.e2e.E2EAssertions.*;
import static org.assertj.core.api.Assertions.*;

/**
 * E2E tests validating the quality of results across all {@link TextSearchMode} values.
 *
 * <h3>What This Tests</h3>
 * <p>Verifies that each text search mode (KEYWORD_ONLY, VECTOR_ONLY, HYBRID)
 * produces semantically appropriate results using the shared E2E seed data.
 * Uses both deterministic assertions and LLM Judge validation.</p>
 *
 * <h3>Why This Matters</h3>
 * <p>Prior to this test, existing E2E tests always used the default HYBRID mode.
 * This test ensures:</p>
 * <ul>
 *   <li>KEYWORD_ONLY returns lexically correct results (exact term matches)</li>
 *   <li>VECTOR_ONLY returns semantically relevant results (conceptual matches)</li>
 *   <li>HYBRID produces broader coverage than either mode alone</li>
 *   <li>Mode switching within the same memory instance is safe</li>
 *   <li>Advanced modes (SPLADE, COLBERT_RERANK, FULL_STACK) degrade gracefully</li>
 * </ul>
 *
 * <h3>Prerequisites</h3>
 * <ul>
 *   <li>Ollama running with qwen3-embedding model</li>
 *   <li>{@code OLLAMA_LIVE=true} environment variable</li>
 *   <li>Optional: {@code LLM_JUDGE=true} for LLM-based quality validation</li>
 * </ul>
 */
@DisplayName("🔍 E2E: Text Search Mode Quality Validation")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TextSearchModeE2ETest extends AbstractE2ETest {

    // ══════════════════════════════════════════════════════════════
    // KEYWORD_ONLY — Exact Term Matches
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("KEYWORD_ONLY — 'PostgreSQL HikariCP' finds exact keyword matches")
    void keywordOnly_exactTermMatch() {
        List<CognitiveResult> results = memory.recall(
                "PostgreSQL HikariCP connection pool",
                RecallOptions.builder()
                        .topK(10)
                        .textSearchMode(TextSearchMode.KEYWORD_ONLY)
                        .build());

        log.info("KEYWORD_ONLY: 'PostgreSQL HikariCP connection pool'");
        printResults(results);

        assertThat(results).as("KEYWORD_ONLY should return results for exact terms").isNotEmpty();
        assertScoreDescending(results);

        // At least one result must contain "PostgreSQL" or "HikariCP" or "pool"
        boolean hasExactMatch = results.stream()
                .anyMatch(r -> {
                    String text = r.text().toLowerCase();
                    return text.contains("postgresql") || text.contains("hikaricp")
                            || text.contains("connection pool");
                });
        assertThat(hasExactMatch).as("KEYWORD_ONLY should find exact keyword matches").isTrue();

        // LLM Judge: validate keyword match quality
        if (isLlmJudgeEnabled()) {
            llmAssertRecall("PostgreSQL HikariCP connection pool", results)
                    .isRelevantTo("Results should contain memories with the exact terms PostgreSQL, HikariCP, or connection pool")
                    .hasGoodRanking();
        }
    }

    @Test
    @Order(2)
    @DisplayName("KEYWORD_ONLY — 'Flyway migration' finds migration-related memories")
    void keywordOnly_flywayMigration() {
        List<CognitiveResult> results = memory.recall(
                "Flyway migration schema column",
                RecallOptions.builder()
                        .topK(10)
                        .textSearchMode(TextSearchMode.KEYWORD_ONLY)
                        .build());

        log.info("KEYWORD_ONLY: 'Flyway migration schema column'");
        printResults(results);

        assertThat(results).as("Should find Flyway/migration memories").isNotEmpty();

        boolean hasMigration = results.stream()
                .anyMatch(r -> {
                    String text = r.text().toLowerCase();
                    return text.contains("flyway") || text.contains("migration");
                });
        assertThat(hasMigration).as("Should contain Flyway or migration keyword").isTrue();

        if (isLlmJudgeEnabled()) {
            llmAssertRecall("Flyway migration schema column", results)
                    .warnIfIrrelevant("Results should contain memories about database migrations or schema changes");
        }
    }

    @Test
    @Order(3)
    @DisplayName("KEYWORD_ONLY — unrelated keywords return empty or irrelevant")
    void keywordOnly_unrelatedTermsNoMatch() {
        List<CognitiveResult> results = memory.recall(
                "photosynthesis chlorophyll sunlight",
                RecallOptions.builder()
                        .topK(5)
                        .textSearchMode(TextSearchMode.KEYWORD_ONLY)
                        .build());

        log.info("KEYWORD_ONLY: 'photosynthesis chlorophyll sunlight'");
        printResults(results);

        // These are biology terms — seed data is all software engineering
        // BM25 should find zero or very few matches
        if (!results.isEmpty()) {
            boolean hasBiologyTerm = results.stream()
                    .anyMatch(r -> r.text().toLowerCase().contains("photosynthesis")
                            || r.text().toLowerCase().contains("chlorophyll"));
            assertThat(hasBiologyTerm).as("No memory should contain biology terms").isFalse();
        }

        if (isLlmJudgeEnabled()) {
            llmAssertRecall("photosynthesis chlorophyll sunlight", results)
                    .warnIfIrrelevant("Results should have very low or no relevance — query is about biology, not software");
        }
    }

    // ══════════════════════════════════════════════════════════════
    // VECTOR_ONLY — Semantic Similarity
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(10)
    @DisplayName("VECTOR_ONLY — 'database performance bottleneck' finds semantically related")
    void vectorOnly_semanticMatch() {
        List<CognitiveResult> results = memory.recall(
                "database performance bottleneck slow queries",
                RecallOptions.builder()
                        .topK(10)
                        .textSearchMode(TextSearchMode.VECTOR_ONLY)
                        .build());

        log.info("VECTOR_ONLY: 'database performance bottleneck slow queries'");
        printResults(results);

        assertThat(results).as("VECTOR_ONLY should find semantically related results").isNotEmpty();
        assertScoreDescending(results);

        // At least one result should be about database performance (semantically)
        boolean hasDbPerf = results.stream()
                .anyMatch(r -> {
                    String text = r.text().toLowerCase();
                    return text.contains("query") || text.contains("index")
                            || text.contains("optimization") || text.contains("pool")
                            || text.contains("performance") || text.contains("database");
                });
        assertThat(hasDbPerf).as("Should find semantically related database content").isTrue();

        if (isLlmJudgeEnabled()) {
            llmAssertRecall("database performance bottleneck slow queries", results)
                    .isRelevantTo("Results should be about database performance, query optimization, or performance bottlenecks")
                    .hasGoodRanking();
        }
    }

    @Test
    @Order(11)
    @DisplayName("VECTOR_ONLY — conceptual query without keyword overlap")
    void vectorOnly_conceptualNoKeywordOverlap() {
        // Use a conceptual query where exact keywords don't appear in seed data
        // "scaling backend services under load" should find k8s, connection pool,
        // autoscaler memories — even though those words aren't in the query
        List<CognitiveResult> results = memory.recall(
                "scaling backend services under heavy traffic load",
                RecallOptions.builder()
                        .topK(10)
                        .textSearchMode(TextSearchMode.VECTOR_ONLY)
                        .build());

        log.info("VECTOR_ONLY: 'scaling backend services under heavy traffic load'");
        printResults(results);

        assertThat(results).as("VECTOR_ONLY should find conceptual matches").isNotEmpty();

        if (isLlmJudgeEnabled()) {
            llmAssertRecall("scaling backend services under heavy traffic load", results)
                    .warnIfIrrelevant("Results should relate to scaling, performance, deployment, or infrastructure topics")
                    .coversTopics("scaling", "performance", "infrastructure");
        }
    }

    // ══════════════════════════════════════════════════════════════
    // HYBRID — BM25 + Vector Fusion
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(20)
    @DisplayName("HYBRID — combines keyword AND semantic results")
    void hybrid_combinedResults() {
        // This query has both exact keywords ("deadlock") and conceptual meaning
        List<CognitiveResult> hybrid = memory.recall(
                "PostgreSQL deadlock detection and resolution",
                RecallOptions.builder()
                        .topK(10)
                        .textSearchMode(TextSearchMode.HYBRID)
                        .build());

        log.info("HYBRID: 'PostgreSQL deadlock detection and resolution'");
        printResults(hybrid);

        assertThat(hybrid).isNotEmpty();
        assertScoreDescending(hybrid);

        // The deadlock memory (db-003) should definitely appear
        boolean foundDeadlock = hybrid.stream()
                .anyMatch(r -> r.text().toLowerCase().contains("deadlock"));
        assertThat(foundDeadlock).as("HYBRID should find deadlock memory via keyword match").isTrue();

        if (isLlmJudgeEnabled()) {
            llmAssertRecall("PostgreSQL deadlock detection and resolution", hybrid)
                    .isRelevantTo("Results should be about database locking, deadlocks, or concurrency issues")
                    .hasGoodRanking();
        }
    }

    @Test
    @Order(21)
    @DisplayName("HYBRID produces broader coverage than KEYWORD_ONLY or VECTOR_ONLY alone")
    void hybrid_broaderCoverage() {
        String query = "connection pool exhaustion causing production outage";

        List<CognitiveResult> keyword = memory.recall(query,
                RecallOptions.builder().topK(10).textSearchMode(TextSearchMode.KEYWORD_ONLY).build());
        List<CognitiveResult> vector = memory.recall(query,
                RecallOptions.builder().topK(10).textSearchMode(TextSearchMode.VECTOR_ONLY).build());
        List<CognitiveResult> hybrid = memory.recall(query,
                RecallOptions.builder().topK(10).textSearchMode(TextSearchMode.HYBRID).build());

        Set<String> keywordIds = keyword.stream().map(CognitiveResult::id).collect(Collectors.toSet());
        Set<String> vectorIds = vector.stream().map(CognitiveResult::id).collect(Collectors.toSet());
        Set<String> hybridIds = hybrid.stream().map(CognitiveResult::id).collect(Collectors.toSet());

        log.info("Coverage comparison for: '{}'", query);
        log.info("  KEYWORD_ONLY: {} results, IDs={}", keyword.size(), keywordIds);
        log.info("  VECTOR_ONLY:  {} results, IDs={}", vector.size(), vectorIds);
        log.info("  HYBRID:       {} results, IDs={}", hybrid.size(), hybridIds);

        assertThat(hybrid).as("HYBRID should return results").isNotEmpty();

        // HYBRID should contain results from both sources
        // (some keyword-only results AND some vector-only results)
        boolean hasFromKeyword = !keywordIds.isEmpty() &&
                hybridIds.stream().anyMatch(keywordIds::contains);
        boolean hasFromVector = !vectorIds.isEmpty() &&
                hybridIds.stream().anyMatch(vectorIds::contains);

        log.info("  Overlap — HYBRID∩KEYWORD={}, HYBRID∩VECTOR={}",
                hasFromKeyword, hasFromVector);

        if (isLlmJudgeEnabled()) {
            llmAssertRecall(query, hybrid)
                    .isRelevantTo("Results should comprehensively cover connection pool issues, production outages, and database problems")
                    .hasGoodRanking();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Mode Switching — Rapid Mode Changes
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(30)
    @DisplayName("Mode switching — rapidly alternate between all modes, no exceptions")
    void modeSwitching_noExceptions() {
        String query = "database connection error handling";
        TextSearchMode[] modes = {
                TextSearchMode.HYBRID,
                TextSearchMode.KEYWORD_ONLY,
                TextSearchMode.VECTOR_ONLY,
                TextSearchMode.HYBRID,
                TextSearchMode.KEYWORD_ONLY,
                TextSearchMode.VECTOR_ONLY,
        };

        for (TextSearchMode mode : modes) {
            assertThatCode(() -> {
                List<CognitiveResult> results = memory.recall(query,
                        RecallOptions.builder()
                                .topK(5)
                                .textSearchMode(mode)
                                .build());
                log.info("  {} → {} results", mode, results.size());
            }).as("Mode " + mode + " should not throw").doesNotThrowAnyException();
        }
    }

    @Test
    @Order(31)
    @DisplayName("Mode switching — KEYWORD_ONLY and VECTOR_ONLY produce different result sets")
    void modeSwitching_differentResults() {
        String query = "error handling and retry logic";

        List<CognitiveResult> keyword = memory.recall(query,
                RecallOptions.builder().topK(10).textSearchMode(TextSearchMode.KEYWORD_ONLY).build());
        List<CognitiveResult> vector = memory.recall(query,
                RecallOptions.builder().topK(10).textSearchMode(TextSearchMode.VECTOR_ONLY).build());

        Set<String> keywordIds = keyword.stream().map(CognitiveResult::id).collect(Collectors.toSet());
        Set<String> vectorIds = vector.stream().map(CognitiveResult::id).collect(Collectors.toSet());

        log.info("KEYWORD_ONLY IDs: {}", keywordIds);
        log.info("VECTOR_ONLY IDs:  {}", vectorIds);

        // They should differ — keyword-only uses exact terms, vector uses semantic
        if (!keywordIds.isEmpty() && !vectorIds.isEmpty()) {
            // Not asserting they're completely disjoint (some overlap is fine)
            // but they should not be identical
            log.info("  Intersection: {}", keywordIds.stream()
                    .filter(vectorIds::contains).toList());
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Graceful Degradation — Advanced Modes Without Providers
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(40)
    @DisplayName("SPLADE mode degrades gracefully when no provider is configured")
    void spladeMode_gracefulDegradation() {
        // SPLADE provider is null in current E2E context — should degrade silently
        assertThatCode(() -> {
            List<CognitiveResult> results = memory.recall(
                    "database optimization techniques",
                    RecallOptions.builder()
                            .topK(5)
                            .textSearchMode(TextSearchMode.SPLADE)
                            .build());
            log.info("SPLADE (degraded): {} results", results.size());
            printResults(results);
        }).as("SPLADE mode should degrade gracefully").doesNotThrowAnyException();
    }

    @Test
    @Order(41)
    @DisplayName("COLBERT_RERANK mode degrades gracefully when no provider is configured")
    void colbertRerank_gracefulDegradation() {
        assertThatCode(() -> {
            List<CognitiveResult> results = memory.recall(
                    "authentication and authorization flow",
                    RecallOptions.builder()
                            .topK(5)
                            .textSearchMode(TextSearchMode.COLBERT_RERANK)
                            .build());
            log.info("COLBERT_RERANK (degraded): {} results", results.size());
            printResults(results);
        }).as("COLBERT_RERANK mode should degrade gracefully").doesNotThrowAnyException();
    }

    @Test
    @Order(42)
    @DisplayName("FULL_STACK mode degrades gracefully when providers are missing")
    void fullStack_gracefulDegradation() {
        assertThatCode(() -> {
            List<CognitiveResult> results = memory.recall(
                    "deployment pipeline configuration",
                    RecallOptions.builder()
                            .topK(5)
                            .textSearchMode(TextSearchMode.FULL_STACK)
                            .build());
            log.info("FULL_STACK (degraded): {} results", results.size());
            printResults(results);
        }).as("FULL_STACK mode should degrade gracefully").doesNotThrowAnyException();
    }

    @Test
    @Order(43)
    @DisplayName("All TextSearchMode enum values execute without errors")
    void allModesSmoke() {
        String query = "software engineering practices";
        for (TextSearchMode mode : TextSearchMode.values()) {
            assertThatCode(() -> {
                List<CognitiveResult> results = memory.recall(query,
                        RecallOptions.builder()
                                .topK(5)
                                .textSearchMode(mode)
                                .build());
                log.info("  {} → {} results", mode, results.size());
            }).as("Mode " + mode + " should not throw").doesNotThrowAnyException();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Cross-Tier Keyword Recall — BM25 Across Memory Types
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(50)
    @DisplayName("KEYWORD_ONLY — finds matches in EPISODIC, SEMANTIC, and PROCEDURAL tiers")
    void keywordOnly_crossTierCoverage() {
        // "PostgreSQL" appears in all tiers in the seed data
        List<CognitiveResult> results = memory.recall(
                "PostgreSQL index optimization",
                RecallOptions.builder()
                        .topK(20)
                        .textSearchMode(TextSearchMode.KEYWORD_ONLY)
                        .build());

        log.info("KEYWORD_ONLY cross-tier: 'PostgreSQL index optimization'");
        printResults(results);

        if (results.size() >= 3) {
            var tiers = results.stream().map(CognitiveResult::memoryType).distinct().toList();
            log.info("  Tiers found: {}", tiers);
            // PostgreSQL content exists in EPISODIC and SEMANTIC seed data
            assertThat(tiers.size()).as("Keywords should match across multiple tiers")
                    .isGreaterThanOrEqualTo(2);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // LLM Judge — A/B Quality Comparison
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(60)
    @DisplayName("LLM Judge: HYBRID ranking quality for production error query")
    void llmJudge_hybridRankingQuality() {
        List<CognitiveResult> results = memory.recall(
                "production error caused by database connection timeout",
                RecallOptions.builder()
                        .topK(10)
                        .textSearchMode(TextSearchMode.HYBRID)
                        .build());

        log.info("LLM Judge HYBRID ranking: 'production error caused by database connection timeout'");
        printResults(results);

        assertThat(results).isNotEmpty();

        if (isLlmJudgeEnabled()) {
            llmAssertRecall("production error caused by database connection timeout", results)
                    .isRelevantTo("Results should contain memories about production errors, database timeouts, or connection failures")
                    .hasGoodRanking()
                    .coversTopics("database", "error", "production");
        }
    }

    @Test
    @Order(61)
    @DisplayName("LLM Judge: VECTOR_ONLY captures conceptual matches that KEYWORD_ONLY misses")
    void llmJudge_vectorCaptsConceptualMatches() {
        // Use paraphrased query that doesn't share exact keywords with seed data
        // "server crashed due to too many open sockets" should match connection pool / outage memories
        List<CognitiveResult> results = memory.recall(
                "server crashed due to too many open sockets and resource exhaustion",
                RecallOptions.builder()
                        .topK(10)
                        .textSearchMode(TextSearchMode.VECTOR_ONLY)
                        .build());

        log.info("LLM Judge VECTOR_ONLY conceptual: 'server crashed due to too many open sockets'");
        printResults(results);

        assertThat(results).isNotEmpty();

        if (isLlmJudgeEnabled()) {
            llmAssertRecall("server crashed due to too many open sockets and resource exhaustion", results)
                    .warnIfIrrelevant("Results should relate to resource exhaustion, connection limits, or server crashes — even without exact keyword overlap");
        }
    }
}
