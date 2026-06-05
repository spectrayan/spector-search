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

import com.spectrayan.spector.memory.synapse.SynapticTagEncoder;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based tests for tag overlap ratio formula.
 *
 * <p><b>Validates: Requirements 13.4</b>
 *
 * <p>Property 29: For any record_tags and query_mask bit patterns, the tag overlap ratio
 * SHALL equal bitCount(record_tags & query_mask) / bitCount(query_mask).
 */
class TagOverlapPropertyTest {

    // ══════════════════════════════════════════════════════════════
    // Property 29: overlap = bitCount(tags & mask) / bitCount(mask)
    // ══════════════════════════════════════════════════════════════

    /**
     * Property 29a: overlapRatio computes bitCount(tags & mask) / bitCount(mask).
     *
     * <p><b>Validates: Requirements 13.4</b>
     */
    @Property(tries = 200)
    void overlapRatio_matchesFormula(
            @ForAll("tagValues") long recordTags,
            @ForAll("nonZeroMasks") long queryMask) {

        float actual = SynapticTagEncoder.overlapRatio(recordTags, queryMask);

        int queryBits = Long.bitCount(queryMask);
        int matchedBits = Long.bitCount(recordTags & queryMask);
        float expected = (float) matchedBits / queryBits;

        assert Math.abs(actual - expected) < 1e-6f
                : String.format("overlapRatio mismatch: actual=%.6f, expected=%.6f " +
                "(recordTags=%016X, queryMask=%016X)",
                actual, expected, recordTags, queryMask);
    }

    /**
     * Property 29b: When queryMask is 0, overlapRatio returns 1.0 (no filter).
     *
     * <p><b>Validates: Requirements 13.4</b>
     */
    @Property(tries = 100)
    void zeroQueryMask_returnsOne(@ForAll("tagValues") long recordTags) {
        float overlap = SynapticTagEncoder.overlapRatio(recordTags, 0L);
        assert overlap == 1.0f
                : "Zero query mask should return 1.0, got: " + overlap;
    }

    /**
     * Property 29c: Overlap ratio is always in [0.0, 1.0].
     *
     * <p><b>Validates: Requirements 13.4</b>
     */
    @Property(tries = 200)
    void overlapRatio_boundedZeroToOne(
            @ForAll("tagValues") long recordTags,
            @ForAll("tagValues") long queryMask) {

        float overlap = SynapticTagEncoder.overlapRatio(recordTags, queryMask);

        assert overlap >= 0.0f && overlap <= 1.0f
                : String.format("Overlap ratio out of bounds: %.6f", overlap);
    }

    /**
     * Property 29d: Full match (record contains all query bits) → overlap = 1.0.
     *
     * <p><b>Validates: Requirements 13.4</b>
     */
    @Property(tries = 200)
    void fullMatch_overlapIsOne(@ForAll("nonZeroMasks") long queryMask) {
        // Record that has all query bits set (plus possibly more)
        long recordTags = queryMask | 0x8000_0000_0000_0000L; // queryMask + extra bit

        float overlap = SynapticTagEncoder.overlapRatio(recordTags, queryMask);

        assert overlap == 1.0f
                : String.format("Full match should produce overlap=1.0, got %.6f", overlap);
    }

    // ══════════════════════════════════════════════════════════════
    // Generators
    // ══════════════════════════════════════════════════════════════

    @Provide
    Arbitrary<Long> tagValues() {
        return Arbitraries.longs();
    }

    @Provide
    Arbitrary<Long> nonZeroMasks() {
        return Arbitraries.longs().filter(l -> l != 0);
    }
}
