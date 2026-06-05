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

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.bench.cognitive.DatasetLoader.LoadedDataset;
import com.spectrayan.spector.bench.cognitive.model.BenchmarkExitCode;
import com.spectrayan.spector.bench.cognitive.model.BenchmarkQuery;
import com.spectrayan.spector.bench.cognitive.model.ScoredResult;
import com.spectrayan.spector.embed.EmbeddingProvider;
import com.spectrayan.spector.embed.ollama.OllamaEmbeddingProvider;
import com.spectrayan.spector.memory.CognitiveResult;
import com.spectrayan.spector.memory.MemoryType;
import com.spectrayan.spector.memory.ScoreBreakdown;
import com.spectrayan.spector.memory.SpectorMemory;
import com.spectrayan.spector.memory.cortex.TierRouter;
import com.spectrayan.spector.memory.cortex.TierStore;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout;

/**
 * Main entry point for the cognitive memory benchmark.
 *
 * <p>Orchestrates the end-to-end benchmark run: loads the dataset, creates a memory
 * instance, runs queries against both baseline (vector-only) and cognitive (full pipeline)
 * retrievers, computes IR metrics, performs statistical tests, and produces reports.</p>
 *
 * <h3>CLI Arguments</h3>
 * <ul>
 *   <li>args[0]: dataset directory path (required)</li>
 *   <li>args[1]: output directory path (required)</li>
 *   <li>args[2]: regression threshold (optional, double)</li>
 *   <li>args[3]: topK (optional, int, default 10)</li>
 * </ul>
 *
 * <h3>Exit Codes</h3>
 * <ul>
 *   <li>0: SUCCESS — all criteria passed</li>
 *   <li>1: EFFECT_SIZE_INSUFFICIENT — Cohen's d &lt; 0.5</li>
 *   <li>2: NDCG_REGRESSION — cognitive nDCG below regression threshold</li>
 *   <li>3: DATASET_VALIDATION_FAILED — dataset loading failed</li>
 *   <li>4: SETUP_FAILED — memory instance creation failed</li>
 *   <li>5: PARTIAL_EXECUTION — queries were skipped due to timeout</li>
 * </ul>
 */
public final class CognitiveBenchmarkHarness {

    private static final Logger log = LoggerFactory.getLogger(CognitiveBenchmarkHarness.class);

    /** Threshold for classifying a delta as a "win" (cognitive beats baseline). */
    static final double WIN_THRESHOLD = 0.001;

    /** Maximum time allowed per query before it is skipped (seconds). */
    private static final long QUERY_TIMEOUT_SECONDS = 30;

    private final Path datasetDir;
    private final Path outputDir;
    private final Double regressionThreshold;
    private final int topK;

    /**
     * Creates a new benchmark harness with the specified configuration.
     *
     * @param datasetDir          path to the dataset directory
     * @param outputDir           path to the output directory for reports
     * @param regressionThreshold optional nDCG regression threshold (null = no check)
     * @param topK                number of results to retrieve and evaluate
     */
    public CognitiveBenchmarkHarness(Path datasetDir, Path outputDir,
                                     Double regressionThreshold, int topK) {
        this.datasetDir = datasetDir;
        this.outputDir = outputDir;
        this.regressionThreshold = regressionThreshold;
        this.topK = topK;
    }

    /**
     * Main entry point. Parses CLI arguments and runs the benchmark.
     *
     * @param args CLI arguments: datasetDir, outputDir, [regressionThreshold], [topK]
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: CognitiveBenchmarkHarness <datasetDir> <outputDir> [regressionThreshold] [topK]");
            System.exit(BenchmarkExitCode.SETUP_FAILED.code());
            return;
        }

        Path datasetDir = Path.of(args[0]);
        Path outputDir = Path.of(args[1]);

        Double regressionThreshold = null;
        if (args.length >= 3 && !args[2].isBlank()) {
            regressionThreshold = Double.parseDouble(args[2]);
        }

        int topK = 10;
        if (args.length >= 4 && !args[3].isBlank()) {
            topK = Integer.parseInt(args[3]);
        }

        CognitiveBenchmarkHarness harness = new CognitiveBenchmarkHarness(
                datasetDir, outputDir, regressionThreshold, topK);

        BenchmarkExitCode exitCode = harness.run();
        System.exit(exitCode.code());
    }

    /**
     * Executes the full benchmark run and returns the appropriate exit code.
     *
     * @return the exit code indicating benchmark outcome
     */
    public BenchmarkExitCode run() {
        // ── Step 1: Load dataset ──
        LoadedDataset dataset;
        try {
            DatasetLoader loader = new DatasetLoader();
            dataset = loader.load(datasetDir);
            log.info("Dataset loaded: {} corpus records, {} queries",
                    dataset.corpus().size(), dataset.queries().size());
        } catch (DatasetValidationException e) {
            log.error("Dataset validation failed: {}", e.getMessage());
            return BenchmarkExitCode.DATASET_VALIDATION_FAILED;
        } catch (DatasetParseException e) {
            log.error("Dataset parse failed: {}", e.getMessage());
            return BenchmarkExitCode.DATASET_VALIDATION_FAILED;
        }

        // ── Step 2: Create memory instance ──
        try (BenchmarkSetup setup = new BenchmarkSetup();
             EmbeddingProvider embedder = createEmbeddingProvider()) {
            SpectorMemory memory = setup.createMemoryInstance(dataset, embedder);
            log.info("Memory instance created with {} total memories", memory.totalMemories());

            // ── Step 3: Create retrievers ──
            BaselineRetriever baselineRetriever = createBaselineRetriever(memory);
            CognitiveRetriever cognitiveRetriever = new CognitiveRetriever(memory);

            // ── Step 4: Execute benchmark loop ──
            return executeBenchmark(dataset, baselineRetriever, cognitiveRetriever, embedder,
                    memory, setup.idToSlot());
        } catch (Exception e) {
            log.error("Setup failed: {}", e.getMessage(), e);
            return BenchmarkExitCode.SETUP_FAILED;
        }
    }

    /**
     * Executes the benchmark loop: runs all queries, computes metrics, writes reports.
     */
    BenchmarkExitCode executeBenchmark(LoadedDataset dataset,
                                       BaselineRetriever baselineRetriever,
                                       CognitiveRetriever cognitiveRetriever,
                                       EmbeddingProvider embedder,
                                       SpectorMemory memory,
                                       Map<String, Integer> idToSlot) {
        List<BenchmarkQuery> queries = dataset.queries();
        Map<String, Map<String, Integer>> qrels = dataset.qrels();
        MetricsComputer metricsComputer = new MetricsComputer();

        // Per-query storage
        List<QueryMetrics> perQueryMetrics = new ArrayList<>(queries.size());
        List<ReportWriter.QueryResult> queryResults = new ArrayList<>(queries.size());
        int skippedCount = 0;
        List<String> skippedQueryIds = new ArrayList<>();

        // Subsystem contribution tracking (Task 11.3/11.4)
        Map<String, Set<ContributingSubsystem>> perQueryContributions = new LinkedHashMap<>();

        // Get graph references for subsystem detection
        var hebbianGraph = memory.hebbianGraph();
        var temporalChain = memory.temporalChain();
        var entityGraph = memory.entityGraph();

        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            for (BenchmarkQuery query : queries) {
                long startNanos = System.nanoTime();

                // ── Execute both retrievers with timeout ──
                List<ScoredResult> baselineResults;
                List<ScoredResult> cognitiveResults;
                List<CognitiveResult> cognitiveRawResults;

                try {
                    float[] queryVector = embedder.embed(query.text()).vector();

                    Future<List<ScoredResult>> baselineFuture = executor.submit(
                            () -> baselineRetriever.retrieve(queryVector, topK));
                    Future<List<CognitiveResult>> cognitiveFuture = executor.submit(
                            () -> cognitiveRetriever.retrieveWithBreakdown(query.text(), query));

                    baselineResults = baselineFuture.get(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    cognitiveRawResults = cognitiveFuture.get(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                    cognitiveResults = cognitiveRawResults.stream()
                            .map(r -> new ScoredResult(r.id(), r.score()))
                            .toList();
                } catch (TimeoutException e) {
                    log.warn("Query '{}' exceeded {}s timeout, skipping", query.id(), QUERY_TIMEOUT_SECONDS);
                    skippedCount++;
                    skippedQueryIds.add(query.id());
                    continue;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("Benchmark interrupted at query '{}'", query.id());
                    break;
                } catch (ExecutionException e) {
                    log.warn("Query '{}' failed with exception, skipping: {}",
                            query.id(), e.getCause().getMessage());
                    skippedCount++;
                    skippedQueryIds.add(query.id());
                    continue;
                }

                long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

                // ── Extract ranked IDs ──
                List<String> baselineRankedIds = baselineResults.stream()
                        .map(ScoredResult::memoryId)
                        .toList();
                List<String> cognitiveRankedIds = cognitiveResults.stream()
                        .map(ScoredResult::memoryId)
                        .toList();

                // ── Compute per-query metrics ──
                Map<String, Integer> queryQrels = qrels.getOrDefault(query.id(), Map.of());

                double baselineNdcg = metricsComputer.ndcgAtK(baselineRankedIds, queryQrels, topK);
                double baselineMrr = metricsComputer.mrrAtK(baselineRankedIds, queryQrels, topK);
                double baselineRecall = metricsComputer.recallAtK(baselineRankedIds, queryQrels, topK);

                double cognitiveNdcg = metricsComputer.ndcgAtK(cognitiveRankedIds, queryQrels, topK);
                double cognitiveMrr = metricsComputer.mrrAtK(cognitiveRankedIds, queryQrels, topK);
                double cognitiveRecall = metricsComputer.recallAtK(cognitiveRankedIds, queryQrels, topK);

                double delta = cognitiveNdcg - baselineNdcg;

                // ── Subsystem contribution detection (Task 11.4) ──
                Set<String> baselineTop10Set = new java.util.HashSet<>(baselineRankedIds);
                EnumSet<ContributingSubsystem> queryContributions =
                        EnumSet.noneOf(ContributingSubsystem.class);

                // Build a lookup from memory ID to CognitiveResult for breakdowns
                Map<String, CognitiveResult> cognitiveResultMap = new LinkedHashMap<>();
                for (CognitiveResult cr : cognitiveRawResults) {
                    cognitiveResultMap.put(cr.id(), cr);
                }

                for (String cogId : cognitiveRankedIds) {
                    // Only analyze results in cognitive top-10 but NOT in baseline top-10
                    if (!baselineTop10Set.contains(cogId)) {
                        int relevance = queryQrels.getOrDefault(cogId, 0);
                        if (relevance >= 2) {
                            // This is a relevant result that the cognitive pipeline found
                            // but the baseline missed — detect which subsystem contributed
                            CognitiveResult cr = cognitiveResultMap.get(cogId);
                            ScoreBreakdown breakdown = (cr != null) ? cr.breakdown() : null;

                            Set<ContributingSubsystem> resultContributions =
                                    ContributingSubsystem.detect(
                                            cogId, baselineTop10Set,
                                            hebbianGraph, temporalChain, entityGraph,
                                            breakdown, idToSlot);
                            queryContributions.addAll(resultContributions);
                        }
                    }
                }

                perQueryContributions.put(query.id(), queryContributions);

                // Format contributing subsystems for CSV output
                String contributingSubsystemsStr = queryContributions.isEmpty()
                        ? ""
                        : queryContributions.stream()
                                .map(ContributingSubsystem::name)
                                .reduce((a, b) -> a + "|" + b)
                                .orElse("");

                // Store per-query metrics
                perQueryMetrics.add(new QueryMetrics(
                        baselineNdcg, baselineMrr, baselineRecall,
                        cognitiveNdcg, cognitiveMrr, cognitiveRecall,
                        elapsedMs));

                queryResults.add(new ReportWriter.QueryResult(
                        query.id(), baselineNdcg, cognitiveNdcg, delta,
                        contributingSubsystemsStr,
                        query.cognitiveProfile().name(),
                        elapsedMs));
            }
        } finally {
            executor.shutdownNow();
        }

        // ── Report skipped queries ──
        if (skippedCount > 0) {
            log.warn("Skipped {} queries due to timeout: {}", skippedCount, skippedQueryIds);
        }

        int executedCount = queryResults.size();
        if (executedCount == 0) {
            log.error("No queries were successfully executed");
            return BenchmarkExitCode.PARTIAL_EXECUTION;
        }

        // ── Step 5: Compute win/tie/loss ──
        WinTieLossResult wtl = computeWinTieLoss(queryResults);

        // ── Step 6: Compute aggregate metrics ──
        double meanBaselineNdcg = perQueryMetrics.stream()
                .mapToDouble(QueryMetrics::baselineNdcg).average().orElse(0.0);
        double meanBaselineMrr = perQueryMetrics.stream()
                .mapToDouble(QueryMetrics::baselineMrr).average().orElse(0.0);
        double meanBaselineRecall = perQueryMetrics.stream()
                .mapToDouble(QueryMetrics::baselineRecall).average().orElse(0.0);

        double meanCognitiveNdcg = perQueryMetrics.stream()
                .mapToDouble(QueryMetrics::cognitiveNdcg).average().orElse(0.0);
        double meanCognitiveMrr = perQueryMetrics.stream()
                .mapToDouble(QueryMetrics::cognitiveMrr).average().orElse(0.0);
        double meanCognitiveRecall = perQueryMetrics.stream()
                .mapToDouble(QueryMetrics::cognitiveRecall).average().orElse(0.0);

        double avgLatencyMs = perQueryMetrics.stream()
                .mapToLong(QueryMetrics::latencyMs).average().orElse(0.0);

        // ── Step 7: Compute per-profile nDCG ──
        Map<String, Double> perProfileNdcg = computePerProfileNdcg(queryResults);

        // ── Step 8: Statistical tests (Cohen's d and p-value on nDCG arrays) ──
        double[] baselineArray = perQueryMetrics.stream()
                .mapToDouble(QueryMetrics::baselineNdcg).toArray();
        double[] cognitiveArray = perQueryMetrics.stream()
                .mapToDouble(QueryMetrics::cognitiveNdcg).toArray();

        double cohensD = StatisticalTests.cohensD(baselineArray, cognitiveArray);
        double pValue = StatisticalTests.pairedTTestPValue(baselineArray, cognitiveArray);

        log.info("Cohen's d = {}, p-value = {}", cohensD, pValue);

        // ── Step 9: Write reports ──
        ReportWriter writer = new ReportWriter();
        ReportWriter.BenchmarkReport report = new ReportWriter.BenchmarkReport(
                Instant.now(),
                dataset.corpus().size(),
                executedCount,
                new ReportWriter.RetrieverMetrics(
                        meanBaselineNdcg, meanBaselineMrr, meanBaselineRecall, avgLatencyMs),
                new ReportWriter.RetrieverMetrics(
                        meanCognitiveNdcg, meanCognitiveMrr, meanCognitiveRecall, avgLatencyMs),
                ContributingSubsystem.computeContributionPercentages(
                        perQueryContributions, executedCount),
                perProfileNdcg,
                new ReportWriter.WinTieLoss(wtl.wins(), wtl.ties(), wtl.losses()),
                cohensD,
                pValue,
                avgLatencyMs);

        try {
            writer.writeSummary(outputDir, report);
            writer.writeDetail(outputDir, queryResults);
            writer.logSummary(report);
        } catch (Exception e) {
            log.error("Failed to write reports: {}", e.getMessage(), e);
        }

        // ── Step 10: Determine exit code ──
        if (skippedCount > 0) {
            return BenchmarkExitCode.PARTIAL_EXECUTION;
        }
        if (cohensD < 0.5) {
            log.warn("FAIL: Cohen's d ({}) < 0.5 (medium effect threshold)", cohensD);
            return BenchmarkExitCode.EFFECT_SIZE_INSUFFICIENT;
        }
        if (regressionThreshold != null && meanCognitiveNdcg < regressionThreshold) {
            log.warn("FAIL: Cognitive nDCG@{} ({}) < regression threshold ({})",
                    topK, meanCognitiveNdcg, regressionThreshold);
            return BenchmarkExitCode.NDCG_REGRESSION;
        }

        log.info("PASS: Cohen's d = {}, cognitive nDCG@{} = {}", cohensD, topK, meanCognitiveNdcg);
        return BenchmarkExitCode.SUCCESS;
    }

    // ══════════════════════════════════════════════════════════════
    // Win/Tie/Loss Classification
    // ══════════════════════════════════════════════════════════════

    /**
     * Result of win/tie/loss classification.
     */
    record WinTieLossResult(int wins, int ties, int losses) {}

    /**
     * Classifies each query result as win, tie, or loss based on the nDCG delta.
     *
     * <ul>
     *   <li>Win: delta &gt; 0.001 (cognitive beats baseline)</li>
     *   <li>Tie: |delta| ≤ 0.001 (effectively identical)</li>
     *   <li>Loss: delta &lt; -0.001 (baseline beats cognitive)</li>
     * </ul>
     *
     * <p>Verifies partition completeness: wins + ties + losses == total count.</p>
     *
     * @param results the per-query results
     * @return the classified counts
     */
    static WinTieLossResult computeWinTieLoss(List<ReportWriter.QueryResult> results) {
        int wins = 0;
        int ties = 0;
        int losses = 0;

        for (ReportWriter.QueryResult result : results) {
            double delta = result.delta();
            if (delta > WIN_THRESHOLD) {
                wins++;
            } else if (delta < -WIN_THRESHOLD) {
                losses++;
            } else {
                ties++;
            }
        }

        // Verify partition completeness
        int total = results.size();
        if (wins + ties + losses != total) {
            throw new IllegalStateException(
                    "Win/tie/loss partition violation: %d + %d + %d != %d"
                            .formatted(wins, ties, losses, total));
        }

        return new WinTieLossResult(wins, ties, losses);
    }

    /**
     * Classifies a single delta value as win, tie, or loss.
     *
     * @param delta the nDCG difference (cognitive - baseline)
     * @return 1 for win, 0 for tie, -1 for loss
     */
    static int classifyDelta(double delta) {
        if (delta > WIN_THRESHOLD) {
            return 1;  // win
        } else if (delta < -WIN_THRESHOLD) {
            return -1; // loss
        } else {
            return 0;  // tie
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Per-Profile nDCG Computation
    // ══════════════════════════════════════════════════════════════

    /**
     * Groups query results by cognitive profile and computes the mean nDCG@10
     * for each profile group.
     *
     * @param results per-query results containing profile and cognitiveNdcg
     * @return map of profile name to mean nDCG
     */
    static Map<String, Double> computePerProfileNdcg(List<ReportWriter.QueryResult> results) {
        Map<String, List<Double>> profileGroups = new LinkedHashMap<>();

        for (ReportWriter.QueryResult result : results) {
            profileGroups
                    .computeIfAbsent(result.profile(), k -> new ArrayList<>())
                    .add(result.cognitiveNdcg());
        }

        Map<String, Double> perProfileMean = new LinkedHashMap<>();
        for (Map.Entry<String, List<Double>> entry : profileGroups.entrySet()) {
            double mean = entry.getValue().stream()
                    .mapToDouble(Double::doubleValue)
                    .average()
                    .orElse(0.0);
            perProfileMean.put(entry.getKey(), mean);
        }

        return perProfileMean;
    }

    // ══════════════════════════════════════════════════════════════
    // Internal Helpers
    // ══════════════════════════════════════════════════════════════

    /**
     * Per-query metrics record for aggregation.
     */
    private record QueryMetrics(
            double baselineNdcg, double baselineMrr, double baselineRecall,
            double cognitiveNdcg, double cognitiveMrr, double cognitiveRecall,
            long latencyMs
    ) {}

    /**
     * Creates the BaselineRetriever by accessing the memory's tier router
     * to get the primary segment, layout, and record count.
     *
     * <p>Uses the tier with the most records as the primary corpus segment
     * for baseline scoring.</p>
     */
    private BaselineRetriever createBaselineRetriever(SpectorMemory memory) {
        TierRouter tierRouter = memory.tierRouter();

        // Find the tier with the most records
        MemoryType primaryTier = MemoryType.EPISODIC;
        int maxCount = 0;
        for (MemoryType type : MemoryType.values()) {
            try {
                int count = tierRouter.countFor(type);
                if (count > maxCount) {
                    maxCount = count;
                    primaryTier = type;
                }
            } catch (Exception e) {
                // Tier might not be registered
            }
        }

        TierStore store = tierRouter.get(primaryTier);
        MemorySegment segment = store.primarySegment();
        CognitiveRecordLayout layout = store.layout();
        int recordCount = store.size();

        // Get calibration data from quantizer
        float[] mins = memory.quantizer().mins();
        float[] scales = memory.quantizer().scales();

        // Build memory ID array from memory index
        String[] memoryIds = new String[recordCount];
        var locationMap = memory.index().locationMap();
        for (var entry : locationMap.entrySet()) {
            var loc = entry.getValue();
            if (loc.type() == primaryTier) {
                int slot = (int) (loc.offset() / layout.stride());
                if (slot >= 0 && slot < recordCount) {
                    memoryIds[slot] = entry.getKey();
                }
            }
        }

        return new BaselineRetriever(segment, layout, recordCount, mins, scales, memoryIds);
    }

    /**
     * Creates an embedding provider for the benchmark.
     * Uses the default Ollama embedding provider.
     */
    private EmbeddingProvider createEmbeddingProvider() {
        return OllamaEmbeddingProvider.createDefault();
    }
}
