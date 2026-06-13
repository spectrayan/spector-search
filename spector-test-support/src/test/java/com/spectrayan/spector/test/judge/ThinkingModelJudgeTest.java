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
package com.spectrayan.spector.test.judge;

import com.spectrayan.spector.embed.GenerationOptions;
import com.spectrayan.spector.embed.TextGenerationProvider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link LlmTestJudge} handling of thinking model responses.
 *
 * <p>Thinking models (gemma4, qwen3) wrap their reasoning in {@code <think>} tags.
 * The judge must extract JSON verdicts from these responses regardless of where
 * the JSON appears — outside or inside the thinking block.</p>
 */
@DisplayName("LlmTestJudge — Thinking Model Support")
class ThinkingModelJudgeTest {

    // ══════════════════════════════════════════════════════════════
    // STANDARD RESPONSES (non-thinking models)
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Standard Responses")
    class StandardResponseTests {

        @Test
        @DisplayName("Parses clean JSON response")
        void cleanJson() {
            var judge = createJudge("{\"relevant\": true, \"confidence\": 0.95, \"reasoning\": \"Good match\"}");
            JudgeVerdict verdict = judge.judgeRelevance("test query", List.of("result1"), "any");

            assertTrue(verdict.relevant());
            assertEquals(0.95f, verdict.confidence(), 0.01);
            assertEquals("Good match", verdict.reasoning());
        }

        @Test
        @DisplayName("Parses JSON with surrounding text")
        void jsonWithSurroundingText() {
            var judge = createJudge(
                    "Here is my analysis:\n\n{\"relevant\": false, \"confidence\": 0.3, \"reasoning\": \"Not a match\"}\n\nDone.");
            JudgeVerdict verdict = judge.judgeRelevance("test", List.of("r"), "any");

            assertFalse(verdict.relevant());
        }

        @Test
        @DisplayName("Parses JSON in markdown code fence")
        void markdownCodeFence() {
            var judge = createJudge(
                    "```json\n{\"relevant\": true, \"confidence\": 0.8, \"reasoning\": \"OK\"}\n```");
            JudgeVerdict verdict = judge.judgeRelevance("test", List.of("r"), "any");

            assertTrue(verdict.relevant());
        }
    }

    // ══════════════════════════════════════════════════════════════
    // THINKING MODEL RESPONSES
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Thinking Model Responses")
    class ThinkingModelTests {

        @Test
        @DisplayName("Extracts JSON after <think> block")
        void jsonAfterThinkBlock() {
            var judge = createJudge(
                    "<think>I need to analyze this carefully. The results look relevant because " +
                    "they mention memory systems.</think>\n" +
                    "{\"relevant\": true, \"confidence\": 0.9, \"reasoning\": \"Contains memory topics\"}");
            JudgeVerdict verdict = judge.judgeRelevance("test", List.of("r"), "any");

            assertTrue(verdict.relevant());
            assertEquals("Contains memory topics", verdict.reasoning());
        }

        @Test
        @DisplayName("Extracts JSON from inside <think> block when nothing outside")
        void jsonInsideThinkBlock() {
            var judge = createJudge(
                    "<think>Let me evaluate. " +
                    "{\"relevant\": true, \"confidence\": 0.85, \"reasoning\": \"Found inside thinking\"} " +
                    "That's my verdict.</think>");
            JudgeVerdict verdict = judge.judgeRelevance("test", List.of("r"), "any");

            assertTrue(verdict.relevant());
            assertEquals("Found inside thinking", verdict.reasoning());
        }

        @Test
        @DisplayName("Handles multiline <think> blocks")
        void multilineThinkBlock() {
            var judge = createJudge(
                    "<think>\nStep 1: Read the query\nStep 2: Analyze results\n" +
                    "Step 3: The results are relevant because they discuss vector search\n</think>\n" +
                    "{\"relevant\": true, \"confidence\": 0.88, \"reasoning\": \"Discusses vector search\"}");
            JudgeVerdict verdict = judge.judgeRelevance("test", List.of("r"), "any");

            assertTrue(verdict.relevant());
        }

        @Test
        @DisplayName("Handles empty response after stripping think tags")
        void emptyAfterStripping() {
            var judge = createJudge("<think>Just thinking, no output.</think>");
            JudgeVerdict verdict = judge.judgeRelevance("test", List.of("r"), "any");

            // parseFailure() returns relevant=true (soft fail) with confidence=0
            assertTrue(verdict.relevant());
            assertEquals(0f, verdict.confidence(), 0.01);
            assertTrue(verdict.reasoning().contains("Failed to parse") || verdict.reasoning().contains("no output"));
        }

        @Test
        @DisplayName("Handles qwen3-style thinking with /no_think")
        void qwen3NoThinkPrefix() {
            var judge = createJudge(
                    "<think>Internal reasoning here.</think>" +
                    "{\"relevant\": false, \"confidence\": 0.2, \"reasoning\": \"Irrelevant results\"}");
            JudgeVerdict verdict = judge.judgeRelevance("test", List.of("r"), "any");

            assertFalse(verdict.relevant());
        }
    }

    // ══════════════════════════════════════════════════════════════
    // EDGE CASES
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Empty response returns parse failure verdict")
        void emptyResponse() {
            var judge = createJudge("");
            JudgeVerdict verdict = judge.judgeRelevance("test", List.of("r"), "any");

            // parseFailure() returns relevant=true (soft fail) with confidence=0
            assertTrue(verdict.relevant());
            assertEquals(0f, verdict.confidence(), 0.01);
        }

        @Test
        @DisplayName("Null response returns parse failure verdict")
        void nullResponse() {
            var judge = createJudge(null);
            JudgeVerdict verdict = judge.judgeRelevance("test", List.of("r"), "any");

            // parseFailure() returns relevant=true (soft fail) with confidence=0
            assertTrue(verdict.relevant());
            assertEquals(0f, verdict.confidence(), 0.01);
        }

        @Test
        @DisplayName("Malformed JSON returns parse failure verdict")
        void malformedJson() {
            var judge = createJudge("{relevant: true, broken json}");
            JudgeVerdict verdict = judge.judgeRelevance("test", List.of("r"), "any");

            // parseFailure() returns relevant=true (soft fail) with confidence=0
            assertTrue(verdict.relevant());
            assertEquals(0f, verdict.confidence(), 0.01);
        }

        @Test
        @DisplayName("Missing relevant field treated as parse failure (regex requires it)")
        void missingRelevantField() {
            var judge = createJudge("{\"confidence\": 0.9, \"reasoning\": \"Has confidence but no relevant\"}");
            JudgeVerdict verdict = judge.judgeRelevance("test", List.of("r"), "any");

            // JSON_EXTRACT regex requires "relevant" field — without it, treated as parse failure
            assertTrue(verdict.relevant()); // parseFailure returns true (soft fail)
            assertEquals(0f, verdict.confidence(), 0.01);
        }

        @Test
        @DisplayName("Missing confidence defaults to 0.5")
        void missingConfidence() {
            var judge = createJudge("{\"relevant\": true, \"reasoning\": \"OK\"}");
            JudgeVerdict verdict = judge.judgeRelevance("test", List.of("r"), "any");

            assertTrue(verdict.relevant());
            assertEquals(0.5f, verdict.confidence(), 0.01);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════

    /**
     * Creates a judge backed by a mock LLM that always returns the given response.
     */
    private LlmTestJudge createJudge(String fixedResponse) {
        TextGenerationProvider mockLlm = new TextGenerationProvider() {
            @Override
            public String generate(String prompt, GenerationOptions options) {
                return fixedResponse;
            }

            @Override
            public String generate(String prompt) {
                return fixedResponse;
            }

            @Override
            public String modelName() {
                return "mock-thinking-model";
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        };

        return LlmTestJudge.create(mockLlm);
    }
}
