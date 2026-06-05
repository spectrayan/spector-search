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
package com.spectrayan.spector.bench.cognitive;

import com.spectrayan.spector.memory.habituation.HabituationPenalty;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * Property-based tests for habituation score attenuation.
 *
 * <p><b>Validates: Requirements 15.1, 15.2</b>
 *
 * <p>Property 31: For any memory recalled N times in sequence, its score SHALL be
 * attenuated by the habituation formula relative to its initial score.
 *
 * <p>The HabituationPenalty uses formula: 1.0 / (1.0 + (N-1) * decayRate)
 * With default decayRate=0.2, this produces decreasing penalties for repeated returns.
 */
class HabituationPropertyTest {

    private static final float DEFAULT_DECAY_RATE = 0.2f;

    // ══════════════════════════════════════════════════════════════
    // Property 31: Score attenuated by habituation formula
    // ══════════════════════════════════════════════════════════════

    /**
     * Property 31a: After N recalls, the penalty equals 1/(1+(N-1)*decayRate).
     *
     * <p><b>Validates: Requirements 15.1, 15.2</b>
     */
    @Property(tries = 200)
    void habituationPenalty_matchesFormula(
            @ForAll("memoryIds") String memoryId,
            @ForAll @IntRange(min = 1, max = 20) int recallCount) {

        HabituationPenalty penalty = new HabituationPenalty(DEFAULT_DECAY_RATE);

        // Record N recalls
        float lastPenalty = 1.0f;
        for (int i = 0; i < recallCount; i++) {
            lastPenalty = penalty.recordAndComputePenalty(memoryId);
        }

        // Verify formula: 1.0 / (1.0 + (N-1) * decayRate)
        float expected = 1.0f / (1.0f + (recallCount - 1) * DEFAULT_DECAY_RATE);

        assert Math.abs(lastPenalty - expected) < 1e-5f
                : String.format("After %d recalls, penalty should be %.6f, got %.6f",
                recallCount, expected, lastPenalty);
    }

    /**
     * Property 31b: Habituation penalty is monotonically decreasing with more recalls.
     *
     * <p><b>Validates: Requirements 15.1</b>
     */
    @Property(tries = 100)
    void habituationPenalty_decreasesMonotonically(
            @ForAll("memoryIds") String memoryId,
            @ForAll @IntRange(min = 2, max = 10) int totalRecalls) {

        HabituationPenalty penalty = new HabituationPenalty(DEFAULT_DECAY_RATE);

        float prevPenalty = Float.MAX_VALUE;
        for (int i = 0; i < totalRecalls; i++) {
            float current = penalty.recordAndComputePenalty(memoryId);
            assert current <= prevPenalty
                    : String.format("Penalty should decrease: recall %d gave %.6f > previous %.6f",
                    i + 1, current, prevPenalty);
            prevPenalty = current;
        }
    }

    /**
     * Property 31c: First recall always has penalty = 1.0 (no penalty).
     *
     * <p><b>Validates: Requirements 15.2</b>
     */
    @Property(tries = 200)
    void firstRecall_noPenalty(@ForAll("memoryIds") String memoryId) {
        HabituationPenalty penalty = new HabituationPenalty(DEFAULT_DECAY_RATE);

        float firstPenalty = penalty.recordAndComputePenalty(memoryId);

        assert firstPenalty == 1.0f
                : "First recall should have penalty 1.0, got: " + firstPenalty;
    }

    /**
     * Property 31d: Penalty is always positive (never reaches zero).
     *
     * <p><b>Validates: Requirements 15.1</b>
     */
    @Property(tries = 100)
    void penalty_alwaysPositive(
            @ForAll("memoryIds") String memoryId,
            @ForAll @IntRange(min = 1, max = 100) int recallCount) {

        HabituationPenalty penalty = new HabituationPenalty(DEFAULT_DECAY_RATE);

        float lastPenalty = 1.0f;
        for (int i = 0; i < recallCount; i++) {
            lastPenalty = penalty.recordAndComputePenalty(memoryId);
        }

        assert lastPenalty > 0.0f
                : "Habituation penalty should always be positive, got: " + lastPenalty;
    }

    // ══════════════════════════════════════════════════════════════
    // Generators
    // ══════════════════════════════════════════════════════════════

    @Provide
    Arbitrary<String> memoryIds() {
        return Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(15)
                .map(s -> "mem-" + s);
    }
}
