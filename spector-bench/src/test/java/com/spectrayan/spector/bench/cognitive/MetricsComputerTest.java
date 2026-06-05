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

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MetricsComputer} verifying nDCG@K, MRR@K, and Recall@K
 * against known values and edge cases.
 */
class MetricsComputerTest {

    private MetricsComputer metrics;

    @BeforeEach
    void setUp() {
        metrics = new MetricsComputer();
    }

    // ══════════════════════════════════════════════════════════════
    // nDCG@K tests
    // ══════════════════════════════════════════════════════════════

    @Test
    void ndcgAtK_perfectRanking_returnsOne() {
        // Ideal ranking: docs sorted by grade descending → nDCG = 1.0
        List<String> ranked = List.of("d1", "d2", "d3");
        Map<String, Integer> qrels = Map.of("d1", 3, "d2", 2, "d3", 1);

        assertEquals(1.0, metrics.ndcgAtK(ranked, qrels, 3), 1e-9);
    }

    @Test
    void ndcgAtK_reverseRanking_lessThanOne() {
        // Worst ranking: docs sorted ascending → nDCG < 1.0
        List<String> ranked = List.of("d3", "d2", "d1");
        Map<String, Integer> qrels = Map.of("d1", 3, "d2", 2, "d3", 1);

        double ndcg = metrics.ndcgAtK(ranked, qrels, 3);
        assertEquals(true, ndcg > 0.0 && ndcg < 1.0);
    }

    @Test
    void ndcgAtK_knownValue() {
        // Known hand-calculated nDCG@3:
        // Ranked: [d2(grade=2), d1(grade=3), d3(grade=1)]
        // DCG = (2^2-1)/log2(2) + (2^3-1)/log2(3) + (2^1-1)/log2(4)
        //     = 3/1 + 7/1.585 + 1/2 = 3.0 + 4.416 + 0.5 = 7.916
        // IDCG (ideal: d1=3, d2=2, d3=1):
        //     = (2^3-1)/log2(2) + (2^2-1)/log2(3) + (2^1-1)/log2(4)
        //     = 7/1 + 3/1.585 + 1/2 = 7.0 + 1.893 + 0.5 = 9.393
        // nDCG = 7.916 / 9.393 ≈ 0.8428
        List<String> ranked = List.of("d2", "d1", "d3");
        Map<String, Integer> qrels = Map.of("d1", 3, "d2", 2, "d3", 1);

        double ndcg = metrics.ndcgAtK(ranked, qrels, 3);
        assertEquals(0.8428, ndcg, 0.001);
    }

    @Test
    void ndcgAtK_emptyResults_returnsZero() {
        Map<String, Integer> qrels = Map.of("d1", 3, "d2", 2);

        assertEquals(0.0, metrics.ndcgAtK(List.of(), qrels, 10));
        assertEquals(0.0, metrics.ndcgAtK(null, qrels, 10));
    }

    @Test
    void ndcgAtK_noRelevantDocsInQrels_returnsZero() {
        // IDCG = 0 when all grades are 0
        List<String> ranked = List.of("d1", "d2");
        Map<String, Integer> qrels = Map.of("d1", 0, "d2", 0);

        assertEquals(0.0, metrics.ndcgAtK(ranked, qrels, 2));
    }

    @Test
    void ndcgAtK_kLargerThanList_usesActualSize() {
        // k=10 but list has 2 items → use actual size (no penalty for empty positions, Req 11.7)
        List<String> ranked = List.of("d1", "d2");
        Map<String, Integer> qrels = Map.of("d1", 3, "d2", 2);

        double ndcg = metrics.ndcgAtK(ranked, qrels, 10);
        // With perfect ranking in first 2 positions, nDCG should be 1.0
        assertEquals(1.0, ndcg, 1e-9);
    }

    @Test
    void ndcgAtK_partialResults_noEmptyPositionPenalty() {
        // Only 3 results returned, k=5, qrels has 5 relevant docs
        // Should compute nDCG using only 3 returned docs (no penalty for missing 2)
        List<String> ranked = List.of("d1", "d2", "d3");
        Map<String, Integer> qrels = Map.of("d1", 3, "d2", 2, "d3", 1, "d4", 3, "d5", 2);

        double ndcg = metrics.ndcgAtK(ranked, qrels, 5);
        // DCG uses actual 3 results; IDCG uses top-3 ideal grades from qrels (3, 3, 2)
        // This should produce a valid value < 1.0 but > 0.0
        assertEquals(true, ndcg > 0.0 && ndcg < 1.0);
    }

    @Test
    void ndcgAtK_unknownDocsInRanking_treatedAsGradeZero() {
        // Document "unknown" is not in qrels → grade = 0
        List<String> ranked = List.of("unknown", "d1");
        Map<String, Integer> qrels = Map.of("d1", 3, "d2", 2);

        double ndcg = metrics.ndcgAtK(ranked, qrels, 2);
        assertEquals(true, ndcg > 0.0 && ndcg < 1.0);
    }

    // ══════════════════════════════════════════════════════════════
    // MRR@K tests
    // ══════════════════════════════════════════════════════════════

    @Test
    void mrrAtK_firstResultRelevant_returnsOne() {
        List<String> ranked = List.of("d1", "d2", "d3");
        Map<String, Integer> qrels = Map.of("d1", 3, "d2", 1);

        assertEquals(1.0, metrics.mrrAtK(ranked, qrels, 3));
    }

    @Test
    void mrrAtK_secondResultRelevant_returnsHalf() {
        List<String> ranked = List.of("d3", "d1", "d2");
        Map<String, Integer> qrels = Map.of("d1", 2, "d2", 1);

        assertEquals(0.5, metrics.mrrAtK(ranked, qrels, 3));
    }

    @Test
    void mrrAtK_noRelevantInTopK_returnsZero() {
        List<String> ranked = List.of("d3", "d4", "d5");
        Map<String, Integer> qrels = Map.of("d1", 3, "d2", 2);

        assertEquals(0.0, metrics.mrrAtK(ranked, qrels, 3));
    }

    @Test
    void mrrAtK_emptyResults_returnsZero() {
        Map<String, Integer> qrels = Map.of("d1", 3);

        assertEquals(0.0, metrics.mrrAtK(List.of(), qrels, 10));
        assertEquals(0.0, metrics.mrrAtK(null, qrels, 10));
    }

    @Test
    void mrrAtK_gradeZeroNotRelevant() {
        // Grade 0 should not count as relevant
        List<String> ranked = List.of("d1", "d2");
        Map<String, Integer> qrels = Map.of("d1", 0, "d2", 1);

        assertEquals(0.5, metrics.mrrAtK(ranked, qrels, 2));
    }

    @Test
    void mrrAtK_kLargerThanList_usesActualSize() {
        List<String> ranked = List.of("d2", "d1");
        Map<String, Integer> qrels = Map.of("d1", 2);

        assertEquals(0.5, metrics.mrrAtK(ranked, qrels, 100));
    }

    // ══════════════════════════════════════════════════════════════
    // Recall@K tests
    // ══════════════════════════════════════════════════════════════

    @Test
    void recallAtK_allRelevantRetrieved_returnsOne() {
        List<String> ranked = List.of("d1", "d2", "d3");
        Map<String, Integer> qrels = Map.of("d1", 3, "d2", 2, "d3", 1);

        assertEquals(1.0, metrics.recallAtK(ranked, qrels, 3));
    }

    @Test
    void recallAtK_halfRelevantRetrieved_returnsHalf() {
        List<String> ranked = List.of("d1", "d4");
        Map<String, Integer> qrels = Map.of("d1", 3, "d2", 2, "d4", 0);

        assertEquals(0.5, metrics.recallAtK(ranked, qrels, 2));
    }

    @Test
    void recallAtK_noRelevantRetrieved_returnsZero() {
        List<String> ranked = List.of("d3", "d4");
        Map<String, Integer> qrels = Map.of("d1", 3, "d2", 2, "d3", 0, "d4", 0);

        assertEquals(0.0, metrics.recallAtK(ranked, qrels, 2));
    }

    @Test
    void recallAtK_emptyResults_returnsZero() {
        Map<String, Integer> qrels = Map.of("d1", 3);

        assertEquals(0.0, metrics.recallAtK(List.of(), qrels, 10));
        assertEquals(0.0, metrics.recallAtK(null, qrels, 10));
    }

    @Test
    void recallAtK_noRelevantInQrels_returnsZero() {
        List<String> ranked = List.of("d1", "d2");
        Map<String, Integer> qrels = Map.of("d1", 0, "d2", 0);

        assertEquals(0.0, metrics.recallAtK(ranked, qrels, 2));
    }

    @Test
    void recallAtK_kLargerThanList_usesActualSize() {
        List<String> ranked = List.of("d1");
        Map<String, Integer> qrels = Map.of("d1", 3, "d2", 2);

        // 1 out of 2 relevant docs retrieved
        assertEquals(0.5, metrics.recallAtK(ranked, qrels, 100));
    }

    @Test
    void recallAtK_gradeZeroExcludedFromRelevant() {
        List<String> ranked = List.of("d1", "d2", "d3");
        Map<String, Integer> qrels = Map.of("d1", 0, "d2", 1, "d3", 0);

        // Only d2 is relevant (grade >= 1); it's in the results → recall = 1.0
        assertEquals(1.0, metrics.recallAtK(ranked, qrels, 3));
    }

    // ══════════════════════════════════════════════════════════════
    // Edge case tests (Task 4.4)
    // ══════════════════════════════════════════════════════════════

    @Test
    void allMetrics_nullQrels_returnZero() {
        List<String> ranked = List.of("d1");

        assertEquals(0.0, metrics.ndcgAtK(ranked, null, 5));
        assertEquals(0.0, metrics.mrrAtK(ranked, null, 5));
        assertEquals(0.0, metrics.recallAtK(ranked, null, 5));
    }

    @Test
    void allMetrics_emptyQrels_returnZero() {
        List<String> ranked = List.of("d1");
        Map<String, Integer> emptyQrels = Collections.emptyMap();

        assertEquals(0.0, metrics.ndcgAtK(ranked, emptyQrels, 5));
        assertEquals(0.0, metrics.mrrAtK(ranked, emptyQrels, 5));
        assertEquals(0.0, metrics.recallAtK(ranked, emptyQrels, 5));
    }

    @Test
    void allMetrics_kZero_returnZero() {
        List<String> ranked = List.of("d1");
        Map<String, Integer> qrels = Map.of("d1", 3);

        assertEquals(0.0, metrics.ndcgAtK(ranked, qrels, 0));
        assertEquals(0.0, metrics.mrrAtK(ranked, qrels, 0));
        assertEquals(0.0, metrics.recallAtK(ranked, qrels, 0));
    }
}
