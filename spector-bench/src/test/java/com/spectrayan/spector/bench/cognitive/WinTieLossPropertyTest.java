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
import java.util.List;

import static net.jqwik.api.Arbitraries.doubles;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based tests for win/tie/loss classification in
 * {@link CognitiveBenchmarkHarness}.
 *
 * <p><b>Validates: Requirements 2.4</b>
 *
 * <p>Property 5: For any array of baseline and cognitive nDCG values,
 * wins + ties + losses always equals the total query count. The classification
 * partitions the input space exhaustively with no gaps or overlaps.
 */
class WinTieLossPropertyTest {

    private static final double WIN_THRESHOLD = CognitiveBenchmarkHarness.WIN_THRESHOLD;

    /**
     * Property 5: wins + ties + losses == total query count.
     *
     * <p>Given any random arrays of baseline and cognitive nDCG values of the same
     * length, classifying each pair as win/tie/loss based on the threshold (0.001)
     * must produce counts that sum to the array length.</p>
     *
     * <p><b>Validates: Requirements 2.4</b>
     */
    @Property(tries = 500)
    void winsTiesLosses_alwaysEqualTotalCount(
            @ForAll("ndcgPairs") List<double[]> pairs) {

        // Build QueryResult list from the pairs
        List<ReportWriter.QueryResult> results = new ArrayList<>(pairs.size());
        for (int i = 0; i < pairs.size(); i++) {
            double baselineNdcg = pairs.get(i)[0];
            double cognitiveNdcg = pairs.get(i)[1];
            double delta = cognitiveNdcg - baselineNdcg;

            results.add(new ReportWriter.QueryResult(
                    "q-" + i, baselineNdcg, cognitiveNdcg, delta,
                    "", "BALANCED", 10));
        }

        // Execute the classification
        CognitiveBenchmarkHarness.WinTieLossResult wtl =
                CognitiveBenchmarkHarness.computeWinTieLoss(results);

        // Property: wins + ties + losses == total count
        int total = wtl.wins() + wtl.ties() + wtl.losses();
        assert total == pairs.size()
                : "Partition violation: " + wtl.wins() + " + " + wtl.ties() + " + "
                  + wtl.losses() + " = " + total + " != " + pairs.size();
    }

    /**
     * Property 5b: Individual classification is exhaustive and mutually exclusive.
     *
     * <p>For any single delta value, exactly one of the three conditions holds:
     * win (delta &gt; 0.001), tie (|delta| ≤ 0.001), or loss (delta &lt; -0.001).
     *
     * <p><b>Validates: Requirements 2.4</b>
     */
    @Property(tries = 1000)
    void singleDelta_classifiesExactlyOnce(@ForAll("deltas") double delta) {
        boolean isWin = delta > WIN_THRESHOLD;
        boolean isTie = Math.abs(delta) <= WIN_THRESHOLD;
        boolean isLoss = delta < -WIN_THRESHOLD;

        int count = (isWin ? 1 : 0) + (isTie ? 1 : 0) + (isLoss ? 1 : 0);
        assert count == 1
                : "Delta " + delta + " classified as " + count + " categories (win="
                  + isWin + ", tie=" + isTie + ", loss=" + isLoss + ")";
    }

    /**
     * Property 5c: The classifyDelta helper agrees with the threshold-based counting.
     *
     * <p><b>Validates: Requirements 2.4</b>
     */
    @Property(tries = 500)
    void classifyDelta_matchesThresholdLogic(@ForAll("deltas") double delta) {
        int classification = CognitiveBenchmarkHarness.classifyDelta(delta);

        if (delta > WIN_THRESHOLD) {
            assert classification == 1 : "Expected win (1), got " + classification;
        } else if (delta < -WIN_THRESHOLD) {
            assert classification == -1 : "Expected loss (-1), got " + classification;
        } else {
            assert classification == 0 : "Expected tie (0), got " + classification;
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Generators
    // ══════════════════════════════════════════════════════════════

    @Provide
    Arbitrary<List<double[]>> ndcgPairs() {
        Arbitrary<double[]> pair = doubles().between(0.0, 1.0)
                .array(double[].class).ofSize(2);
        return pair.list().ofMinSize(1).ofMaxSize(200);
    }

    @Provide
    Arbitrary<Double> deltas() {
        return doubles().between(-1.0, 1.0);
    }
}
