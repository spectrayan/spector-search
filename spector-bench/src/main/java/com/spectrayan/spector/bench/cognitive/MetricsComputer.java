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
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Computes standard IR evaluation metrics from ranked result lists against ground-truth
 * relevance judgments (qrels).
 *
 * <p>Supports graded relevance on a 0–3 scale. All metrics return values in [0, 1].
 *
 * <p>Edge-case behaviour (per Requirement 11.7):
 * <ul>
 *   <li>Empty result lists return 0.0 for all metrics.</li>
 *   <li>If IDCG = 0 (no relevant docs in qrels), nDCG returns 0.0.</li>
 *   <li>If {@code k} exceeds the result list size, the actual list size is used without
 *       penalising empty positions.</li>
 * </ul>
 */
public final class MetricsComputer {

    /**
     * Computes nDCG@K with graded relevance.
     *
     * <p>Formula:
     * <pre>
     *   DCG@K  = Σ_{i=0}^{K-1} (2^rel_i - 1) / log2(i + 2)
     *   IDCG@K = DCG@K for the ideal ranking (qrels sorted by grade descending)
     *   nDCG@K = DCG@K / IDCG@K
     * </pre>
     *
     * @param rankedIds the ranked list of document IDs (most relevant first)
     * @param qrels     mapping from document ID to relevance grade (0–3)
     * @param k         the cut-off depth
     * @return nDCG value in [0, 1], or 0.0 for degenerate inputs
     */
    public double ndcgAtK(List<String> rankedIds, Map<String, Integer> qrels, int k) {
        if (rankedIds == null || rankedIds.isEmpty() || qrels == null || qrels.isEmpty() || k <= 0) {
            return 0.0;
        }

        int effectiveK = Math.min(k, rankedIds.size());

        double dcg = computeDcg(rankedIds, qrels, effectiveK);
        double idcg = computeIdcg(qrels, effectiveK);

        if (idcg == 0.0) {
            return 0.0;
        }

        return dcg / idcg;
    }

    /**
     * Computes MRR@K — the reciprocal rank of the first result with relevance grade ≥ 1.
     *
     * @param rankedIds the ranked list of document IDs (most relevant first)
     * @param qrels     mapping from document ID to relevance grade (0–3)
     * @param k         the cut-off depth
     * @return reciprocal rank in (0, 1], or 0.0 if no relevant result exists in top-K
     */
    public double mrrAtK(List<String> rankedIds, Map<String, Integer> qrels, int k) {
        if (rankedIds == null || rankedIds.isEmpty() || qrels == null || qrels.isEmpty() || k <= 0) {
            return 0.0;
        }

        int effectiveK = Math.min(k, rankedIds.size());

        for (int i = 0; i < effectiveK; i++) {
            String docId = rankedIds.get(i);
            int grade = qrels.getOrDefault(docId, 0);
            if (grade >= 1) {
                return 1.0 / (i + 1);
            }
        }

        return 0.0;
    }

    /**
     * Computes Recall@K — the fraction of all relevant documents (grade ≥ 1) that appear
     * in the top-K results.
     *
     * @param rankedIds the ranked list of document IDs (most relevant first)
     * @param qrels     mapping from document ID to relevance grade (0–3)
     * @param k         the cut-off depth
     * @return recall value in [0, 1], or 0.0 if no relevant docs exist in qrels
     */
    public double recallAtK(List<String> rankedIds, Map<String, Integer> qrels, int k) {
        if (rankedIds == null || rankedIds.isEmpty() || qrels == null || qrels.isEmpty() || k <= 0) {
            return 0.0;
        }

        long totalRelevant = qrels.values().stream().filter(grade -> grade >= 1).count();
        if (totalRelevant == 0) {
            return 0.0;
        }

        int effectiveK = Math.min(k, rankedIds.size());
        long retrieved = 0;

        for (int i = 0; i < effectiveK; i++) {
            String docId = rankedIds.get(i);
            int grade = qrels.getOrDefault(docId, 0);
            if (grade >= 1) {
                retrieved++;
            }
        }

        return (double) retrieved / totalRelevant;
    }

    // ══════════════════════════════════════════════════════════════
    // Internal helpers
    // ══════════════════════════════════════════════════════════════

    private double computeDcg(List<String> rankedIds, Map<String, Integer> qrels, int effectiveK) {
        double dcg = 0.0;
        for (int i = 0; i < effectiveK; i++) {
            String docId = rankedIds.get(i);
            int rel = qrels.getOrDefault(docId, 0);
            if (rel > 0) {
                dcg += (Math.pow(2, rel) - 1) / log2(i + 2);
            }
        }
        return dcg;
    }

    private double computeIdcg(Map<String, Integer> qrels, int effectiveK) {
        // Sort all relevance grades descending to form the ideal ranking
        List<Integer> grades = new ArrayList<>(qrels.values());
        grades.sort(Collections.reverseOrder());

        // Compute DCG of the ideal ranking up to effectiveK
        int idealK = Math.min(effectiveK, grades.size());
        double idcg = 0.0;
        for (int i = 0; i < idealK; i++) {
            int rel = grades.get(i);
            if (rel > 0) {
                idcg += (Math.pow(2, rel) - 1) / log2(i + 2);
            }
        }
        return idcg;
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }
}
