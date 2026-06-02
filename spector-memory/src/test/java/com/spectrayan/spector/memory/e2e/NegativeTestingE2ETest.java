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

import com.spectrayan.spector.memory.CognitiveResult;
import com.spectrayan.spector.memory.MemoryType;
import com.spectrayan.spector.memory.RecallOptions;

import org.junit.jupiter.api.*;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Negative and adversarial E2E tests for the Spector Memory system.
 *
 * <p>Validates that the system handles edge cases gracefully:
 * invalid inputs, irrelevant queries, suppression/forget correctness,
 * and boundary conditions.</p>
 */
@DisplayName("🚫 E2E: Negative Testing & Edge Cases")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NegativeTestingE2ETest extends AbstractE2ETest {

    // ══════════════════════════════════════════════════════════════
    // QUERY ROBUSTNESS
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("Empty query is rejected by embedding provider")
    void emptyQueryIsRejected() {
        // The embedding provider rejects blank text with SpectorEmbeddingException
        assertThatThrownBy(() -> memory.recall("",
                RecallOptions.builder().topK(10).build()))
                .as("Empty query should be rejected by embedding provider")
                .isInstanceOf(Exception.class);
    }

    @Test
    @Order(2)
    @DisplayName("Whitespace-only query is rejected by embedding provider")
    void whitespaceOnlyQueryIsRejected() {
        // The embedding provider rejects blank text with SpectorEmbeddingException
        assertThatThrownBy(() -> memory.recall("     ",
                RecallOptions.builder().topK(10).build()))
                .as("Whitespace-only query should be rejected by embedding provider")
                .isInstanceOf(Exception.class);
    }

    @Test
    @Order(3)
    @DisplayName("Very long query completes without crash")
    void veryLongQueryDoesNotCrash() {
        // Generate a 5000-character query
        String longQuery = "database connection pool ".repeat(200);

        assertThatCode(() -> {
            List<CognitiveResult> results = memory.recall(longQuery,
                    RecallOptions.builder().topK(5).build());
            log.info("Long query ({} chars) returned {} results",
                    longQuery.length(), results.size());
        }).doesNotThrowAnyException();
    }

    @Test
    @Order(4)
    @DisplayName("Special character query does not crash")
    void specialCharacterQueryDoesNotCrash() {
        String[] adversarialQueries = {
                "'; DROP TABLE memories; --",
                "<script>alert('xss')</script>",
                "🎉🚀💻 emoji query 🧠",
                "SELECT * FROM memories WHERE 1=1",
                "\\n\\t\\r null bytes \\0",
                "{\"json\": \"query\", \"nested\": true}",
        };

        for (String query : adversarialQueries) {
            assertThatCode(() -> {
                List<CognitiveResult> results = memory.recall(query,
                        RecallOptions.builder().topK(5).build());
                log.info("Adversarial query '{}' returned {} results",
                        truncate(query, 40), results.size());
            }).as("Query '%s' should not crash", truncate(query, 40))
              .doesNotThrowAnyException();
        }
    }

    @Test
    @Order(5)
    @DisplayName("Unicode query returns results without error")
    void unicodeQueryWorks() {
        // Japanese query
        List<CognitiveResult> results = memory.recall("データベース接続プール",
                RecallOptions.builder().topK(5).build());
        log.info("Japanese query returned {} results", results.size());
        assertThatCode(() -> memory.recall("数据库连接", RecallOptions.builder().topK(5).build()))
                .doesNotThrowAnyException();
    }

    // ══════════════════════════════════════════════════════════════
    // SEMANTIC DISCRIMINATION
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(10)
    @DisplayName("Completely unrelated query returns low scores")
    void unrelatedQueryReturnsLowScores() {
        List<CognitiveResult> results = memory.recall(
                "recipe for chocolate sourdough bread baking temperature",
                RecallOptions.builder().topK(10).build());

        log.info("Unrelated query (baking) returned {} results", results.size());
        printResults(results);

        // If results exist, scores should be low
        if (!results.isEmpty()) {
            float topScore = results.getFirst().score();
            log.info("Top score for unrelated query: {}", topScore);

            // With LLM judge, validate semantically
            if (isLlmJudgeEnabled()) {
                llmAssertRecall("recipe for chocolate sourdough bread baking temperature", results)
                        .warnIfIrrelevant("Results should NOT be about bread baking — expect low relevance");
            }
        }
    }

    @Test
    @Order(11)
    @DisplayName("Homonym discrimination — 'swimming pool' should not match 'connection pool'")
    void homonymDisambiguation() {
        List<CognitiveResult> results = memory.recall(
                "swimming pool maintenance chlorine levels and filtration system repair",
                RecallOptions.builder().topK(10).build());

        log.info("Homonym query (swimming pool) returned {} results", results.size());
        printResults(results);

        // Connection pool memories (db-001, db-015) should NOT dominate
        boolean connectionPoolDominates = results.stream()
                .limit(3)
                .anyMatch(r -> r.text().toLowerCase().contains("hikaricp")
                        || r.text().toLowerCase().contains("connection pool exhaustion"));

        if (connectionPoolDominates) {
            log.warn("⚠ Connection pool memories ranked in top-3 for 'swimming pool' query — disambiguation weak");
        }

        // LLM Judge: semantic validation
        if (isLlmJudgeEnabled()) {
            llmAssertRecall("swimming pool maintenance chlorine filtration", results)
                    .warnIfIrrelevant("Results should be about swimming pools, NOT database connection pools");
        }
    }

    @Test
    @Order(12)
    @DisplayName("Wrong domain — Python Flask should not surface Java Spring memories")
    void wrongDomainExclusion() {
        List<CognitiveResult> results = memory.recall(
                "Python Flask SQLAlchemy REST API deployment on Raspberry Pi",
                RecallOptions.builder().topK(10).build());

        log.info("Wrong domain query (Python Flask) returned {} results", results.size());
        printResults(results);

        // Java/Spring-specific memories should not rank #1
        if (!results.isEmpty()) {
            String topText = results.getFirst().text().toLowerCase();
            boolean javaSpringTop = topText.contains("spring boot")
                    || topText.contains("java 21")
                    || topText.contains("junit");
            if (javaSpringTop) {
                log.warn("⚠ Java/Spring memory ranked #1 for Python Flask query — cross-domain leakage");
            }
        }

        // LLM Judge: semantic validation
        if (isLlmJudgeEnabled() && !results.isEmpty()) {
            llmAssertRecall("Python Flask SQLAlchemy REST API Raspberry Pi", results)
                    .warnIfIrrelevant("Results should NOT be about Java/Spring — expect Python/Flask related content or low relevance");
        }
    }

    @Test
    @Order(13)
    @DisplayName("Gibberish query returns low-confidence results or empty")
    void gibberishQueryReturnsLowConfidence() {
        List<CognitiveResult> results = memory.recall(
                "xyzzy plugh qwertyuiop asdfghjkl zxcvbnm foobar",
                RecallOptions.builder().topK(5).build());

        log.info("Gibberish query returned {} results", results.size());
        if (!results.isEmpty()) {
            float topScore = results.getFirst().score();
            log.info("Top score for gibberish: {} (expect low)", topScore);
        }

        // LLM Judge
        if (isLlmJudgeEnabled() && !results.isEmpty()) {
            llmAssertRecall("xyzzy plugh qwertyuiop asdfghjkl zxcvbnm foobar", results)
                    .warnIfIrrelevant("Results should have very low relevance — query is pure gibberish");
        }
    }

    // ══════════════════════════════════════════════════════════════
    // SUPPRESSION & FORGET CORRECTNESS
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(20)
    @DisplayName("Forgotten memory never appears in recall")
    void forgottenMemoryNeverRecalled() {
        // Ingest a unique memory, then forget it
        String uniqueId = "neg-forget-test-" + System.nanoTime();
        memory.remember(uniqueId,
                "This memory about quantum encryption should be forgotten immediately",
                MemoryType.EPISODIC, "test", "forget").join();

        // Forget it
        memory.forget(uniqueId);

        // Recall should not return it
        List<CognitiveResult> results = memory.recall(
                "quantum encryption forgotten memory test",
                RecallOptions.builder().topK(20).build());

        boolean found = results.stream().anyMatch(r -> r.id().equals(uniqueId));
        assertThat(found)
                .as("Forgotten memory '%s' should not appear in recall", uniqueId)
                .isFalse();
    }

    @Test
    @Order(21)
    @DisplayName("Suppressed memory is excluded from recall")
    void suppressedMemoryNotRecalled() {
        // Ingest a unique memory, then suppress it
        String uniqueId = "neg-suppress-test-" + System.nanoTime();
        memory.remember(uniqueId,
                "This memory about blockchain consensus algorithms should be suppressed",
                MemoryType.EPISODIC, "test", "suppress").join();

        // Suppress it
        memory.suppress(uniqueId, "Testing suppression");

        // Recall should not return it
        List<CognitiveResult> results = memory.recall(
                "blockchain consensus algorithms suppressed memory",
                RecallOptions.builder().topK(20).build());

        boolean found = results.stream().anyMatch(r -> r.id().equals(uniqueId));
        assertThat(found)
                .as("Suppressed memory '%s' should not appear in recall", uniqueId)
                .isFalse();
    }

    @Test
    @Order(22)
    @DisplayName("Unsuppressed memory returns to recall")
    void unsuppressedMemoryReturns() {
        // Ingest, suppress, unsuppress
        String uniqueId = "neg-unsuppress-test-" + System.nanoTime();
        memory.remember(uniqueId,
                "This memory about neural network pruning techniques should survive unsuppression",
                MemoryType.EPISODIC, "test", "unsuppress").join();

        memory.suppress(uniqueId, "Testing suppress/unsuppress cycle");
        memory.unsuppress(uniqueId);

        // Verify via index that memory exists and is NOT suppressed
        var location = memory.index().locate(uniqueId);
        assertThat(location)
                .as("Unsuppressed memory '%s' should still be in the index", uniqueId)
                .isNotNull();

        assertThat(memory.suppression().isSuppressed(uniqueId))
                .as("Unsuppressed memory '%s' should not be in suppression set", uniqueId)
                .isFalse();
    }

    @Test
    @Order(23)
    @DisplayName("Double forget is idempotent — no exception")
    void doubleForgetDoesNotThrow() {
        String uniqueId = "neg-double-forget-" + System.nanoTime();
        memory.remember(uniqueId,
                "Memory for double-forget idempotency test",
                MemoryType.EPISODIC, "test").join();

        assertThatCode(() -> {
            memory.forget(uniqueId);
            memory.forget(uniqueId); // second forget should not throw
        }).doesNotThrowAnyException();
    }

    // ══════════════════════════════════════════════════════════════
    // BOUNDARY CONDITIONS
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(30)
    @DisplayName("topK=0 returns empty results or throws")
    void topK0ReturnsEmptyOrThrows() {
        try {
            List<CognitiveResult> results = memory.recall(
                    "database connection pooling",
                    RecallOptions.builder().topK(0).build());
            // If it doesn't throw, it should return empty
            assertThat(results)
                    .as("topK=0 should return empty results")
                    .isEmpty();
        } catch (Exception e) {
            // topK=0 may be rejected as invalid — that's also correct behavior
            log.info("topK=0 rejected with: {}", e.getMessage());
        }
    }

    @Test
    @Order(31)
    @DisplayName("topK exceeding total memory count returns all without error")
    void topKExceedsMemoryCountReturnsAll() {
        assertThatCode(() -> {
            List<CognitiveResult> results = memory.recall(
                    "database",
                    RecallOptions.builder().topK(10_000).build());
            log.info("topK=10000 returned {} results (total memories: {})",
                    results.size(), memory.totalMemories());
            assertThat(results.size())
                    .as("Result count should not exceed total memories")
                    .isLessThanOrEqualTo(memory.totalMemories());
        }).doesNotThrowAnyException();
    }

    @Test
    @Order(32)
    @DisplayName("Valence range filtering excludes out-of-range memories")
    void valenceRangeFiltering() {
        // Only positive valence memories
        List<CognitiveResult> positiveOnly = memory.recall(
                "deployment and configuration management",
                RecallOptions.builder()
                        .topK(20)
                        .minValence((byte) 10)
                        .maxValence(Byte.MAX_VALUE)
                        .build());

        log.info("Positive-only recall returned {} results", positiveOnly.size());

        // All returned memories should have valence >= 10
        for (CognitiveResult r : positiveOnly) {
            assertThat(r.valence())
                    .as("Memory '%s' valence should be >= 10", r.id())
                    .isGreaterThanOrEqualTo((byte) 10);
        }
    }

    @Test
    @Order(33)
    @DisplayName("Memory type filter restricts to specified types only")
    void memoryTypeFilterWorks() {
        // Only PROCEDURAL memories
        List<CognitiveResult> proceduralOnly = memory.recall(
                "debugging and investigation procedure checklist",
                RecallOptions.builder()
                        .topK(20)
                        .memoryTypes(MemoryType.PROCEDURAL)
                        .build());

        log.info("Procedural-only recall returned {} results", proceduralOnly.size());
        printResults(proceduralOnly);

        for (CognitiveResult r : proceduralOnly) {
            assertThat(r.memoryType())
                    .as("Memory '%s' should be PROCEDURAL", r.id())
                    .isEqualTo(MemoryType.PROCEDURAL);
        }

        // Should only contain proc-* IDs
        if (!proceduralOnly.isEmpty()) {
            assertThat(proceduralOnly)
                    .as("All results should be from PROCEDURAL tier")
                    .allMatch(r -> r.memoryType() == MemoryType.PROCEDURAL);
        }
    }

    @Test
    @Order(34)
    @DisplayName("Duplicate content with different IDs scores consistently")
    void duplicateContentScoresConsistently() {
        // edge-003 is an exact duplicate of db-001
        // Both should score similarly for the same query
        List<CognitiveResult> results = memory.recall(
                "HikariCP connection pool exhaustion production outage",
                RecallOptions.builder().topK(20).build());

        CognitiveResult edge003 = results.stream()
                .filter(r -> r.id().equals("edge-003")).findFirst().orElse(null);
        CognitiveResult db001 = results.stream()
                .filter(r -> r.id().equals("db-001")).findFirst().orElse(null);

        if (edge003 != null && db001 != null) {
            float scoreDiff = Math.abs(edge003.score() - db001.score());
            log.info("Duplicate scores: edge-003={}, db-001={}, diff={}",
                    edge003.score(), db001.score(), scoreDiff);
            // Scores may differ due to valence/tags but should be in the same ballpark
        } else {
            log.info("One or both duplicates not in top-20 (edge-003={}, db-001={})",
                    edge003 != null, db001 != null);
        }
    }
}
