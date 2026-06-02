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

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;

/**
 * Fluent assertion helpers for cognitive recall results.
 *
 * <p>These utilities provide semantic, readable assertions that validate
 * specific expected-vs-actual behavior of the cognitive memory system.</p>
 *
 * <h3>Usage</h3>
 * <pre>
 * E2EAssertions.assertRecallContains(results, "db-001", "db-003");
 * E2EAssertions.assertScoreDescending(results);
 * E2EAssertions.assertTopResultContains(results, "PostgreSQL");
 * </pre>
 */
public final class E2EAssertions {

    private E2EAssertions() {}

    // ══════════════════════════════════════════════════════════════
    // ID PRESENCE / ABSENCE
    // ══════════════════════════════════════════════════════════════

    /**
     * Asserts that the recall results contain ALL of the specified memory IDs.
     *
     * @param results the recall results
     * @param expectedIds IDs that must be present
     */
    public static void assertRecallContains(List<CognitiveResult> results, String... expectedIds) {
        Set<String> actualIds = results.stream()
                .map(CognitiveResult::id)
                .collect(Collectors.toSet());

        for (String id : expectedIds) {
            assertThat(actualIds)
                    .as("Expected memory '%s' in recall results (got: %s)", id, actualIds)
                    .contains(id);
        }
    }

    /**
     * Asserts that the recall results contain AT LEAST ONE of the specified memory IDs.
     *
     * @param results the recall results
     * @param possibleIds IDs where at least one must be present
     */
    public static void assertRecallContainsAny(List<CognitiveResult> results, String... possibleIds) {
        Set<String> actualIds = results.stream()
                .map(CognitiveResult::id)
                .collect(Collectors.toSet());

        boolean anyFound = Arrays.stream(possibleIds).anyMatch(actualIds::contains);
        assertThat(anyFound)
                .as("Expected at least one of %s in results (got: %s)",
                        Arrays.toString(possibleIds), actualIds)
                .isTrue();
    }

    /**
     * Asserts that NONE of the specified IDs appear in the recall results.
     *
     * @param results the recall results
     * @param excludedIds IDs that must NOT be present
     */
    public static void assertRecallExcludes(List<CognitiveResult> results, String... excludedIds) {
        Set<String> actualIds = results.stream()
                .map(CognitiveResult::id)
                .collect(Collectors.toSet());

        for (String id : excludedIds) {
            assertThat(actualIds)
                    .as("Memory '%s' should NOT be in recall results", id)
                    .doesNotContain(id);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // SCORE VALIDATION
    // ══════════════════════════════════════════════════════════════

    /**
     * Asserts that scores are in monotonically descending order.
     */
    public static void assertScoreDescending(List<CognitiveResult> results) {
        for (int i = 1; i < results.size(); i++) {
            assertThat(results.get(i).score())
                    .as("Score at position %d should be <= position %d", i, i - 1)
                    .isLessThanOrEqualTo(results.get(i - 1).score());
        }
    }

    /**
     * Asserts that all scores are within the valid [0.0, maxScore] range.
     */
    public static void assertScoresInRange(List<CognitiveResult> results, float maxScore) {
        for (CognitiveResult r : results) {
            assertThat(r.score())
                    .as("Score of '%s' should be in [0, %f]", r.id(), maxScore)
                    .isBetween(0f, maxScore);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // CONTENT VALIDATION
    // ══════════════════════════════════════════════════════════════

    /**
     * Asserts that the top result's text contains at least one of the specified keywords.
     */
    public static void assertTopResultContains(List<CognitiveResult> results, String... keywords) {
        assertThat(results).as("Results should not be empty").isNotEmpty();
        String topText = results.getFirst().text().toLowerCase();

        boolean found = Arrays.stream(keywords)
                .anyMatch(kw -> topText.contains(kw.toLowerCase()));
        assertThat(found)
                .as("Top result text should contain one of %s (got: '%s')",
                        Arrays.toString(keywords),
                        topText.substring(0, Math.min(80, topText.length())))
                .isTrue();
    }

    /**
     * Asserts that a specific memory ID appears within the top-N results.
     */
    public static void assertInTopN(List<CognitiveResult> results, String expectedId, int topN) {
        List<String> topIds = results.stream()
                .limit(topN)
                .map(CognitiveResult::id)
                .toList();
        assertThat(topIds)
                .as("Expected '%s' in top-%d results (got: %s)", expectedId, topN, topIds)
                .contains(expectedId);
    }

    // ══════════════════════════════════════════════════════════════
    // TYPE / TIER VALIDATION
    // ══════════════════════════════════════════════════════════════

    /**
     * Asserts that all results are of one of the specified memory types.
     */
    public static void assertResultsHaveType(List<CognitiveResult> results, MemoryType... allowedTypes) {
        Set<MemoryType> allowed = Set.of(allowedTypes);
        for (CognitiveResult r : results) {
            assertThat(r.memoryType())
                    .as("Result '%s' should be one of %s", r.id(), allowed)
                    .isIn(allowedTypes);
        }
    }

    /**
     * Asserts that results span at least {@code minTiers} different memory types.
     */
    public static void assertMultipleTiers(List<CognitiveResult> results, int minTiers) {
        Set<MemoryType> tiers = results.stream()
                .map(CognitiveResult::memoryType)
                .collect(Collectors.toSet());
        assertThat(tiers.size())
                .as("Results should span at least %d tiers (got: %s)", minTiers, tiers)
                .isGreaterThanOrEqualTo(minTiers);
    }

    // ══════════════════════════════════════════════════════════════
    // VALENCE VALIDATION
    // ══════════════════════════════════════════════════════════════

    /**
     * Asserts that all results have valence within the specified range.
     */
    public static void assertValenceInRange(List<CognitiveResult> results, byte min, byte max) {
        for (CognitiveResult r : results) {
            assertThat(r.valence())
                    .as("Valence of '%s' should be in [%d, %d]", r.id(), min, max)
                    .isBetween(min, max);
        }
    }
}
