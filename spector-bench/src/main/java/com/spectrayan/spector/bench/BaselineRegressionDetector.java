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
package com.spectrayan.spector.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Detects performance regressions by comparing JMH JSON results against a baseline.
 *
 * <p>A regression is flagged when the current benchmark score is worse than the
 * baseline by more than the configured threshold (default 10%). The comparison
 * is mode-aware:</p>
 * <ul>
 *   <li><b>Throughput</b>: regression if current &lt; baseline * (1 - threshold)</li>
 *   <li><b>AverageTime / SampleTime</b>: regression if current &gt; baseline * (1 + threshold)</li>
 * </ul>
 *
 * <p>Validates Requirements 24.3, 24.6</p>
 */
public class BaselineRegressionDetector {

    /** Default regression threshold: 10%. */
    public static final double DEFAULT_THRESHOLD = 0.10;

    private final double threshold;
    private final ObjectMapper mapper;

    public BaselineRegressionDetector() {
        this(DEFAULT_THRESHOLD);
    }

    public BaselineRegressionDetector(double threshold) {
        this.threshold = threshold;
        this.mapper = new ObjectMapper();
    }

    /**
     * Compares current JMH results against a baseline file.
     *
     * @param baselinePath path to baseline JMH JSON file
     * @param currentPath  path to current JMH JSON file
     * @return regression report
     * @throws IOException if files cannot be read or parsed
     */
    public RegressionReport compare(Path baselinePath, Path currentPath) throws IOException {
        Map<String, BenchmarkEntry> baseline = parseBenchmarks(baselinePath);
        Map<String, BenchmarkEntry> current = parseBenchmarks(currentPath);

        List<Regression> regressions = new ArrayList<>();
        List<Improvement> improvements = new ArrayList<>();

        for (Map.Entry<String, BenchmarkEntry> entry : current.entrySet()) {
            String key = entry.getKey();
            BenchmarkEntry curr = entry.getValue();
            BenchmarkEntry base = baseline.get(key);

            if (base == null) continue; // New benchmark, no comparison possible

            double percentChange = computePercentChange(base.score, curr.score, curr.mode);

            if (isRegression(percentChange, curr.mode)) {
                regressions.add(new Regression(key, base.score, curr.score,
                        percentChange, curr.mode, curr.unit));
            } else if (isImprovement(percentChange, curr.mode)) {
                improvements.add(new Improvement(key, base.score, curr.score,
                        percentChange, curr.mode, curr.unit));
            }
        }

        return new RegressionReport(regressions, improvements, baseline.size(), current.size());
    }

    private Map<String, BenchmarkEntry> parseBenchmarks(Path path) throws IOException {
        Map<String, BenchmarkEntry> result = new HashMap<>();
        String json = Files.readString(path);
        JsonNode root = mapper.readTree(json);

        if (!root.isArray()) return result;

        for (JsonNode node : root) {
            String benchmark = node.path("benchmark").asText("");
            String mode = node.path("mode").asText("thrpt");
            double score = node.path("primaryMetric").path("score").asDouble(0);
            String unit = node.path("primaryMetric").path("scoreUnit").asText("");

            // Include params in key for parameterized benchmarks
            String params = "";
            JsonNode paramsNode = node.get("params");
            if (paramsNode != null && paramsNode.isObject()) {
                StringBuilder sb = new StringBuilder();
                for (var field : paramsNode.properties()) {
                    if (!sb.isEmpty()) sb.append(",");
                    sb.append(field.getKey()).append("=").append(field.getValue().asText());
                }
                params = "[" + sb + "]";
            }

            String key = benchmark + params + ":" + mode;
            result.put(key, new BenchmarkEntry(benchmark, mode, score, unit));
        }

        return result;
    }

    private double computePercentChange(double baseline, double current, String mode) {
        if (baseline == 0) return 0;
        // For throughput: higher is better, so positive change = improvement
        // For avg time: lower is better, so positive change = regression
        return ((current - baseline) / Math.abs(baseline)) * 100.0;
    }

    private boolean isRegression(double percentChange, String mode) {
        double thresholdPercent = threshold * 100.0;
        return switch (mode) {
            case "thrpt" -> percentChange < -thresholdPercent; // throughput dropped
            case "avgt", "sample", "ss" -> percentChange > thresholdPercent; // time increased
            default -> false;
        };
    }

    private boolean isImprovement(double percentChange, String mode) {
        double thresholdPercent = threshold * 100.0;
        return switch (mode) {
            case "thrpt" -> percentChange > thresholdPercent;
            case "avgt", "sample", "ss" -> percentChange < -thresholdPercent;
            default -> false;
        };
    }

    // ─────────────── Result Records ───────────────

    public record BenchmarkEntry(String benchmark, String mode, double score, String unit) {}

    public record Regression(
            String benchmark, double baselineScore, double currentScore,
            double percentChange, String mode, String unit
    ) {}

    public record Improvement(
            String benchmark, double baselineScore, double currentScore,
            double percentChange, String mode, String unit
    ) {}

    public record RegressionReport(
            List<Regression> regressions,
            List<Improvement> improvements,
            int baselineBenchmarkCount,
            int currentBenchmarkCount
    ) {
        public boolean hasRegressions() {
            return !regressions.isEmpty();
        }
    }
}
