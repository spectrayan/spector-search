package com.spectrayan.spector.bench;

import com.spectrayan.spector.core.CosineSimilarity;
import com.spectrayan.spector.core.DotProduct;
import com.spectrayan.spector.core.SimdCapability;
import com.spectrayan.spector.core.SimilarityFunction;
import com.spectrayan.spector.engine.SpectorConfig;
import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.index.HnswParams;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Standalone heavy performance test runner with HTML metrics report.
 *
 * <p>This does NOT use JMH — it runs quick, direct measurements and
 * generates a self-contained HTML dashboard with all captured metrics.</p>
 *
 * <p>Run: {@code java --add-modules jdk.incubator.vector -cp ... PerformanceTestRunner}</p>
 */
public class PerformanceTestRunner {

    // ─── Test configuration ───
    private static final int[] DATASET_SIZES = {10_000, 50_000, 100_000};
    private static final int DIMENSIONS = 128;
    private static final int WARMUP_ITERATIONS = 50;
    private static final int MEASURE_ITERATIONS = 200;
    private static final int[] CONCURRENCY_LEVELS = {1, 4, 8, 16};

    private static final String[] WORDS = {
            "java", "search", "vector", "simd", "performance", "engine",
            "query", "index", "document", "semantic", "hybrid", "fusion",
            "kernel", "memory", "thread", "virtual", "panama", "arena",
            "embedding", "transformer", "neural", "network", "optimization"
    };

    private final List<BenchmarkResult> results = new ArrayList<>();
    private final Runtime runtime = Runtime.getRuntime();

    public static void main(String[] args) throws Exception {
        var runner = new PerformanceTestRunner();
        runner.run();
    }

    public void run() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║        SPECTOR SEARCH — HEAVY PERFORMANCE TEST          ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.printf("  SIMD: %s%n", SimdCapability.report());
        System.out.printf("  CPUs: %d  |  Max Heap: %d MB%n",
                runtime.availableProcessors(), runtime.maxMemory() / (1024 * 1024));
        System.out.println();

        // 1. SIMD Kernel Benchmarks
        runSimdKernelTests();

        // 2. Per-scale ingestion + search benchmarks
        for (int size : DATASET_SIZES) {
            runScaleBenchmark(size);
        }

        // 3. Concurrency stress test
        runConcurrencyTest();

        // 4. Generate report
        Path reportPath = Path.of("spector-bench", "target", "performance-report.html");
        Files.createDirectories(reportPath.getParent());
        generateHtmlReport(reportPath);

        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.printf("  Report: %s%n", reportPath.toAbsolutePath());
        System.out.println("═══════════════════════════════════════════════════════════");
    }

    // ─────────────── SIMD Kernel Tests ───────────────

    private void runSimdKernelTests() {
        System.out.println("▶ SIMD Kernel Benchmarks");
        Random rng = new Random(42);

        for (int dim : new int[]{32, 128, 384, 768}) {
            float[] a = randomVector(dim, rng);
            float[] b = randomVector(dim, rng);

            // Warmup
            for (int i = 0; i < 1000; i++) {
                CosineSimilarity.compute(a, b);
                DotProduct.compute(a, b);
            }

            // Measure cosine
            long[] cosineNanos = new long[5000];
            for (int i = 0; i < cosineNanos.length; i++) {
                long t0 = System.nanoTime();
                CosineSimilarity.compute(a, b);
                cosineNanos[i] = System.nanoTime() - t0;
            }
            var cosineStats = computeStats(cosineNanos);
            record("SIMD Cosine", "dim=" + dim, cosineStats);

            // Measure dot product
            long[] dotNanos = new long[5000];
            for (int i = 0; i < dotNanos.length; i++) {
                long t0 = System.nanoTime();
                DotProduct.compute(a, b);
                dotNanos[i] = System.nanoTime() - t0;
            }
            var dotStats = computeStats(dotNanos);
            record("SIMD DotProduct", "dim=" + dim, dotStats);

            System.out.printf("  dim=%3d  cosine: p50=%.1fns p99=%.1fns  dot: p50=%.1fns p99=%.1fns%n",
                    dim, cosineStats.p50, cosineStats.p99, dotStats.p50, dotStats.p99);
        }
        System.out.println();
    }

    // ─────────────── Scale Benchmarks ───────────────

    private void runScaleBenchmark(int datasetSize) {
        System.out.printf("▶ Scale Benchmark: %,d documents (dim=%d)%n", datasetSize, DIMENSIONS);

        var hnswParams = new HnswParams(16, 200, 64);
        var config = new SpectorConfig(DIMENSIONS, datasetSize + 1000,
                SimilarityFunction.COSINE, hnswParams);

        long memBefore = usedMemoryMB();
        Instant ingestStart = Instant.now();

        SpectorEngine engine = new SpectorEngine(config);
        Random rng = new Random(42);

        // Ingestion
        for (int i = 0; i < datasetSize; i++) {
            String content = generateText(20 + rng.nextInt(60), rng);
            float[] vector = randomVector(DIMENSIONS, rng);
            engine.ingest("doc-" + i, content, vector);
        }

        Duration ingestDuration = Duration.between(ingestStart, Instant.now());
        long memAfter = usedMemoryMB();
        double ingestRate = datasetSize / (ingestDuration.toMillis() / 1000.0);

        record("Ingestion", "n=" + datasetSize, ingestDuration.toMillis(),
                ingestRate, memAfter - memBefore);

        System.out.printf("  Ingested in %s (%.0f docs/s)  mem: +%d MB%n",
                formatDuration(ingestDuration), ingestRate, memAfter - memBefore);

        // Prepare query
        Random qrng = new Random(999);
        float[] queryVector = randomVector(DIMENSIONS, qrng);

        // Keyword search
        var kwStats = benchmarkSearch(engine, "keyword", () ->
                engine.keywordSearch("java vector search engine", 10));
        record("Keyword Search", "n=" + datasetSize + " k=10", kwStats);

        // Vector search
        var vecStats = benchmarkSearch(engine, "vector", () ->
                engine.vectorSearch(queryVector, 10));
        record("Vector Search", "n=" + datasetSize + " k=10", vecStats);

        // Hybrid search
        var hybStats = benchmarkSearch(engine, "hybrid", () ->
                engine.hybridSearch("java vector search", queryVector, 10));
        record("Hybrid Search", "n=" + datasetSize + " k=10", hybStats);

        // Large topK
        var vec100Stats = benchmarkSearch(engine, "vector-k100", () ->
                engine.vectorSearch(queryVector, 100));
        record("Vector Search", "n=" + datasetSize + " k=100", vec100Stats);

        engine.close();
        System.out.println();
    }

    private LatencyStats benchmarkSearch(SpectorEngine engine, String label, Runnable searchFn) {
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) searchFn.run();

        long[] nanos = new long[MEASURE_ITERATIONS];
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            long t0 = System.nanoTime();
            searchFn.run();
            nanos[i] = System.nanoTime() - t0;
        }

        var stats = computeStats(nanos);
        System.out.printf("  %-14s  p50=%.2fms  p95=%.2fms  p99=%.2fms  avg=%.2fms  throughput=%.0f/s%n",
                label, stats.p50 / 1e6, stats.p95 / 1e6, stats.p99 / 1e6,
                stats.mean / 1e6, 1e9 / stats.mean);
        return stats;
    }

    // ─────────────── Concurrency Test ───────────────

    private void runConcurrencyTest() throws Exception {
        System.out.println("▶ Concurrency Stress Test (50K docs)");

        var hnswParams = new HnswParams(16, 200, 64);
        var config = new SpectorConfig(DIMENSIONS, 51_000,
                SimilarityFunction.COSINE, hnswParams);

        SpectorEngine engine = new SpectorEngine(config);
        Random rng = new Random(42);
        for (int i = 0; i < 50_000; i++) {
            engine.ingest("doc-" + i, generateText(30, rng), randomVector(DIMENSIONS, rng));
        }

        for (int threads : CONCURRENCY_LEVELS) {
            float[] qv = randomVector(DIMENSIONS, new Random(999));
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            AtomicLong totalOps = new AtomicLong();
            AtomicLong totalNanos = new AtomicLong();
            int opsPerThread = 500;

            // Warmup
            for (int i = 0; i < 50; i++) engine.hybridSearch("java", qv, 10);

            long wallStart = System.nanoTime();
            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                futures.add(executor.submit(() -> {
                    Random trng = new Random(threadId);
                    float[] tqv = randomVector(DIMENSIONS, trng);
                    for (int i = 0; i < opsPerThread; i++) {
                        long t0 = System.nanoTime();
                        engine.hybridSearch("java vector search", tqv, 10);
                        totalNanos.addAndGet(System.nanoTime() - t0);
                        totalOps.incrementAndGet();
                    }
                }));
            }

            for (var f : futures) f.get();
            long wallElapsed = System.nanoTime() - wallStart;
            executor.shutdown();

            double wallSec = wallElapsed / 1e9;
            double throughput = totalOps.get() / wallSec;
            double avgLatencyMs = (totalNanos.get() / (double) totalOps.get()) / 1e6;

            record("Concurrent Hybrid", "threads=" + threads,
                    avgLatencyMs, throughput, 0);

            System.out.printf("  threads=%2d  throughput=%.0f ops/s  avg=%.2fms  wall=%.2fs%n",
                    threads, throughput, avgLatencyMs, wallSec);
        }

        engine.close();
        System.out.println();
    }

    // ─────────────── Helpers ───────────────

    private float[] randomVector(int dim, Random rng) {
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) v[i] = rng.nextFloat() * 2f - 1f;
        return v;
    }

    private String generateText(int wordCount, Random rng) {
        StringBuilder sb = new StringBuilder(wordCount * 8);
        for (int w = 0; w < wordCount; w++)
            sb.append(WORDS[rng.nextInt(WORDS.length)]).append(' ');
        return sb.toString();
    }

    private long usedMemoryMB() {
        runtime.gc();
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
    }

    private String formatDuration(Duration d) {
        if (d.toMinutes() > 0) return d.toMinutes() + "m " + (d.toSeconds() % 60) + "s";
        return d.toSeconds() + "." + (d.toMillis() % 1000) / 100 + "s";
    }

    // ─────────────── Statistics ───────────────

    record LatencyStats(double min, double max, double mean,
                        double p50, double p95, double p99, double stddev) {}

    private LatencyStats computeStats(long[] nanos) {
        Arrays.sort(nanos);
        int n = nanos.length;
        double sum = 0;
        for (long v : nanos) sum += v;
        double mean = sum / n;
        double variance = 0;
        for (long v : nanos) variance += (v - mean) * (v - mean);
        double stddev = Math.sqrt(variance / n);

        return new LatencyStats(
                nanos[0], nanos[n - 1], mean,
                nanos[(int) (n * 0.50)],
                nanos[(int) (n * 0.95)],
                nanos[(int) (n * 0.99)],
                stddev
        );
    }

    // ─────────────── Result Recording ───────────────

    record BenchmarkResult(String category, String params,
                           double p50, double p95, double p99,
                           double mean, double throughput, long memMB) {}

    private void record(String category, String params, LatencyStats stats) {
        results.add(new BenchmarkResult(category, params,
                stats.p50, stats.p95, stats.p99, stats.mean,
                stats.mean > 0 ? 1e9 / stats.mean : 0, 0));
    }

    private void record(String category, String params,
                        double latencyMs, double throughput, long memMB) {
        results.add(new BenchmarkResult(category, params,
                latencyMs, latencyMs, latencyMs, latencyMs, throughput, memMB));
    }

    // ─────────────── HTML Report ───────────────

    private void generateHtmlReport(Path path) throws IOException {
        String timestamp = LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        // Group results by category
        Map<String, List<BenchmarkResult>> grouped = results.stream()
                .collect(Collectors.groupingBy(BenchmarkResult::category,
                        LinkedHashMap::new, Collectors.toList()));

        StringBuilder rows = new StringBuilder();
        for (var entry : grouped.entrySet()) {
            for (var r : entry.getValue()) {
                boolean isNanos = r.category.startsWith("SIMD");
                String unit = isNanos ? "ns" : "ms";
                double div = isNanos ? 1.0 : 1e6;

                rows.append(String.format(
                        "<tr><td>%s</td><td>%s</td><td>%.2f %s</td>" +
                        "<td>%.2f %s</td><td>%.2f %s</td><td>%.2f %s</td>" +
                        "<td>%.0f</td><td>%s</td></tr>\n",
                        r.category, r.params,
                        r.p50 / div, unit, r.p95 / div, unit,
                        r.p99 / div, unit, r.mean / div, unit,
                        r.throughput,
                        r.memMB > 0 ? r.memMB + " MB" : "—"
                ));
            }
        }

        // Build chart data for search latencies
        StringBuilder chartLabels = new StringBuilder("[");
        StringBuilder chartP50 = new StringBuilder("[");
        StringBuilder chartP99 = new StringBuilder("[");
        boolean first = true;
        for (var r : results) {
            if (!r.category.contains("Search")) continue;
            if (!first) { chartLabels.append(","); chartP50.append(","); chartP99.append(","); }
            chartLabels.append("'").append(r.category).append(" ").append(r.params).append("'");
            chartP50.append(String.format("%.3f", r.p50 / 1e6));
            chartP99.append(String.format("%.3f", r.p99 / 1e6));
            first = false;
        }
        chartLabels.append("]");
        chartP50.append("]");
        chartP99.append("]");

        // Concurrency chart data
        StringBuilder concLabels = new StringBuilder("[");
        StringBuilder concThroughput = new StringBuilder("[");
        first = true;
        for (var r : results) {
            if (!r.category.startsWith("Concurrent")) continue;
            if (!first) { concLabels.append(","); concThroughput.append(","); }
            concLabels.append("'").append(r.params).append("'");
            concThroughput.append(String.format("%.0f", r.throughput));
            first = false;
        }
        concLabels.append("]");
        concThroughput.append("]");

        String html = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>Spector Search — Performance Report</title>
        <script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.4/dist/chart.umd.min.js"></script>
        <style>
          :root {
            --bg: #0f0f1a; --surface: #1a1a2e; --border: #2a2a4a;
            --text: #e0e0e8; --accent: #7c3aed; --accent2: #06b6d4;
            --green: #10b981; --red: #ef4444; --orange: #f59e0b;
          }
          * { box-sizing: border-box; margin: 0; padding: 0; }
          body {
            font-family: 'Inter', 'Segoe UI', system-ui, sans-serif;
            background: var(--bg); color: var(--text);
            line-height: 1.6; padding: 2rem;
          }
          .header {
            text-align: center; margin-bottom: 2rem;
            background: linear-gradient(135deg, var(--surface), #16213e);
            border: 1px solid var(--border); border-radius: 16px;
            padding: 2rem;
          }
          .header h1 {
            font-size: 2rem;
            background: linear-gradient(90deg, var(--accent), var(--accent2));
            -webkit-background-clip: text; -webkit-text-fill-color: transparent;
          }
          .header .meta { color: #888; font-size: 0.9rem; margin-top: 0.5rem; }
          .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 1rem; margin-bottom: 2rem; }
          .card {
            background: var(--surface); border: 1px solid var(--border);
            border-radius: 12px; padding: 1.5rem;
          }
          .card h3 { color: var(--accent2); font-size: 0.85rem; text-transform: uppercase; letter-spacing: 1px; }
          .card .value { font-size: 2rem; font-weight: 700; margin: 0.5rem 0; }
          .card .sub { color: #888; font-size: 0.85rem; }
          .chart-container {
            background: var(--surface); border: 1px solid var(--border);
            border-radius: 12px; padding: 1.5rem; margin-bottom: 2rem;
          }
          .chart-container h2 { margin-bottom: 1rem; font-size: 1.2rem; }
          table {
            width: 100%%; border-collapse: collapse;
            background: var(--surface); border-radius: 12px;
            overflow: hidden; border: 1px solid var(--border);
          }
          th {
            background: #16213e; padding: 12px 16px;
            text-align: left; font-size: 0.8rem;
            text-transform: uppercase; letter-spacing: 1px;
            color: var(--accent2);
          }
          td { padding: 10px 16px; border-top: 1px solid var(--border); font-size: 0.9rem; }
          tr:hover { background: rgba(124, 58, 237, 0.08); }
          .charts-row { display: grid; grid-template-columns: 1fr 1fr; gap: 1rem; margin-bottom: 2rem; }
          @media (max-width: 900px) { .charts-row { grid-template-columns: 1fr; } }
        </style>
        </head>
        <body>
        <div class="header">
          <h1>⚡ Spector Search Performance Report</h1>
          <div class="meta">Generated: %s | Java %s | CPUs: %d | SIMD: %s</div>
        </div>

        <div class="grid">
          <div class="card">
            <h3>Total Benchmarks</h3>
            <div class="value">%d</div>
            <div class="sub">across all categories</div>
          </div>
          <div class="card">
            <h3>Max Dataset</h3>
            <div class="value">%s</div>
            <div class="sub">documents indexed</div>
          </div>
          <div class="card">
            <h3>Max Concurrency</h3>
            <div class="value">%d threads</div>
            <div class="sub">parallel search load</div>
          </div>
          <div class="card">
            <h3>Vector Dimensions</h3>
            <div class="value">%d</div>
            <div class="sub">embedding size tested</div>
          </div>
        </div>

        <div class="charts-row">
          <div class="chart-container">
            <h2>Search Latency (ms)</h2>
            <canvas id="latencyChart" height="300"></canvas>
          </div>
          <div class="chart-container">
            <h2>Concurrent Throughput (ops/s)</h2>
            <canvas id="concChart" height="300"></canvas>
          </div>
        </div>

        <div class="chart-container">
          <h2>Full Results</h2>
          <table>
            <thead><tr>
              <th>Benchmark</th><th>Params</th><th>P50</th><th>P95</th>
              <th>P99</th><th>Mean</th><th>Throughput</th><th>Memory</th>
            </tr></thead>
            <tbody>%s</tbody>
          </table>
        </div>

        <script>
        const chartColors = { p50: '#7c3aed', p99: '#ef4444', bar: '#06b6d4' };
        Chart.defaults.color = '#888';
        Chart.defaults.borderColor = '#2a2a4a';

        new Chart(document.getElementById('latencyChart'), {
          type: 'bar',
          data: {
            labels: %s,
            datasets: [
              { label: 'P50 (ms)', data: %s, backgroundColor: chartColors.p50 + '99', borderColor: chartColors.p50, borderWidth: 1 },
              { label: 'P99 (ms)', data: %s, backgroundColor: chartColors.p99 + '99', borderColor: chartColors.p99, borderWidth: 1 }
            ]
          },
          options: {
            responsive: true,
            plugins: { legend: { position: 'top' } },
            scales: { y: { beginAtZero: true, title: { display: true, text: 'Latency (ms)' } },
                      x: { ticks: { maxRotation: 45 } } }
          }
        });

        new Chart(document.getElementById('concChart'), {
          type: 'bar',
          data: {
            labels: %s,
            datasets: [{ label: 'Throughput', data: %s,
              backgroundColor: chartColors.bar + '99', borderColor: chartColors.bar, borderWidth: 1 }]
          },
          options: {
            responsive: true,
            plugins: { legend: { display: false } },
            scales: { y: { beginAtZero: true, title: { display: true, text: 'ops/sec' } } }
          }
        });
        </script>
        </body>
        </html>
        """.formatted(
                timestamp,
                System.getProperty("java.version"),
                runtime.availableProcessors(),
                SimdCapability.report(),
                results.size(),
                String.format("%,d", DATASET_SIZES[DATASET_SIZES.length - 1]),
                CONCURRENCY_LEVELS[CONCURRENCY_LEVELS.length - 1],
                DIMENSIONS,
                rows,
                chartLabels, chartP50, chartP99,
                concLabels, concThroughput
        );

        Files.writeString(path, html);
    }
}
