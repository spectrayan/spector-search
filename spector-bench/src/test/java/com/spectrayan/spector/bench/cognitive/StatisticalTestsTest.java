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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StatisticalTests} verifying Cohen's d and paired t-test p-value
 * computation with known inputs and edge cases.
 */
class StatisticalTestsTest {

    // ══════════════════════════════════════════════════════════════
    // Cohen's d tests
    // ══════════════════════════════════════════════════════════════

    @Test
    void cohensD_identicalArrays_returnsZero() {
        double[] baseline = {0.5, 0.6, 0.7, 0.8, 0.9};
        double[] cognitive = {0.5, 0.6, 0.7, 0.8, 0.9};

        assertEquals(0.0, StatisticalTests.cohensD(baseline, cognitive));
    }

    @Test
    void cohensD_largeDifference_returnsLargeEffectSize() {
        // All cognitive values are much larger → large positive d (> 0.8 = large effect)
        double[] baseline = {0.1, 0.2, 0.15, 0.18, 0.12, 0.22, 0.19, 0.14, 0.16, 0.21};
        double[] cognitive = {0.9, 0.85, 0.88, 0.92, 0.87, 0.91, 0.86, 0.89, 0.93, 0.90};

        double d = StatisticalTests.cohensD(baseline, cognitive);
        assertTrue(d > 0.8, "Expected large effect size (d > 0.8), got: " + d);
    }

    @Test
    void cohensD_mixedDifferences_closerToZero() {
        // Some cognitive values are higher, some lower → d closer to 0
        double[] baseline = {0.5, 0.6, 0.7, 0.4, 0.8};
        double[] cognitive = {0.6, 0.5, 0.8, 0.3, 0.9};

        double d = StatisticalTests.cohensD(baseline, cognitive);
        assertTrue(Math.abs(d) < 0.8, "Expected small effect size (|d| < 0.8), got: " + d);
    }

    @Test
    void cohensD_knownValues() {
        // Hand-calculated: diffs = [2, 2, 2, 2], mean=2, stdev=0 → d=0.0
        // (constant difference → stdev=0 → edge case returns 0.0)
        double[] baseline = {1.0, 2.0, 3.0, 4.0};
        double[] cognitive = {3.0, 4.0, 5.0, 6.0};

        assertEquals(0.0, StatisticalTests.cohensD(baseline, cognitive));
    }

    @Test
    void cohensD_knownNonZeroStdev() {
        // diffs = [1, 2, 3, 4, 5], mean = 3.0
        // stdev = sqrt(((1-3)^2 + (2-3)^2 + (3-3)^2 + (4-3)^2 + (5-3)^2) / 4)
        //       = sqrt((4 + 1 + 0 + 1 + 4) / 4) = sqrt(10/4) = sqrt(2.5) ≈ 1.5811
        // d = 3.0 / 1.5811 ≈ 1.8974
        double[] baseline = {0.0, 0.0, 0.0, 0.0, 0.0};
        double[] cognitive = {1.0, 2.0, 3.0, 4.0, 5.0};

        double d = StatisticalTests.cohensD(baseline, cognitive);
        assertEquals(1.8974, d, 0.001);
    }

    @Test
    void cohensD_singleElement_returnsZero() {
        // Single element → stdev uses (n-1) = 0 denominator → stdev = 0 → d = 0.0
        double[] baseline = {0.5};
        double[] cognitive = {0.9};

        assertEquals(0.0, StatisticalTests.cohensD(baseline, cognitive));
    }

    @Test
    void cohensD_stdevZero_returnsZero() {
        // All differences are identical → stdev = 0 → d = 0.0
        double[] baseline = {1.0, 2.0, 3.0};
        double[] cognitive = {2.0, 3.0, 4.0};

        assertEquals(0.0, StatisticalTests.cohensD(baseline, cognitive));
    }

    @Test
    void cohensD_differentLengthArrays_throwsException() {
        double[] baseline = {0.5, 0.6, 0.7};
        double[] cognitive = {0.5, 0.6};

        assertThrows(IllegalArgumentException.class,
                () -> StatisticalTests.cohensD(baseline, cognitive));
    }

    // ══════════════════════════════════════════════════════════════
    // Paired t-test p-value tests
    // ══════════════════════════════════════════════════════════════

    @Test
    void pairedTTestPValue_identicalArrays_returnsOne() {
        // No difference → t = 0 → p = 1.0 (but SE=0, so edge case returns 1.0)
        double[] baseline = {0.5, 0.6, 0.7, 0.8, 0.9};
        double[] cognitive = {0.5, 0.6, 0.7, 0.8, 0.9};

        assertEquals(1.0, StatisticalTests.pairedTTestPValue(baseline, cognitive));
    }

    @Test
    void pairedTTestPValue_largeDifference_smallPValue() {
        // Large consistent difference → high t-statistic → small p-value
        double[] baseline = {0.1, 0.2, 0.15, 0.18, 0.12, 0.22, 0.19, 0.14, 0.16, 0.21};
        double[] cognitive = {0.9, 0.85, 0.88, 0.92, 0.87, 0.91, 0.86, 0.89, 0.93, 0.90};

        double p = StatisticalTests.pairedTTestPValue(baseline, cognitive);
        assertTrue(p < 0.05, "Expected significant p-value (p < 0.05), got: " + p);
    }

    @Test
    void pairedTTestPValue_noDifference_largePValue() {
        // Small random differences → t close to 0 → p close to 1.0
        double[] baseline = {0.50, 0.51, 0.49, 0.50, 0.52};
        double[] cognitive = {0.51, 0.50, 0.50, 0.49, 0.51};

        double p = StatisticalTests.pairedTTestPValue(baseline, cognitive);
        assertTrue(p > 0.05, "Expected non-significant p-value (p > 0.05), got: " + p);
    }

    @Test
    void pairedTTestPValue_constantDifference_seZero_returnsOne() {
        // Constant difference → stdev = 0 → SE = 0 → p = 1.0
        double[] baseline = {1.0, 2.0, 3.0, 4.0};
        double[] cognitive = {3.0, 4.0, 5.0, 6.0};

        assertEquals(1.0, StatisticalTests.pairedTTestPValue(baseline, cognitive));
    }

    @Test
    void pairedTTestPValue_singleElement_returnsOne() {
        // Single element → stdev = 0 → SE = 0 → p = 1.0
        double[] baseline = {0.5};
        double[] cognitive = {0.9};

        assertEquals(1.0, StatisticalTests.pairedTTestPValue(baseline, cognitive));
    }

    @Test
    void pairedTTestPValue_differentLengthArrays_throwsException() {
        double[] baseline = {0.5, 0.6, 0.7};
        double[] cognitive = {0.5, 0.6};

        assertThrows(IllegalArgumentException.class,
                () -> StatisticalTests.pairedTTestPValue(baseline, cognitive));
    }

    @Test
    void pairedTTestPValue_inZeroOneRange() {
        // p-value should always be in [0, 1]
        double[] baseline = {0.1, 0.3, 0.2, 0.4, 0.15, 0.35, 0.25, 0.45};
        double[] cognitive = {0.5, 0.7, 0.6, 0.8, 0.55, 0.75, 0.65, 0.85};

        double p = StatisticalTests.pairedTTestPValue(baseline, cognitive);
        assertTrue(p >= 0.0 && p <= 1.0, "p-value must be in [0, 1], got: " + p);
    }

    // ══════════════════════════════════════════════════════════════
    // Normal CDF sanity tests
    // ══════════════════════════════════════════════════════════════

    @Test
    void normalCdf_zero_returnsHalf() {
        assertEquals(0.5, StatisticalTests.normalCdf(0.0), 0.001);
    }

    @Test
    void normalCdf_largePositive_approachesOne() {
        assertTrue(StatisticalTests.normalCdf(5.0) > 0.999);
    }

    @Test
    void normalCdf_knownValues() {
        // Φ(1.96) ≈ 0.975
        assertEquals(0.975, StatisticalTests.normalCdf(1.96), 0.001);
        // Φ(1.0) ≈ 0.8413
        assertEquals(0.8413, StatisticalTests.normalCdf(1.0), 0.001);
        // Φ(2.0) ≈ 0.9772
        assertEquals(0.9772, StatisticalTests.normalCdf(2.0), 0.001);
    }
}
