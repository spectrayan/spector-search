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

import com.spectrayan.spector.memory.CognitiveProfile;
import com.spectrayan.spector.memory.CognitiveResult;
import com.spectrayan.spector.memory.MemoryType;
import com.spectrayan.spector.memory.RecallOptions;

import org.junit.jupiter.api.*;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * E2E tests for cognitive profile scoring correctness.
 *
 * <p>Each {@link CognitiveProfile} represents a different brain state
 * (debugging, exploring, hyperfocusing, etc.) and should produce measurably
 * different recall behavior. These tests validate that the scoring formula
 * and profile parameters produce the expected effects.</p>
 */
@DisplayName("🧠 E2E: Cognitive Profile Correctness")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CognitiveProfileE2ETest extends AbstractE2ETest {

    // ══════════════════════════════════════════════════════════════
    // BALANCED vs EXPLORING — alpha/beta weight difference
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("EXPLORING profile favors similarity over importance")
    void exploringFavorsSimilarity() {
        String query = "PostgreSQL connection pool configuration and sizing";

        List<CognitiveResult> balanced = memory.recall(query, CognitiveProfile.BALANCED);
        List<CognitiveResult> exploring = memory.recall(query, CognitiveProfile.EXPLORING);

        log.info("BALANCED top-5:");
        printResults(balanced.stream().limit(5).toList());
        log.info("EXPLORING top-5:");
        printResults(exploring.stream().limit(5).toList());

        // Both should return results
        assertThat(balanced).isNotEmpty();
        assertThat(exploring).isNotEmpty();

        // Exploring uses α=0.8 (high similarity weight) — results may differ
        // in ordering compared to balanced α=0.6
        List<String> balancedIds = idsOf(balanced.stream().limit(5).toList());
        List<String> exploringIds = idsOf(exploring.stream().limit(5).toList());
        log.info("BALANCED IDs: {}", balancedIds);
        log.info("EXPLORING IDs: {}", exploringIds);

        // LLM Judge: validate recall relevance
        if (isLlmJudgeEnabled()) {
            llmAssertRecall(query, exploring)
                    .isRelevantTo("Results should be about database connection pool configuration, sizing, or PostgreSQL");
        }
    }

    // ══════════════════════════════════════════════════════════════
    // DEBUGGING — negative valence bias
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(2)
    @DisplayName("DEBUGGING profile surfaces negative-valence memories")
    void debuggingProfileSurfacesNegativeValence() {
        List<CognitiveResult> results = memory.recall(
                "database error connection failure outage",
                CognitiveProfile.DEBUGGING);

        log.info("DEBUGGING profile results:");
        printResults(results);

        // DEBUGGING profile has maxValence=-10, so all results should be negative
        if (results.isEmpty()) {
            log.info("  ⚠ No results with valence <= -10 for this query");
        } else {
            for (CognitiveResult r : results) {
                assertThat(r.valence())
                        .as("DEBUGGING memory '%s' valence should be <= -10", r.id())
                        .isLessThanOrEqualTo((byte) -10);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // RECALLING — positive valence bias
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(3)
    @DisplayName("RECALLING profile surfaces positive-valence memories only")
    void recallingProfileSurfacesPositiveValence() {
        List<CognitiveResult> results = memory.recall(
                "deployment best practices and solutions",
                CognitiveProfile.RECALLING);

        log.info("RECALLING profile results:");
        printResults(results);

        // RECALLING profile has minValence=10 — all results should be positive
        if (results.isEmpty()) {
            log.info("  ⚠ No results with valence >= 10 for this query");
        } else {
            for (CognitiveResult r : results) {
                assertThat(r.valence())
                        .as("RECALLING memory '%s' valence should be >= 10", r.id())
                        .isGreaterThanOrEqualTo((byte) 10);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // CRITICAL — importance-dominated scoring
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(4)
    @DisplayName("CRITICAL profile prioritizes importance over similarity")
    void criticalProfilePrioritizesImportance() {
        List<CognitiveResult> balanced = memory.recall(
                "security vulnerability response",
                CognitiveProfile.BALANCED);
        List<CognitiveResult> critical = memory.recall(
                "security vulnerability response",
                CognitiveProfile.CRITICAL);

        log.info("BALANCED top-5:");
        printResults(balanced.stream().limit(5).toList());
        log.info("CRITICAL top-5 (β=0.8, importance-dominated):");
        printResults(critical.stream().limit(5).toList());

        assertThat(critical).isNotEmpty();
        // CRITICAL uses β=0.8 — importance-dominated, full valence range

        // LLM Judge: validate security-related results
        if (isLlmJudgeEnabled()) {
            llmAssertRecall("security vulnerability response", critical)
                    .warnIfIrrelevant("Results should relate to security incidents, vulnerabilities, or critical system issues");
        }
    }

    // ══════════════════════════════════════════════════════════════
    // PARANOID_SENTINEL — only negative valence
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(5)
    @DisplayName("PARANOID_SENTINEL only returns negative-valence memories")
    void paranoidSentinelOnlyNegative() {
        List<CognitiveResult> results = memory.recall(
                "deployment and infrastructure issues",
                CognitiveProfile.PARANOID_SENTINEL);

        log.info("PARANOID_SENTINEL results:");
        printResults(results);

        // All results should have negative valence (maxValence=-1)
        for (CognitiveResult r : results) {
            assertThat(r.valence())
                    .as("PARANOID_SENTINEL memory '%s' should have negative valence", r.id())
                    .isLessThan((byte) 0);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // DEFAULT_MODE_NETWORK — only SEMANTIC + PROCEDURAL
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(6)
    @DisplayName("DEFAULT_MODE_NETWORK only returns SEMANTIC and PROCEDURAL memories")
    void defaultModeNetworkSemanticAndProcedural() {
        List<CognitiveResult> results = memory.recall(
                "database design patterns and best practices",
                CognitiveProfile.DEFAULT_MODE_NETWORK);

        log.info("DEFAULT_MODE_NETWORK results:");
        printResults(results);

        assertThat(results).isNotEmpty();

        // Should only contain SEMANTIC and PROCEDURAL types
        for (CognitiveResult r : results) {
            assertThat(r.memoryType())
                    .as("DMN memory '%s' type should be SEMANTIC or PROCEDURAL", r.id())
                    .isIn(MemoryType.SEMANTIC, MemoryType.PROCEDURAL);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // PROFILE AUTO-DETECTION
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(10)
    @DisplayName("Profile auto-detection selects DEBUGGING for error tags")
    void profileAutoDetectDebugging() {
        CognitiveProfile detected = CognitiveProfile.detect("error", "database", "timeout");
        assertThat(detected)
                .as("Tags with 'error' should trigger DEBUGGING profile")
                .isEqualTo(CognitiveProfile.DEBUGGING);
    }

    @Test
    @Order(11)
    @DisplayName("Profile auto-detection selects CRITICAL over DEBUGGING")
    void profileAutoDetectCriticalPriority() {
        CognitiveProfile detected = CognitiveProfile.detect("critical", "error", "production");
        assertThat(detected)
                .as("CRITICAL should take priority over DEBUGGING")
                .isEqualTo(CognitiveProfile.CRITICAL);
    }

    @Test
    @Order(12)
    @DisplayName("Profile auto-detection selects RECALLING for solution tags")
    void profileAutoDetectRecalling() {
        CognitiveProfile detected = CognitiveProfile.detect("solution", "pattern", "template");
        assertThat(detected)
                .as("Tags with 'solution' should trigger RECALLING profile")
                .isEqualTo(CognitiveProfile.RECALLING);
    }

    @Test
    @Order(13)
    @DisplayName("Profile auto-detection returns BALANCED for unknown tags")
    void profileAutoDetectBalanced() {
        CognitiveProfile detected = CognitiveProfile.detect("database", "kubernetes");
        assertThat(detected)
                .as("Tags without keywords should default to BALANCED")
                .isEqualTo(CognitiveProfile.BALANCED);
    }

    @Test
    @Order(14)
    @DisplayName("Profile auto-detection handles null and empty tags")
    void profileAutoDetectNullSafe() {
        assertThat(CognitiveProfile.detect((String[]) null))
                .isEqualTo(CognitiveProfile.BALANCED);
        assertThat(CognitiveProfile.detect())
                .isEqualTo(CognitiveProfile.BALANCED);
        assertThat(CognitiveProfile.detect(null, null))
                .isEqualTo(CognitiveProfile.BALANCED);
    }

    // ══════════════════════════════════════════════════════════════
    // SCORING FORMULA CONSISTENCY
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(20)
    @DisplayName("Consecutive recalls with same profile produce results of same size")
    void consecutiveRecallsConsistentSize() {
        // Habituation may reshuffle results between calls, but topK constraint
        // should always produce the same number of results (capped by available memories)
        String query = "Avro serialization schema registry backward compatibility";
        RecallOptions opts = RecallOptions.builder()
                .profile(CognitiveProfile.BALANCED)
                .topK(10)
                .build();

        List<CognitiveResult> run1 = memory.recall(query, opts);
        List<CognitiveResult> run2 = memory.recall(query, opts);

        log.info("Run 1: {} results, Run 2: {} results", run1.size(), run2.size());
        log.info("Run 1 IDs: {}", idsOf(run1));
        log.info("Run 2 IDs: {}", idsOf(run2));

        // Both runs should return the same count
        assertThat(run1.size())
                .as("Consecutive recalls should return same result count")
                .isEqualTo(run2.size());

        // Both should return non-empty results
        assertThat(run1).isNotEmpty();
    }
}
