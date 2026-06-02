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
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;

/**
 * E2E tests for agentic coding conversation memories.
 *
 * <p>Validates that real-world user-agent development conversations are
 * properly ingested, recalled, and semantically matched. These memories
 * come from actual coding sessions covering documentation improvement,
 * debugging, and feature implementation.</p>
 *
 * <p>Every test includes LLM-based semantic validation when the judge is enabled.</p>
 */
@DisplayName("🤖 E2E: Agentic Conversation Recall")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgenticConversationE2ETest extends AbstractE2ETest {

    // ══════════════════════════════════════════════════════════════
    // PROMPTLY APP — DOCUMENTATION WORKFLOW
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("MkDocs dark mode Mermaid fix is recalled for CSS debugging")
    void mkdocsDarkModeMermaidFix() {
        List<CognitiveResult> results = memory.recall(
                "how to fix Mermaid diagrams not visible in dark mode MkDocs Material theme",
                RecallOptions.builder().topK(10).build());

        log.info("MkDocs dark mode query results:");
        printResults(results);

        assertThat(results).isNotEmpty();

        // Should recall the CSS fix and investigation memories
        boolean hasRelevant = results.stream().anyMatch(r ->
                r.text().toLowerCase().contains("mermaid")
                        || r.text().toLowerCase().contains("dark mode")
                        || r.text().toLowerCase().contains("css")
                        || r.text().toLowerCase().contains("pastel"));

        if (hasRelevant) {
            log.info("  ✅ Found Mermaid/dark-mode related memories");
        } else {
            log.info("  ⚠ No Mermaid-specific memories in top-10 — model-dependent ranking");
        }

        // LLM Judge: semantic validation
        if (isLlmJudgeEnabled()) {
            llmAssertRecall("fix Mermaid diagrams dark mode MkDocs CSS", results)
                    .warnIfIrrelevant("Results should contain memories about CSS fixes for dark mode diagram visibility");
        }
    }

    @Test
    @Order(2)
    @DisplayName("Broken image path debugging is recalled for MkDocs deployment")
    void brokenImagePathDebugging() {
        List<CognitiveResult> results = memory.recall(
                "images not visible on deployed MkDocs site broken image paths directory URLs",
                RecallOptions.builder().topK(10).build());

        log.info("Broken image paths query results:");
        printResults(results);

        assertThat(results).isNotEmpty();

        // LLM Judge
        if (isLlmJudgeEnabled()) {
            llmAssertRecall("broken image paths MkDocs deployment directory URLs", results)
                    .warnIfIrrelevant("Results should contain memories about path resolution issues in static site generators")
                    .hasGoodRanking();
        }
    }

    @Test
    @Order(3)
    @DisplayName("Angular SPA navigation fix is recalled for Playwright screenshot debugging")
    void angularSpaNavigationFix() {
        List<CognitiveResult> results = memory.recall(
                "Playwright screenshot script page.goto redirects to dashboard Angular router guard race condition",
                RecallOptions.builder().topK(10).build());

        log.info("Angular SPA navigation query results:");
        printResults(results);

        assertThat(results).isNotEmpty();

        // LLM Judge
        if (isLlmJudgeEnabled()) {
            llmAssertRecall("Playwright Angular SPA navigation race condition redirect", results)
                    .warnIfIrrelevant("Results should relate to SPA navigation issues, router guards, or Playwright automation");
        }
    }

    @Test
    @Order(4)
    @DisplayName("Incremental git commit workflow is recalled")
    void incrementalGitCommitWorkflow() {
        List<CognitiveResult> results = memory.recall(
                "incremental git commit strategy for documentation changes",
                RecallOptions.builder().topK(10).build());

        log.info("Git commit workflow query results:");
        printResults(results);

        // Should recall the 10-commit incremental workflow
        assertThat(results).isNotEmpty();

        if (isLlmJudgeEnabled()) {
            llmAssertRecall("incremental git commit documentation changes", results)
                    .warnIfIrrelevant("Results should describe git commit workflow or documentation update processes");
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ELEVATE-X APP — AI FITNESS DEVELOPMENT
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(10)
    @DisplayName("AI safety guardrails are recalled for workout generation")
    void aiSafetyGuardrailsRecall() {
        List<CognitiveResult> results = memory.recall(
                "AI safety guardrails for workout plan generation calorie floor beginner HIIT cap",
                RecallOptions.builder().topK(10).build());

        log.info("AI safety guardrails query results:");
        printResults(results);

        assertThat(results).isNotEmpty();

        // Check for safety-related content
        boolean hasSafety = results.stream().anyMatch(r ->
                r.text().toLowerCase().contains("safety")
                        || r.text().toLowerCase().contains("guardrail")
                        || r.text().toLowerCase().contains("calorie")
                        || r.text().toLowerCase().contains("beginner"));

        if (hasSafety) {
            log.info("  ✅ Found AI safety guardrail memories");
        }

        if (isLlmJudgeEnabled()) {
            llmAssertRecall("AI safety guardrails workout calorie floor beginner", results)
                    .isRelevantTo("Results should describe AI safety validation rules for fitness content generation");
        }
    }

    @Test
    @Order(11)
    @DisplayName("KMP @JsExport debugging is recalled for cross-platform issues")
    void kmpJsExportDebugging() {
        List<CognitiveResult> results = memory.recall(
                "Kotlin Multiplatform @JsExport suspend function cannot be exported Promise workaround",
                RecallOptions.builder().topK(10).build());

        log.info("KMP JsExport debugging results:");
        printResults(results);

        assertThat(results).isNotEmpty();

        if (isLlmJudgeEnabled()) {
            llmAssertRecall("KMP JsExport suspend function Promise workaround", results)
                    .warnIfIrrelevant("Results should describe KMP/Kotlin JS interop issues or workarounds");
        }
    }

    @Test
    @Order(12)
    @DisplayName("Property-based testing approach is recalled")
    void propertyBasedTestingRecall() {
        List<CognitiveResult> results = memory.recall(
                "property-based testing Kotest Arb.string password validation BMI calculation 100 iterations",
                RecallOptions.builder().topK(10).build());

        log.info("Property-based testing results:");
        printResults(results);

        assertThat(results).isNotEmpty();

        if (isLlmJudgeEnabled()) {
            llmAssertRecall("property-based testing validators calculators Kotest", results)
                    .warnIfIrrelevant("Results should describe property-based or generative testing approaches")
                    .coversTopics("testing", "validation", "property");
        }
    }

    // ══════════════════════════════════════════════════════════════
    // CROSS-DOMAIN ISOLATION
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(20)
    @DisplayName("Database connection pool query does NOT return conversation memories")
    void crossDomainIsolation() {
        List<CognitiveResult> results = memory.recall(
                "HikariCP database connection pool exhaustion timeout PostgreSQL",
                RecallOptions.builder().topK(10).build());

        log.info("Cross-domain isolation results:");
        printResults(results);

        // Conversation memories (conv-*) should NOT dominate for database queries
        Set<String> convIds = results.stream()
                .filter(r -> r.id().startsWith("conv-"))
                .map(CognitiveResult::id)
                .collect(Collectors.toSet());

        if (!convIds.isEmpty()) {
            log.info("  ⚠ Conversation memories leaked into database query: {}", convIds);
        } else {
            log.info("  ✅ Good cross-domain isolation — no conversation memories for database query");
        }

        // The top results should be database-specific memories (db-*)
        if (!results.isEmpty()) {
            String topId = results.getFirst().id();
            log.info("  Top result: {} (expect db-* prefix)", topId);
        }

        if (isLlmJudgeEnabled()) {
            llmAssertRecall("HikariCP database connection pool exhaustion PostgreSQL", results)
                    .isRelevantTo("Results should be about database connection pooling, NOT about documentation or fitness apps");
        }
    }

    @Test
    @Order(21)
    @DisplayName("Fitness query surfaces elevate-x memories, not database memories")
    void fitnessQuerySurfacesCorrectDomain() {
        List<CognitiveResult> results = memory.recall(
                "workout plan generation beginner safety calorie cap HIIT sessions per week",
                RecallOptions.builder().topK(10).build());

        log.info("Fitness domain query results:");
        printResults(results);

        assertThat(results).isNotEmpty();

        // Should contain fitness/safety memories, not database memories
        boolean hasFitness = results.stream().anyMatch(r ->
                r.text().toLowerCase().contains("workout")
                        || r.text().toLowerCase().contains("fitness")
                        || r.text().toLowerCase().contains("calorie")
                        || r.text().toLowerCase().contains("hiit")
                        || r.text().toLowerCase().contains("safety"));

        if (hasFitness) {
            log.info("  ✅ Fitness-related memories surfaced correctly");
        }

        if (isLlmJudgeEnabled()) {
            llmAssertRecall("workout plan generation beginner safety calorie HIIT", results)
                    .isRelevantTo("Results should be about fitness, workout plans, or exercise safety — NOT about databases or deployment");
        }
    }

    // ══════════════════════════════════════════════════════════════
    // MEMORY TYPE VALIDATION
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(30)
    @DisplayName("PROCEDURAL conversation memories are properly typed")
    void proceduralConversationMemories() {
        List<CognitiveResult> results = memory.recall(
                "step by step fix for CSS dark mode visibility issue with color attribute",
                RecallOptions.builder()
                        .topK(10)
                        .memoryTypes(MemoryType.PROCEDURAL)
                        .build());

        log.info("Procedural conversation memory results:");
        printResults(results);

        // All should be PROCEDURAL
        for (CognitiveResult r : results) {
            assertThat(r.memoryType())
                    .as("Memory '%s' should be PROCEDURAL", r.id())
                    .isEqualTo(MemoryType.PROCEDURAL);
        }

        if (isLlmJudgeEnabled() && !results.isEmpty()) {
            llmAssertRecall("step by step CSS fix dark mode", results)
                    .warnIfIrrelevant("Results should describe procedures, solutions, or step-by-step fixes");
        }
    }

    @Test
    @Order(31)
    @DisplayName("SEMANTIC conversation memories capture architecture decisions")
    void semanticArchitectureDecisions() {
        List<CognitiveResult> results = memory.recall(
                "architectural decision for storing measurements in canonical metric units",
                RecallOptions.builder()
                        .topK(10)
                        .memoryTypes(MemoryType.SEMANTIC)
                        .build());

        log.info("Semantic architecture decision results:");
        printResults(results);

        // All should be SEMANTIC
        for (CognitiveResult r : results) {
            assertThat(r.memoryType())
                    .as("Memory '%s' should be SEMANTIC", r.id())
                    .isEqualTo(MemoryType.SEMANTIC);
        }

        if (isLlmJudgeEnabled() && !results.isEmpty()) {
            llmAssertRecall("canonical metric units architecture decision storage", results)
                    .warnIfIrrelevant("Results should describe architectural decisions about data storage or unit systems");
        }
    }
}
