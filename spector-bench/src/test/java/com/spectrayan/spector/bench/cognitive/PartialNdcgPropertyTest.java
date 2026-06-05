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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * Property-based tests for partial nDCG computation.
 *
 * <p><b>Validates: Requirements 11.7</b>
 *
 * <p>Property 24: For any result set with fewer than K results, nDCG SHALL be computed
 * using only the returned results without penalizing empty positions.
 */
class PartialNdcgPropertyTest {

    private final MetricsComputer metrics = new MetricsComputer();

    // ══════════════════════════════════════════════════════════════
    // Property 24: Fewer than K results → no penalty
    // ══════════════════════════════════════════════════════════════

    /**
     * Property 24a: When a result set has fewer results than K, and those results
     * are in ideal order, nDCG should still be 1.0 (no penalty for missing positions).
     *
     * <p><b>Validates: Requirements 11.7</b>
     */
    @Property(tries = 200)
    void partialResults_idealOrder_producesNdcgOne(
            @ForAll @IntRange(min = 1, max = 5) int resultSize,
            @ForAll @IntRange(min = 6, max = 20) int k) {

        // Create a small set of results with known grades, in ideal order
        Map<String, Integer> qrels = new java.util.HashMap<>();
        List<String> rankedIds = new ArrayList<>();
        for (int i = 0; i < resultSize; i++) {
            String id = "doc-" + i;
            int grade = 3 - (i % 4); // grades: 3, 2, 1, 0, 3, ...
            qrels.put(id, grade);
            rankedIds.add(id);
        }

        // Sort both the ranked list and expectations by grade descending (ideal order)
        rankedIds.sort(Comparator.comparingInt(id -> -qrels.getOrDefault(id, 0)));

        double ndcg = metrics.ndcgAtK(rankedIds, qrels, k);

        // With ideal ordering, nDCG should be 1.0 or 0.0 (if no relevant docs)
        boolean hasRelevant = qrels.values().stream().anyMatch(g -> g >= 1);
        if (hasRelevant) {
            assert ndcg == 1.0
                    : String.format("Ideal partial ranking (size=%d, k=%d) should produce nDCG=1.0, got %.6f",
                    resultSize, k, ndcg);
        }
    }

    /**
     * Property 24b: nDCG with partial results should never be penalized compared to
     * the same results with k equal to the result size.
     *
     * <p><b>Validates: Requirements 11.7</b>
     */
    @Property(tries = 200)
    void partialResults_noPenaltyForEmptyPositions(
            @ForAll("shortRankedLists") List<String> rankedIds,
            @ForAll("qrelsWithRelevant") Map<String, Integer> qrels) {

        int resultSize = rankedIds.size();
        int bigK = resultSize + 10; // K much larger than results

        double ndcgSmallK = metrics.ndcgAtK(rankedIds, qrels, resultSize);
        double ndcgBigK = metrics.ndcgAtK(rankedIds, qrels, bigK);

        // nDCG with bigger K should be the same as with K = resultSize
        // because we don't penalize empty positions
        assert Math.abs(ndcgSmallK - ndcgBigK) < 1e-10
                : String.format("nDCG should not change with larger K: " +
                "ndcg@%d=%.6f, ndcg@%d=%.6f",
                resultSize, ndcgSmallK, bigK, ndcgBigK);
    }

    // ══════════════════════════════════════════════════════════════
    // Generators
    // ══════════════════════════════════════════════════════════════

    @Provide
    Arbitrary<List<String>> shortRankedLists() {
        return Arbitraries.integers().between(1, 5)
                .flatMap(size -> Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(6)
                        .list().ofSize(size)
                        .map(list -> list.stream().distinct().collect(Collectors.toList()))
                        .filter(list -> !list.isEmpty()));
    }

    @Provide
    Arbitrary<Map<String, Integer>> qrelsWithRelevant() {
        return Arbitraries.integers().between(3, 10)
                .flatMap(size -> {
                    Arbitrary<String> ids = Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(6);
                    Arbitrary<Integer> grades = Arbitraries.integers().between(0, 3);
                    return Arbitraries.maps(ids, grades).ofMinSize(2).ofMaxSize(size)
                            .filter(m -> m.values().stream().anyMatch(g -> g >= 1));
                });
    }
}
