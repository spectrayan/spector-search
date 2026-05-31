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

import static org.assertj.core.api.Assertions.assertThat;

import com.spectrayan.spector.index.ScoredResult;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ReciprocalRankFusion}.
 */
class ReciprocalRankFusionTest {

    @Test
    void singleListPassesThrough() {
        ScoredResult[] list = {
                new ScoredResult("a", 0, 10f),
                new ScoredResult("b", 1, 8f),
                new ScoredResult("c", 2, 5f),
        };

        ScoredResult[] fused = ReciprocalRankFusion.fuse(new ScoredResult[][]{list}, 3);
        assertThat(fused).hasSize(3);
        // Original order preserved (by RRF rank score)
        assertThat(fused[0].id()).isEqualTo("a");
        assertThat(fused[1].id()).isEqualTo("b");
        assertThat(fused[2].id()).isEqualTo("c");
    }

    @Test
    void documentInBothListsRanksHigher() {
        ScoredResult[] keywordList = {
                new ScoredResult("shared", 0, 10f),
                new ScoredResult("keyword-only", 1, 8f),
        };
        ScoredResult[] vectorList = {
                new ScoredResult("shared", 0, 0.95f),
                new ScoredResult("vector-only", 2, 0.90f),
        };

        ScoredResult[] fused = ReciprocalRankFusion.fuse(
                new ScoredResult[][]{keywordList, vectorList}, 10);

        // "shared" appears in both lists → highest fused score
        assertThat(fused[0].id()).isEqualTo("shared");
    }

    @Test
    void topKLimitsResults() {
        ScoredResult[] list = {
                new ScoredResult("a", 0, 10f),
                new ScoredResult("b", 1, 8f),
                new ScoredResult("c", 2, 5f),
                new ScoredResult("d", 3, 3f),
        };

        ScoredResult[] fused = ReciprocalRankFusion.fuse(new ScoredResult[][]{list}, 2);
        assertThat(fused).hasSize(2);
    }

    @Test
    void emptyListsReturnEmpty() {
        ScoredResult[] fused = ReciprocalRankFusion.fuse(new ScoredResult[][]{}, 10);
        assertThat(fused).isEmpty();
    }

    @Test
    void fusedScoresAreDescending() {
        ScoredResult[] list1 = {
                new ScoredResult("a", 0, 10f),
                new ScoredResult("b", 1, 8f),
                new ScoredResult("c", 2, 5f),
        };
        ScoredResult[] list2 = {
                new ScoredResult("c", 2, 0.9f),
                new ScoredResult("a", 0, 0.7f),
                new ScoredResult("d", 3, 0.5f),
        };

        ScoredResult[] fused = ReciprocalRankFusion.fuse(
                new ScoredResult[][]{list1, list2}, 10);

        for (int i = 1; i < fused.length; i++) {
            assertThat(fused[i - 1].score())
                    .isGreaterThanOrEqualTo(fused[i].score());
        }
    }

    @Test
    void threeListFusion() {
        ScoredResult[] l1 = {new ScoredResult("a", 0, 1f), new ScoredResult("b", 1, 0.5f)};
        ScoredResult[] l2 = {new ScoredResult("a", 0, 1f), new ScoredResult("c", 2, 0.5f)};
        ScoredResult[] l3 = {new ScoredResult("a", 0, 1f), new ScoredResult("d", 3, 0.5f)};

        ScoredResult[] fused = ReciprocalRankFusion.fuse(
                new ScoredResult[][]{l1, l2, l3}, 10);

        // "a" appears rank-1 in all 3 lists → highest score
        assertThat(fused[0].id()).isEqualTo("a");
        // Score = 3 × 1/(60+1) ≈ 0.0492
        assertThat(fused[0].score()).isGreaterThan(fused[1].score());
    }
}
