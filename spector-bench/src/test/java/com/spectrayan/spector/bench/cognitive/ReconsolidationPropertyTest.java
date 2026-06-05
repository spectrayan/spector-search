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

import com.spectrayan.spector.memory.synapse.DecayStrategy;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

/**
 * Property-based tests for LTP reconsolidation bucket shift.
 *
 * <p><b>Validates: Requirements 3.6, 12.2</b>
 *
 * <p>Property 11: For any rawBucket ∈ [0, 11] and recallCount ≥ 0,
 * adjustForReconsolidation(rawBucket, recallCount) SHALL equal
 * rawBucket >> min(recallCount, 5), always producing a value ∈ [0, rawBucket].
 */
class ReconsolidationPropertyTest {

    // ══════════════════════════════════════════════════════════════
    // Property 11: adjustedBucket = rawBucket >> min(recallCount, 5)
    // ══════════════════════════════════════════════════════════════

    /**
     * Property 11a: The adjusted bucket equals rawBucket >> min(recallCount, 5).
     *
     * <p><b>Validates: Requirements 3.6, 12.2</b>
     */
    @Property(tries = 200)
    void adjustedBucket_equalsRightShift(
            @ForAll @IntRange(min = 0, max = 11) int rawBucket,
            @ForAll @IntRange(min = 0, max = 100) int recallCount) {

        int adjusted = DecayStrategy.adjustForReconsolidation(rawBucket, recallCount);
        int expected = rawBucket >> Math.min(recallCount, 5);

        assert adjusted == expected
                : String.format("adjustForReconsolidation(%d, %d) = %d, expected %d",
                rawBucket, recallCount, adjusted, expected);
    }

    /**
     * Property 11b: The adjusted bucket is always in [0, rawBucket].
     *
     * <p><b>Validates: Requirements 3.6</b>
     */
    @Property(tries = 200)
    void adjustedBucket_isWithinRange(
            @ForAll @IntRange(min = 0, max = 11) int rawBucket,
            @ForAll @IntRange(min = 0, max = 1000) int recallCount) {

        int adjusted = DecayStrategy.adjustForReconsolidation(rawBucket, recallCount);

        assert adjusted >= 0
                : String.format("Adjusted bucket %d is negative for rawBucket=%d, recallCount=%d",
                adjusted, rawBucket, recallCount);
        assert adjusted <= rawBucket
                : String.format("Adjusted bucket %d exceeds rawBucket=%d for recallCount=%d",
                adjusted, rawBucket, recallCount);
    }

    /**
     * Property 11c: With zero recall count, adjusted bucket equals raw bucket.
     *
     * <p><b>Validates: Requirements 3.6</b>
     */
    @Property(tries = 100)
    void zeroRecallCount_noChange(
            @ForAll @IntRange(min = 0, max = 11) int rawBucket) {

        int adjusted = DecayStrategy.adjustForReconsolidation(rawBucket, 0);

        assert adjusted == rawBucket
                : String.format("Zero recall count should not change bucket: raw=%d, adjusted=%d",
                rawBucket, adjusted);
    }

    /**
     * Property 11d: With recall count >= 5, bucket is maximally shifted.
     *
     * <p><b>Validates: Requirements 12.2</b>
     */
    @Property(tries = 100)
    void highRecallCount_maxShift(
            @ForAll @IntRange(min = 0, max = 11) int rawBucket,
            @ForAll @IntRange(min = 5, max = 1000) int recallCount) {

        int adjusted = DecayStrategy.adjustForReconsolidation(rawBucket, recallCount);
        int expectedMax = rawBucket >> 5;

        assert adjusted == expectedMax
                : String.format("Recall count >= 5 should shift by 5: raw=%d, rc=%d, adjusted=%d, expected=%d",
                rawBucket, recallCount, adjusted, expectedMax);
    }
}
