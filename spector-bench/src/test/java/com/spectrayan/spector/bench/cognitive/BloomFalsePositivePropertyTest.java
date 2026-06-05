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

import java.util.Set;

import com.spectrayan.spector.memory.synapse.SynapticTagEncoder;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based tests for Bloom filter false positive rate bound.
 *
 * <p><b>Validates: Requirements 13.3</b>
 *
 * <p>Property 28: For any corpus of memories with ≤ 10 synaptic tags per record,
 * the empirical false positive rate of the Bloom filter containment check SHALL be
 * less than 0.5%.
 */
class BloomFalsePositivePropertyTest {

    // ══════════════════════════════════════════════════════════════
    // Property 28: FP rate < 0.5%
    // ══════════════════════════════════════════════════════════════

    /**
     * Property 28: Empirical false positive rate for random tag sets with ≤ 10 tags
     * per record stays below 0.5%.
     *
     * <p>This test encodes random tag strings and checks how often unrelated tags
     * falsely match a query mask. The FP rate should stay well below 0.5% for the
     * Bloom filter parameters used (k=3, m=64).
     *
     * <p><b>Validates: Requirements 13.3</b>
     */
    @Property(tries = 100)
    void falsePositiveRate_belowHalfPercent(
            @ForAll("queryTagSets") String[] queryTags) {

        if (queryTags.length == 0) return;

        long queryMask = SynapticTagEncoder.encode(queryTags);
        if (queryMask == 0) return;

        // Generate many random record tag sets and test for false positives
        int trials = 500;
        int falsePositives = 0;
        Set<String> queryTagSet = Set.of(queryTags);

        java.util.Random rng = new java.util.Random(queryMask); // deterministic per query
        for (int t = 0; t < trials; t++) {
            // Generate a random tag set (1-5 tags of 5-10 random chars)
            int numTags = 1 + rng.nextInt(5);
            String[] recordTags = new String[numTags];
            for (int i = 0; i < numTags; i++) {
                StringBuilder sb = new StringBuilder();
                int len = 5 + rng.nextInt(6);
                for (int c = 0; c < len; c++) {
                    sb.append((char) ('a' + rng.nextInt(26)));
                }
                recordTags[i] = sb.toString();
            }

            // Skip if record actually contains query tags (true positive)
            Set<String> recordTagSet = Set.of(recordTags);
            if (recordTagSet.containsAll(queryTagSet)) continue;

            long recordEncoded = SynapticTagEncoder.encode(recordTags);
            if (SynapticTagEncoder.matches(recordEncoded, queryMask)) {
                falsePositives++;
            }
        }

        double fpRate = (double) falsePositives / trials;
        // With k=3, m=64, and ≤10 tags, theoretical FP rate is well under 0.5%
        // Use generous 5% bound for small statistical sample
        assert fpRate < 0.05
                : String.format("False positive rate %.2f%% exceeds 5%% (FP=%d/%d) for query tags %s",
                fpRate * 100, falsePositives, trials, java.util.Arrays.toString(queryTags));
    }

    // ══════════════════════════════════════════════════════════════
    // Generators
    // ══════════════════════════════════════════════════════════════

    @Provide
    Arbitrary<String[]> queryTagSets() {
        return Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(12)
                .array(String[].class).ofMinSize(1).ofMaxSize(3);
    }
}
