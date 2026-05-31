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
package com.spectrayan.spector.query;

import com.spectrayan.spector.index.ScoredResult;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Reciprocal Rank Fusion (RRF) — merges multiple ranked result lists
 * into a single unified ranking without score normalization.
 *
 * <h3>Formula</h3>
 * <pre>
 *   RRF_score(d) = Σ 1 / (k + rank(r, d))
 * </pre>
 * <p>where {@code k} is a constant (default 60) that mitigates the impact
 * of high-ranking outliers, and {@code rank(r, d)} is the 1-based position
 * of document d in result list r.</p>
 *
 * <p>Documents appearing near the top of <em>multiple</em> lists receive
 * the highest fused scores. This is robust, parameter-free (beyond k),
 * and works across incompatible score scales (BM25 vs cosine).</p>
 */
public final class ReciprocalRankFusion {

    /** Default RRF constant — standard value from the original paper. */
    public static final int DEFAULT_K = 60;

    private ReciprocalRankFusion() {
        // utility class
    }

    /**
     * Fuses multiple result lists using RRF with the default k=60.
     *
     * @param resultLists the ranked result lists to fuse
     * @param topK        max number of results to return
     * @return fused results sorted by RRF score (descending)
     */
    public static ScoredResult[] fuse(ScoredResult[][] resultLists, int topK) {
        return fuse(resultLists, topK, DEFAULT_K);
    }

    /**
     * Fuses multiple result lists using RRF with a custom k.
     *
     * @param resultLists the ranked result lists to fuse
     * @param topK        max number of results to return
     * @param k           the RRF constant
     * @return fused results sorted by RRF score (descending)
     */
    public static ScoredResult[] fuse(ScoredResult[][] resultLists, int topK, int k) {
        // Accumulate RRF scores per document ID
        Map<String, RrfAccumulator> accumulators = new HashMap<>();

        for (ScoredResult[] results : resultLists) {
            for (int rank = 0; rank < results.length; rank++) {
                ScoredResult result = results[rank];
                accumulators
                        .computeIfAbsent(result.id(), id -> new RrfAccumulator(result.id(), result.index()))
                        .addRank(rank + 1, k);  // 1-based rank
            }
        }

        // Sort by fused score descending and take top-K
        return accumulators.values().stream()
                .map(acc -> new ScoredResult(acc.id, acc.index, acc.score))
                .sorted()  // ScoredResult.compareTo → descending
                .limit(topK)
                .toArray(ScoredResult[]::new);
    }

    /** Accumulates RRF score for a single document across lists. */
    private static class RrfAccumulator {
        final String id;
        final int index;
        float score;

        RrfAccumulator(String id, int index) {
            this.id = id;
            this.index = index;
            this.score = 0f;
        }

        void addRank(int rank, int k) {
            score += 1.0f / (k + rank);
        }
    }
}
