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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.spectrayan.spector.bench.cognitive.ReportWriter.BenchmarkReport;
import com.spectrayan.spector.bench.cognitive.ReportWriter.QueryResult;
import com.spectrayan.spector.bench.cognitive.ReportWriter.RetrieverMetrics;
import com.spectrayan.spector.bench.cognitive.ReportWriter.SubsystemContributions;
import com.spectrayan.spector.bench.cognitive.ReportWriter.WinTieLoss;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for {@link ReportWriter} verifying summary.json schema correctness,
 * CSV column format, and stdout summary format string.
 */
class ReportWriterTest {

    private final ReportWriter writer = new ReportWriter();
    private final ObjectMapper mapper = JsonMapper.builder().build();

    // ══════════════════════════════════════════════════════════════
    // summary.json tests
    // ══════════════════════════════════════════════════════════════

    @Test
    void writeSummary_producesValidJson(@TempDir Path outputDir) throws IOException {
        BenchmarkReport report = createSampleReport();

        writer.writeSummary(outputDir, report);

        Path summaryFile = outputDir.resolve("summary.json");
        assertTrue(Files.exists(summaryFile));

        String content = Files.readString(summaryFile);
        JsonNode root = mapper.readTree(content);
        assertNotNull(root);
    }

    @Test
    void writeSummary_containsAllRequiredTopLevelFields(@TempDir Path outputDir) throws IOException {
        BenchmarkReport report = createSampleReport();

        writer.writeSummary(outputDir, report);

        String content = Files.readString(outputDir.resolve("summary.json"));
        JsonNode root = mapper.readTree(content);

        // Verify all required top-level fields
        assertNotNull(root.get("timestamp"), "missing 'timestamp'");
        assertNotNull(root.get("corpus_size"), "missing 'corpus_size'");
        assertNotNull(root.get("query_count"), "missing 'query_count'");
        assertNotNull(root.get("baseline_metrics"), "missing 'baseline_metrics'");
        assertNotNull(root.get("cognitive_metrics"), "missing 'cognitive_metrics'");
        assertNotNull(root.get("subsystem_contributions"), "missing 'subsystem_contributions'");
        assertNotNull(root.get("per_profile_ndcg"), "missing 'per_profile_ndcg'");
        assertNotNull(root.get("win_tie_loss"), "missing 'win_tie_loss'");
        assertNotNull(root.get("cohens_d"), "missing 'cohens_d'");
        assertNotNull(root.get("p_value"), "missing 'p_value'");
    }

    @Test
    void writeSummary_baselineMetricsHasCorrectFields(@TempDir Path outputDir) throws IOException {
        BenchmarkReport report = createSampleReport();

        writer.writeSummary(outputDir, report);

        String content = Files.readString(outputDir.resolve("summary.json"));
        JsonNode baselineMetrics = mapper.readTree(content).get("baseline_metrics");

        assertNotNull(baselineMetrics.get("ndcg_at_10"), "missing 'ndcg_at_10'");
        assertNotNull(baselineMetrics.get("mrr_at_10"), "missing 'mrr_at_10'");
        assertNotNull(baselineMetrics.get("recall_at_10"), "missing 'recall_at_10'");
        assertNotNull(baselineMetrics.get("avg_latency_ms"), "missing 'avg_latency_ms'");

        assertEquals(0.412, baselineMetrics.get("ndcg_at_10").asDouble(), 1e-6);
        assertEquals(0.385, baselineMetrics.get("mrr_at_10").asDouble(), 1e-6);
        assertEquals(0.340, baselineMetrics.get("recall_at_10").asDouble(), 1e-6);
        assertEquals(2.1, baselineMetrics.get("avg_latency_ms").asDouble(), 1e-6);
    }

    @Test
    void writeSummary_cognitiveMetricsHasCorrectFields(@TempDir Path outputDir) throws IOException {
        BenchmarkReport report = createSampleReport();

        writer.writeSummary(outputDir, report);

        String content = Files.readString(outputDir.resolve("summary.json"));
        JsonNode cognitiveMetrics = mapper.readTree(content).get("cognitive_metrics");

        assertNotNull(cognitiveMetrics.get("ndcg_at_10"), "missing 'ndcg_at_10'");
        assertNotNull(cognitiveMetrics.get("mrr_at_10"), "missing 'mrr_at_10'");
        assertNotNull(cognitiveMetrics.get("recall_at_10"), "missing 'recall_at_10'");
        assertNotNull(cognitiveMetrics.get("avg_latency_ms"), "missing 'avg_latency_ms'");

        assertEquals(0.687, cognitiveMetrics.get("ndcg_at_10").asDouble(), 1e-6);
    }

    @Test
    void writeSummary_subsystemContributionsHasCorrectFields(@TempDir Path outputDir) throws IOException {
        BenchmarkReport report = createSampleReport();

        writer.writeSummary(outputDir, report);

        String content = Files.readString(outputDir.resolve("summary.json"));
        JsonNode subsystems = mapper.readTree(content).get("subsystem_contributions");

        assertNotNull(subsystems.get("hebbian_pct"), "missing 'hebbian_pct'");
        assertNotNull(subsystems.get("temporal_pct"), "missing 'temporal_pct'");
        assertNotNull(subsystems.get("entity_pct"), "missing 'entity_pct'");
        assertNotNull(subsystems.get("importance_pct"), "missing 'importance_pct'");
        assertNotNull(subsystems.get("valence_pct"), "missing 'valence_pct'");
        assertNotNull(subsystems.get("tag_gating_pct"), "missing 'tag_gating_pct'");

        assertEquals(12.5, subsystems.get("hebbian_pct").asDouble(), 1e-6);
        assertEquals(31.0, subsystems.get("tag_gating_pct").asDouble(), 1e-6);
    }

    @Test
    void writeSummary_winTieLossHasCorrectFields(@TempDir Path outputDir) throws IOException {
        BenchmarkReport report = createSampleReport();

        writer.writeSummary(outputDir, report);

        String content = Files.readString(outputDir.resolve("summary.json"));
        JsonNode wtl = mapper.readTree(content).get("win_tie_loss");

        assertEquals(165, wtl.get("wins").asInt());
        assertEquals(12, wtl.get("ties").asInt());
        assertEquals(23, wtl.get("losses").asInt());
    }

    @Test
    void writeSummary_perProfileNdcgPresent(@TempDir Path outputDir) throws IOException {
        BenchmarkReport report = createSampleReport();

        writer.writeSummary(outputDir, report);

        String content = Files.readString(outputDir.resolve("summary.json"));
        JsonNode perProfile = mapper.readTree(content).get("per_profile_ndcg");

        assertEquals(0.72, perProfile.get("BALANCED").asDouble(), 1e-6);
        assertEquals(0.81, perProfile.get("DEBUGGING").asDouble(), 1e-6);
    }

    @Test
    void writeSummary_scalarFieldsCorrect(@TempDir Path outputDir) throws IOException {
        BenchmarkReport report = createSampleReport();

        writer.writeSummary(outputDir, report);

        String content = Files.readString(outputDir.resolve("summary.json"));
        JsonNode root = mapper.readTree(content);

        assertEquals(2150, root.get("corpus_size").asInt());
        assertEquals(200, root.get("query_count").asInt());
        assertEquals(0.82, root.get("cohens_d").asDouble(), 1e-6);
        assertEquals(0.00001, root.get("p_value").asDouble(), 1e-9);
    }

    // ══════════════════════════════════════════════════════════════
    // detail.csv tests
    // ══════════════════════════════════════════════════════════════

    @Test
    void writeDetail_producesFileWithHeader(@TempDir Path outputDir) throws IOException {
        List<QueryResult> results = createSampleResults();

        writer.writeDetail(outputDir, results);

        Path detailFile = outputDir.resolve("detail.csv");
        assertTrue(Files.exists(detailFile));

        List<String> lines = Files.readAllLines(detailFile);
        assertEquals("query_id,baseline_nDCG,cognitive_nDCG,delta,contributing_subsystems,profile,latency_ms",
                lines.get(0));
    }

    @Test
    void writeDetail_hasCorrectColumnCount(@TempDir Path outputDir) throws IOException {
        List<QueryResult> results = createSampleResults();

        writer.writeDetail(outputDir, results);

        List<String> lines = Files.readAllLines(outputDir.resolve("detail.csv"));
        // Header + data rows
        assertEquals(1 + results.size(), lines.size());

        // Each data row should have 7 columns
        for (int i = 1; i < lines.size(); i++) {
            String[] columns = lines.get(i).split(",");
            assertEquals(7, columns.length, "Row " + i + " should have 7 columns");
        }
    }

    @Test
    void writeDetail_dataRowFormatCorrect(@TempDir Path outputDir) throws IOException {
        List<QueryResult> results = List.of(
                new QueryResult("q-001", 0.412, 0.856, 0.444, "TAG_GATING;IMPORTANCE_DECAY", "DEBUGGING", 3)
        );

        writer.writeDetail(outputDir, results);

        List<String> lines = Files.readAllLines(outputDir.resolve("detail.csv"));
        assertEquals(2, lines.size());

        String dataRow = lines.get(1);
        assertEquals("q-001,0.412,0.856,0.444,TAG_GATING;IMPORTANCE_DECAY,DEBUGGING,3", dataRow);
    }

    @Test
    void writeDetail_emptyResults_producesHeaderOnly(@TempDir Path outputDir) throws IOException {
        writer.writeDetail(outputDir, List.of());

        List<String> lines = Files.readAllLines(outputDir.resolve("detail.csv"));
        assertEquals(1, lines.size());
        assertEquals("query_id,baseline_nDCG,cognitive_nDCG,delta,contributing_subsystems,profile,latency_ms",
                lines.get(0));
    }

    // ══════════════════════════════════════════════════════════════
    // stdout summary tests
    // ══════════════════════════════════════════════════════════════

    @Test
    void logSummary_matchesExpectedFormat() {
        BenchmarkReport report = createSampleReport();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(baos));
            writer.logSummary(report);
        } finally {
            System.setOut(original);
        }

        String output = baos.toString().trim();
        assertEquals("Cognitive nDCG@10=0.687 vs Baseline nDCG@10=0.412 (\u0394=0.275, p=0.00001)", output);
    }

    @Test
    void logSummary_computesDeltaFromMetrics() {
        // Using different values to verify delta computation
        BenchmarkReport report = new BenchmarkReport(
                Instant.parse("2025-07-15T14:30:00Z"),
                1000,
                100,
                new RetrieverMetrics(0.500, 0.400, 0.350, 2.0),
                new RetrieverMetrics(0.800, 0.700, 0.650, 5.0),
                new SubsystemContributions(10.0, 8.0, 9.0, 20.0, 15.0, 30.0),
                Map.of("BALANCED", 0.75),
                new WinTieLoss(80, 5, 15),
                0.90,
                0.00010,
                4.5
        );

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(baos));
            writer.logSummary(report);
        } finally {
            System.setOut(original);
        }

        String output = baos.toString().trim();
        assertEquals("Cognitive nDCG@10=0.800 vs Baseline nDCG@10=0.500 (\u0394=0.300, p=0.00010)", output);
    }

    // ══════════════════════════════════════════════════════════════
    // Test helpers
    // ══════════════════════════════════════════════════════════════

    private BenchmarkReport createSampleReport() {
        return new BenchmarkReport(
                Instant.parse("2025-07-15T14:30:00Z"),
                2150,
                200,
                new RetrieverMetrics(0.412, 0.385, 0.340, 2.1),
                new RetrieverMetrics(0.687, 0.621, 0.580, 4.8),
                new SubsystemContributions(12.5, 8.3, 9.1, 22.4, 15.7, 31.0),
                Map.of("BALANCED", 0.72, "DEBUGGING", 0.81),
                new WinTieLoss(165, 12, 23),
                0.82,
                0.00001,
                4.8
        );
    }

    private List<QueryResult> createSampleResults() {
        return List.of(
                new QueryResult("q-001", 0.412, 0.856, 0.444, "TAG_GATING;IMPORTANCE_DECAY", "DEBUGGING", 3),
                new QueryResult("q-002", 0.300, 0.650, 0.350, "HEBBIAN_GRAPH", "BALANCED", 5),
                new QueryResult("q-003", 0.500, 0.520, 0.020, "TEMPORAL_CHAIN", "RECALLING", 4)
        );
    }
}
