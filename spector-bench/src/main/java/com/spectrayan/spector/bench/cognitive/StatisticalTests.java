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

/**
 * Statistical utility methods for cognitive benchmark pass/fail determination.
 *
 * <p>Provides paired-sample Cohen's d (effect size) and an approximate paired t-test
 * p-value using the Abramowitz &amp; Stegun polynomial approximation to the normal CDF.
 *
 * <p>This is a final utility class with only static methods — no instantiation required.
 *
 * <h3>Edge-case behaviour:</h3>
 * <ul>
 *   <li>If the standard deviation of differences is 0, Cohen's d returns 0.0.</li>
 *   <li>If the standard error is 0, the paired t-test returns p = 1.0.</li>
 *   <li>If the input arrays differ in length, {@link IllegalArgumentException} is thrown.</li>
 * </ul>
 */
public final class StatisticalTests {

    private StatisticalTests() {
        // Utility class — no instantiation
    }

    /**
     * Computes Cohen's d for paired samples.
     *
     * <p>Formula:
     * <pre>
     *   differences[i] = cognitive[i] - baseline[i]
     *   d = mean(differences) / stdev(differences)
     * </pre>
     * where stdev uses Bessel's correction (n−1 denominator).
     *
     * @param baseline  per-query metric scores from the baseline retriever
     * @param cognitive per-query metric scores from the cognitive retriever
     * @return Cohen's d effect size; 0.0 if stdev of differences is 0
     * @throws IllegalArgumentException if arrays differ in length
     */
    public static double cohensD(double[] baseline, double[] cognitive) {
        validateArrays(baseline, cognitive);

        int n = baseline.length;
        double[] diffs = computeDifferences(baseline, cognitive, n);

        double mean = mean(diffs, n);
        double stdev = stdev(diffs, mean, n);

        if (stdev == 0.0) {
            return 0.0;
        }

        return mean / stdev;
    }

    /**
     * Computes an approximate two-tailed p-value for a paired t-test.
     *
     * <p>Formula:
     * <pre>
     *   t = mean(diffs) / (stdev(diffs) / sqrt(n))
     *   p = 2.0 * (1.0 - normalCdf(|t|))
     * </pre>
     * The normal CDF is approximated using the Abramowitz &amp; Stegun polynomial method,
     * which provides a good approximation for large-sample t-distributions.
     *
     * @param baseline  per-query metric scores from the baseline retriever
     * @param cognitive per-query metric scores from the cognitive retriever
     * @return approximate two-tailed p-value; 1.0 if standard error is 0
     * @throws IllegalArgumentException if arrays differ in length
     */
    public static double pairedTTestPValue(double[] baseline, double[] cognitive) {
        validateArrays(baseline, cognitive);

        int n = baseline.length;
        double[] diffs = computeDifferences(baseline, cognitive, n);

        double mean = mean(diffs, n);
        double stdev = stdev(diffs, mean, n);

        double se = stdev / Math.sqrt(n);
        if (se == 0.0) {
            return 1.0;
        }

        double t = mean / se;
        double absT = Math.abs(t);

        return 2.0 * (1.0 - normalCdf(absT));
    }

    // ══════════════════════════════════════════════════════════════
    // Internal helpers
    // ══════════════════════════════════════════════════════════════

    private static void validateArrays(double[] baseline, double[] cognitive) {
        if (baseline.length != cognitive.length) {
            throw new IllegalArgumentException(
                    "Baseline and cognitive arrays must have the same length. " +
                    "Got baseline.length=" + baseline.length +
                    ", cognitive.length=" + cognitive.length);
        }
    }

    private static double[] computeDifferences(double[] baseline, double[] cognitive, int n) {
        double[] diffs = new double[n];
        for (int i = 0; i < n; i++) {
            diffs[i] = cognitive[i] - baseline[i];
        }
        return diffs;
    }

    private static double mean(double[] values, int n) {
        if (n == 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (double v : values) {
            sum += v;
        }
        return sum / n;
    }

    private static double stdev(double[] values, double mean, int n) {
        if (n <= 1) {
            return 0.0;
        }
        double sumSq = 0.0;
        for (double v : values) {
            double diff = v - mean;
            sumSq += diff * diff;
        }
        return Math.sqrt(sumSq / (n - 1));
    }

    /**
     * Approximates the standard normal CDF using the Abramowitz &amp; Stegun
     * polynomial method (Handbook of Mathematical Functions, formula 26.2.17).
     *
     * @param x a non-negative value
     * @return Φ(x), the probability that a standard normal variable is ≤ x
     */
    static double normalCdf(double x) {
        if (x < 0) {
            return 1.0 - normalCdf(-x);
        }

        double t = 1.0 / (1.0 + 0.2316419 * x);
        double poly = t * (0.319381530
                + t * (-0.356563782
                + t * (1.781477937
                + t * (-1.821255978
                + t * 1.330274429))));
        double pdf = (1.0 / Math.sqrt(2.0 * Math.PI)) * Math.exp(-x * x / 2.0);
        return 1.0 - pdf * poly;
    }
}
