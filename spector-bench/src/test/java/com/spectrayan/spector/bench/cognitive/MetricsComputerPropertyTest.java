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
 * Property-based tests for {@link MetricsComputer}.
 *
 * <p><b>Validates: Requirements 2.3, 11.7</b>
 *
 * <p>Property 4: All metrics are bounded in [0, 1] for arbitrary inputs, and ideal ranking
 * produces nDCG = 1.0.
 */
class MetricsComputerPropertyTest {

    private final MetricsComputer metrics = new MetricsComputer();

    // ══════════════════════════════════════════════════════════════
    // Property 4: All metrics ∈ [0, 1]
    // ══════════════════════════════════════════════════════════════

    /**
     * Property 4a: For any randomly generated ranked list and qrels, all metrics
     * (nDCG@K, MRR@K, Recall@K) fall within [0, 1].
     *
     * <p><b>Validates: Requirements 2.3</b>
     */
    @Property(tries = 200)
    void allMetrics_areBoundedBetweenZeroAndOne(
            @ForAll("rankedIds") List<String> rankedIds,
            @ForAll("qrels") Map<String, Integer> qrels,
            @ForAll @IntRange(min = 1, max = 20) int k) {

        double ndcg = metrics.ndcgAtK(rankedIds, qrels, k);
        double mrr = metrics.mrrAtK(rankedIds, qrels, k);
        double recall = metrics.recallAtK(rankedIds, qrels, k);

        assert ndcg >= 0.0 && ndcg <= 1.0
                : "nDCG out of bounds: " + ndcg;
        assert mrr >= 0.0 && mrr <= 1.0
                : "MRR out of bounds: " + mrr;
        assert recall >= 0.0 && recall <= 1.0
                : "Recall out of bounds: " + recall;
    }

    /**
     * Property 4b: If the ranked list is sorted by relevance grade descending (ideal ranking),
     * nDCG@K = 1.0.
     *
     * <p><b>Validates: Requirements 2.3, 11.7</b>
     */
    @Property(tries = 200)
    void idealRanking_producesNdcgOfOne(
            @ForAll("qrelsWithRelevant") Map<String, Integer> qrels,
            @ForAll @IntRange(min = 1, max = 20) int k) {

        // Build ideal ranking: sort document IDs by grade descending
        List<String> idealRanked = qrels.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        double ndcg = metrics.ndcgAtK(idealRanked, qrels, k);

        assert ndcg == 1.0 || ndcg == 0.0
                : "Ideal ranking should produce nDCG=1.0 (or 0.0 if no relevant docs), got: " + ndcg;

        // If there is at least one relevant doc and k covers it, nDCG must be 1.0
        boolean hasRelevant = qrels.values().stream().anyMatch(g -> g >= 1);
        if (hasRelevant) {
            assert ndcg == 1.0
                    : "Ideal ranking with relevant docs should produce nDCG=1.0, got: " + ndcg;
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Generators
    // ══════════════════════════════════════════════════════════════

    @Provide
    Arbitrary<List<String>> rankedIds() {
        return Arbitraries.integers().between(1, 30)
                .flatMap(size -> Arbitraries.strings().alpha().ofMinLength(2).ofMaxLength(6)
                        .list().ofSize(size)
                        .map(list -> list.stream().distinct().collect(Collectors.toList())));
    }

    @Provide
    Arbitrary<Map<String, Integer>> qrels() {
        return Arbitraries.integers().between(1, 20)
                .flatMap(size -> {
                    Arbitrary<String> ids = Arbitraries.strings().alpha().ofMinLength(2).ofMaxLength(6);
                    Arbitrary<Integer> grades = Arbitraries.integers().between(0, 3);
                    return Arbitraries.maps(ids, grades).ofMinSize(1).ofMaxSize(size);
                });
    }

    @Provide
    Arbitrary<Map<String, Integer>> qrelsWithRelevant() {
        // Ensure at least one entry with grade >= 1
        return Arbitraries.integers().between(2, 15)
                .flatMap(size -> {
                    Arbitrary<String> ids = Arbitraries.strings().alpha().ofMinLength(2).ofMaxLength(6);
                    Arbitrary<Integer> grades = Arbitraries.integers().between(0, 3);
                    return Arbitraries.maps(ids, grades).ofMinSize(2).ofMaxSize(size)
                            .filter(m -> m.values().stream().anyMatch(g -> g >= 1));
                });
    }
}
