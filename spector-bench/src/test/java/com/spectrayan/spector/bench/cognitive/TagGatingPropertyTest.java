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
 * Property-based tests for synaptic tag containment gating.
 *
 * <p><b>Validates: Requirements 3.2, 13.2</b>
 *
 * <p>Property 7: For any query with a non-zero synaptic tag mask and any corpus memory
 * in the result set, the containment property (record_tags & query_mask) == query_mask
 * SHALL hold.
 */
class TagGatingPropertyTest {

    // ══════════════════════════════════════════════════════════════
    // Property 7: Tag containment check correctness
    // ══════════════════════════════════════════════════════════════

    /**
     * Property 7a: For any record_tags that pass the matches() check, the containment
     * property (record_tags & query_mask) == query_mask holds.
     *
     * <p><b>Validates: Requirements 3.2, 13.2</b>
     */
    @Property(tries = 200)
    void matchingRecords_satisfyContainment(
            @ForAll("nonZeroMasks") long queryMask,
            @ForAll("tagValues") long recordTags) {

        boolean matches = SynapticTagEncoder.matches(recordTags, queryMask);

        if (matches) {
            // Containment must hold
            assert (recordTags & queryMask) == queryMask
                    : String.format("Containment violated: recordTags=%016X & queryMask=%016X != queryMask",
                    recordTags, queryMask);
        }
    }

    /**
     * Property 7b: If containment fails, matches() must return false.
     * Conversely, if matches() returns true, containment must hold.
     *
     * <p><b>Validates: Requirements 3.2</b>
     */
    @Property(tries = 200)
    void containmentFailure_impliesNoMatch(
            @ForAll("nonZeroMasks") long queryMask,
            @ForAll("tagValues") long recordTags) {

        boolean containmentHolds = (recordTags & queryMask) == queryMask;
        boolean matches = SynapticTagEncoder.matches(recordTags, queryMask);

        // matches() ↔ containment (they should be equivalent)
        assert matches == containmentHolds
                : String.format("matches() disagrees with containment: matches=%b, containment=%b " +
                "(recordTags=%016X, queryMask=%016X)", matches, containmentHolds, recordTags, queryMask);
    }

    /**
     * Property 7c: Encoding known tags and checking containment on the encoding
     * should always pass (no false negatives for exact same tags).
     *
     * <p><b>Validates: Requirements 13.2</b>
     */
    @Property(tries = 200)
    void encodedTags_alwaysMatchThemselves(
            @ForAll("tagLists") String[] tags) {

        long encoded = SynapticTagEncoder.encode(tags);
        long queryMask = SynapticTagEncoder.encode(tags);

        assert SynapticTagEncoder.matches(encoded, queryMask)
                : "Encoded tags should always match themselves as query mask";
    }

    // ══════════════════════════════════════════════════════════════
    // Generators
    // ══════════════════════════════════════════════════════════════

    @Provide
    Arbitrary<Long> nonZeroMasks() {
        return Arbitraries.longs().filter(l -> l != 0);
    }

    @Provide
    Arbitrary<Long> tagValues() {
        return Arbitraries.longs();
    }

    @Provide
    Arbitrary<String[]> tagLists() {
        return Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(15)
                .array(String[].class).ofMinSize(1).ofMaxSize(8);
    }
}
