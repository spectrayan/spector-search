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

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes benchmark results as JSON summary and CSV detail files.
 *
 * <p>Produces:
 * <ul>
 *   <li>{@code summary.json} — aggregate metrics with snake_case field naming (Req 16.1)</li>
 *   <li>{@code detail.csv} — per-query results with columns defined by Req 16.2</li>
 *   <li>Stdout one-liner summary (Req 16.3)</li>
 * </ul>
 */
public final class ReportWriter {

    private static final Logger log = LoggerFactory.getLogger(ReportWriter.class);

    private static final String SUMMARY_FILENAME = "summary.json";
    private static final String DETAIL_FILENAME = "detail.csv";
    private static final String CSV_HEADER =
            "query_id,baseline_nDCG,cognitive_nDCG,delta,contributing_subsystems,profile,latency_ms";

    private final ObjectMapper mapper;

    public ReportWriter() {
        this.mapper = JsonMapper.builder()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .build();
    }

    // ══════════════════════════════════════════════════════════════
    // Inner records
    // ══════════════════════════════════════════════════════════════

    /**
     * Top-level benchmark report containing all aggregate metrics.
     */
    public record BenchmarkReport(
            Instant timestamp,
            int corpusSize,
            int queryCount,
            RetrieverMetrics baselineMetrics,
            RetrieverMetrics cognitiveMetrics,
            SubsystemContributions subsystemContributions,
            Map<String, Double> perProfileNdcg,
            WinTieLoss winTieLoss,
            double cohensD,
            double pValue,
            double avgLatencyMs
    ) {}

    /**
     * Per-retriever aggregate metrics.
     */
    public record RetrieverMetrics(double ndcg10, double mrr10, double recall10, double avgLatencyMs) {}

    /**
     * Contribution percentages for each cognitive subsystem.
     */
    public record SubsystemContributions(double hebbian, double temporal, double entity,
                                          double importance, double valence, double tagGating) {}

    /**
     * Win/tie/loss counts comparing cognitive vs baseline per-query nDCG.
     */
    public record WinTieLoss(int wins, int ties, int losses) {}

    /**
     * Per-query detail record for the CSV output.
     */
    public record QueryResult(String queryId, double baselineNdcg, double cognitiveNdcg,
                               double delta, String contributingSubsystems, String profile,
                               long latencyMs) {}

    // ══════════════════════════════════════════════════════════════
    // Public API
    // ══════════════════════════════════════════════════════════════

    /**
     * Writes the summary JSON report to the specified output directory.
     *
     * <p>The JSON uses snake_case field names matching the schema defined in Req 16.1.
     * Creates the output directory if it does not exist.</p>
     *
     * @param outputDir directory to write {@code summary.json} into
     * @param report    the benchmark report data
     * @throws IOException if writing fails
     */
    public void writeSummary(Path outputDir, BenchmarkReport report) throws IOException {
        Files.createDirectories(outputDir);
        Path summaryFile = outputDir.resolve(SUMMARY_FILENAME);

        Map<String, Object> json = buildSummaryMap(report);
        String content = mapper.writeValueAsString(json);

        Files.writeString(summaryFile, content);
        log.info("Summary report written to {}", summaryFile);
    }

    /**
     * Writes the per-query detail CSV to the specified output directory.
     *
     * <p>Columns: query_id, baseline_nDCG, cognitive_nDCG, delta, contributing_subsystems,
     * profile, latency_ms (Req 16.2).</p>
     *
     * @param outputDir directory to write {@code detail.csv} into
     * @param results   per-query result records
     * @throws IOException if writing fails
     */
    public void writeDetail(Path outputDir, List<QueryResult> results) throws IOException {
        Files.createDirectories(outputDir);
        Path detailFile = outputDir.resolve(DETAIL_FILENAME);

        try (BufferedWriter writer = Files.newBufferedWriter(detailFile)) {
            writer.write(CSV_HEADER);
            writer.newLine();

            for (QueryResult result : results) {
                writer.write(formatCsvRow(result));
                writer.newLine();
            }
        }
        log.info("Detail report written to {} ({} rows)", detailFile, results.size());
    }

    /**
     * Logs the one-line summary to stdout per Req 16.3.
     *
     * <p>Format: "Cognitive nDCG@10={X} vs Baseline nDCG@10={Y} (Δ={Z}, p={P})"</p>
     *
     * @param report the benchmark report containing the metrics to display
     */
    public void logSummary(BenchmarkReport report) {
        double cognitiveNdcg = report.cognitiveMetrics().ndcg10();
        double baselineNdcg = report.baselineMetrics().ndcg10();
        double delta = cognitiveNdcg - baselineNdcg;
        double pValue = report.pValue();

        String summary = String.format(Locale.US,
                "Cognitive nDCG@10=%.3f vs Baseline nDCG@10=%.3f (\u0394=%.3f, p=%.5f)",
                cognitiveNdcg, baselineNdcg, delta, pValue);

        System.out.println(summary);
    }

    // ══════════════════════════════════════════════════════════════
    // Internal helpers
    // ══════════════════════════════════════════════════════════════

    private Map<String, Object> buildSummaryMap(BenchmarkReport report) {
        Map<String, Object> map = new LinkedHashMap<>();

        map.put("timestamp", report.timestamp().toString());
        map.put("corpus_size", report.corpusSize());
        map.put("query_count", report.queryCount());

        // Baseline metrics
        Map<String, Object> baseline = new LinkedHashMap<>();
        baseline.put("ndcg_at_10", report.baselineMetrics().ndcg10());
        baseline.put("mrr_at_10", report.baselineMetrics().mrr10());
        baseline.put("recall_at_10", report.baselineMetrics().recall10());
        baseline.put("avg_latency_ms", report.baselineMetrics().avgLatencyMs());
        map.put("baseline_metrics", baseline);

        // Cognitive metrics
        Map<String, Object> cognitive = new LinkedHashMap<>();
        cognitive.put("ndcg_at_10", report.cognitiveMetrics().ndcg10());
        cognitive.put("mrr_at_10", report.cognitiveMetrics().mrr10());
        cognitive.put("recall_at_10", report.cognitiveMetrics().recall10());
        cognitive.put("avg_latency_ms", report.cognitiveMetrics().avgLatencyMs());
        map.put("cognitive_metrics", cognitive);

        // Subsystem contributions
        Map<String, Object> subsystems = new LinkedHashMap<>();
        subsystems.put("hebbian_pct", report.subsystemContributions().hebbian());
        subsystems.put("temporal_pct", report.subsystemContributions().temporal());
        subsystems.put("entity_pct", report.subsystemContributions().entity());
        subsystems.put("importance_pct", report.subsystemContributions().importance());
        subsystems.put("valence_pct", report.subsystemContributions().valence());
        subsystems.put("tag_gating_pct", report.subsystemContributions().tagGating());
        map.put("subsystem_contributions", subsystems);

        // Per-profile nDCG
        map.put("per_profile_ndcg", report.perProfileNdcg());

        // Win/tie/loss
        Map<String, Object> wtl = new LinkedHashMap<>();
        wtl.put("wins", report.winTieLoss().wins());
        wtl.put("ties", report.winTieLoss().ties());
        wtl.put("losses", report.winTieLoss().losses());
        map.put("win_tie_loss", wtl);

        // Effect size and significance
        map.put("cohens_d", report.cohensD());
        map.put("p_value", report.pValue());

        return map;
    }

    private String formatCsvRow(QueryResult result) {
        return String.format(Locale.US, "%s,%.3f,%.3f,%.3f,%s,%s,%d",
                result.queryId(),
                result.baselineNdcg(),
                result.cognitiveNdcg(),
                result.delta(),
                result.contributingSubsystems(),
                result.profile(),
                result.latencyMs());
    }
}
