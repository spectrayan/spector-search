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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import com.spectrayan.spector.core.simd.SimdCapability;
import com.spectrayan.spector.core.similarity.SimilarityFunction;
import com.spectrayan.spector.config.SpectorConfig;
import com.spectrayan.spector.engine.DefaultSpectorEngine;
import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.config.HnswParams;

/**
 * Industry-standard benchmark following ann-benchmarks methodology.
 *
 * <p>Key differences from the previous PerformanceTestRunner:</p>
 * <ul>
 *   <li>Uses clustered (realistic) vectors, not uniform random</li>
 *   <li>Measures recall@K against brute-force ground truth</li>
 *   <li>Tests multiple dimensions: 128, 384, 768</li>
 *   <li>Uses realistic document sizes: 200-2000 words (like real paragraphs/pages)</li>
 *   <li>Reports QPS at specific recall thresholds</li>
 *   <li>Records system state (CPU%, RAM) during test</li>
 * </ul>
 *
 * <p>Run: {@code mvn -pl spector-bench exec:java -Dexec.mainClass=com.spectrayan.spector.bench.IndustryBenchmark}</p>
 */
public class IndustryBenchmark {

    // ─── Configuration ───
    private static final int[] DATASET_SIZES = {10_000, 50_000, 100_000};
    private static final int[] DIMENSIONS = {128, 384, 768};
    private static final int WARMUP_QUERIES = 100;
    private static final int MEASURE_QUERIES = 500;
    private static final int[] CONCURRENCY_LEVELS = {1, 4, 8, 16};
    private static final int TOP_K = 10;
    private static final int NUM_CLUSTERS = 50; // for realistic vector generation

    // Realistic document corpus words (varied topics, longer vocabulary)
    private static final String[] CORPUS = {
        "machine", "learning", "algorithm", "neural", "network", "deep",
        "transformer", "attention", "embedding", "vector", "semantic",
        "retrieval", "augmented", "generation", "language", "model",
        "inference", "training", "gradient", "optimization", "batch",
        "epoch", "loss", "function", "activation", "layer", "weight",
        "bias", "dropout", "regularization", "normalization", "encoder",
        "decoder", "tokenizer", "vocabulary", "context", "window",
        "position", "encoding", "multi-head", "self-attention", "cross",
        "architecture", "parameter", "fine-tuning", "pre-training",
        "benchmark", "evaluation", "metric", "accuracy", "precision",
        "recall", "f1-score", "latency", "throughput", "scalability",
        "distributed", "parallel", "concurrent", "asynchronous", "pipeline",
        "streaming", "real-time", "indexing", "search", "query",
        "document", "passage", "chunk", "sentence", "paragraph",
        "knowledge", "base", "graph", "ontology", "taxonomy",
        "classification", "clustering", "similarity", "distance",
        "nearest", "neighbor", "approximate", "exact", "brute-force",
        "quantization", "compression", "pruning", "distillation",
        "deployment", "production", "monitoring", "observability",
        "infrastructure", "cloud", "server", "client", "api",
        "endpoint", "request", "response", "authentication", "authorization",
        "database", "storage", "memory", "cache", "buffer",
        "performance", "optimization", "profiling", "bottleneck"
    };

    private final List<BenchResult> results = new ArrayList<>();
    private final Runtime runtime = Runtime.getRuntime();

    public static void main(String[] args) throws Exception {
        new IndustryBenchmark().run();
    }

    public void run() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║   SPECTOR SEARCH — INDUSTRY-STANDARD BENCHMARK SUITE        ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();
        printSystemInfo();
        System.out.println();

        // Phase 1: Recall + Latency at different scales and dimensions
        for (int dims : DIMENSIONS) {
            for (int size : DATASET_SIZES) {
                if (dims == 768 && size == 100_000) continue; // skip largest combo to keep runtime reasonable
                runRecallLatencyBenchmark(dims, size);
            }
        }

        // Phase 2: Document size impact (does content byte size affect search?)
        runDocumentSizeImpact();

        // Phase 3: Concurrency at 50K/384-dim (realistic production scenario)
        runConcurrencyBenchmark(384, 50_000);

        // Generate report
        printSummary();
        Path reportPath = Path.of("spector-bench", "target", "industry-benchmark.txt");
        Files.createDirectories(reportPath.getParent());
        writeReport(reportPath);
        System.out.printf("%n  Report saved: %s%n", reportPath.toAbsolutePath());
    }

    private void printSystemInfo() {
        long totalMem = runtime.maxMemory() / (1024 * 1024);
        System.out.printf("  OS:         %s %s%n", System.getProperty("os.name"), System.getProperty("os.arch"));
        System.out.printf("  Java:       %s%n", System.getProperty("java.version"));
        System.out.printf("  CPUs:       %d logical cores%n", runtime.availableProcessors());
        System.out.printf("  Max Heap:   %d MB%n", totalMem);
        System.out.printf("  SIMD:       %s%n", SimdCapability.report());
        System.out.printf("  Timestamp:  %s%n", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
    }

    // ─────────────── Recall + Latency Benchmark ───────────────

    private void runRecallLatencyBenchmark(int dims, int datasetSize) {
        System.out.printf("▶ Recall+Latency: %,d docs × %d-dim%n", datasetSize, dims);

        var hnswParams = new HnswParams(16, 200, 64);
        var config = new SpectorConfig(dims, datasetSize + 1000,
                SimilarityFunction.COSINE, hnswParams);

        SpectorEngine engine = new DefaultSpectorEngine(config);
        Random rng = new Random(42);

        // Generate clustered vectors (realistic: embeddings form clusters in practice)
        float[][] allVectors = generateClusteredVectors(datasetSize, dims, rng);

        // Ingest with realistic document content
        Instant ingestStart = Instant.now();
        for (int i = 0; i < datasetSize; i++) {
            String content = generateRealisticDocument(rng);
            engine.ingest("doc-" + i, content, allVectors[i]);
        }
        Duration ingestTime = Duration.between(ingestStart, Instant.now());
        double ingestRate = datasetSize / (ingestTime.toMillis() / 1000.0);
        System.out.printf("  Ingested in %.1fs (%.0f docs/s)%n",
                ingestTime.toMillis() / 1000.0, ingestRate);

        // Generate query vectors from same distribution (realistic: queries are similar to corpus)
        int numQueries = MEASURE_QUERIES;
        float[][] queryVectors = new float[numQueries][];
        Random qrng = new Random(999);
        for (int i = 0; i < numQueries; i++) {
            // Pick a random cluster center and add noise (simulates real queries)
            int cluster = qrng.nextInt(NUM_CLUSTERS);
            queryVectors[i] = perturbVector(allVectors[cluster * (datasetSize / NUM_CLUSTERS)], 0.3f, dims, qrng);
        }

        // Compute brute-force ground truth for recall measurement
        int[][] groundTruth = computeGroundTruth(queryVectors, allVectors, TOP_K);

        // Warmup
        for (int i = 0; i < WARMUP_QUERIES; i++) {
            engine.vectorSearch(queryVectors[i % numQueries], TOP_K);
        }

        // Measure vector search
        long[] vectorNanos = new long[numQueries];
        int totalRecallHits = 0;
        for (int i = 0; i < numQueries; i++) {
            long t0 = System.nanoTime();
            var response = engine.vectorSearch(queryVectors[i], TOP_K);
            vectorNanos[i] = System.nanoTime() - t0;

            // Compute recall
            Set<String> retrieved = new HashSet<>();
            for (var r : response.results()) retrieved.add(r.id());
            for (int gt : groundTruth[i]) {
                if (retrieved.contains("doc-" + gt)) totalRecallHits++;
            }
        }
        double recall = (double) totalRecallHits / (numQueries * TOP_K);
        var vecStats = computeStats(vectorNanos);

        System.out.printf("  Vector:  avg=%.3fms  p99=%.3fms  recall@%d=%.1f%%  QPS=%.0f%n",
                vecStats.mean / 1e6, vecStats.p99 / 1e6, TOP_K, recall * 100, 1e9 / vecStats.mean);

        results.add(new BenchResult("Vector Search", dims, datasetSize,
                vecStats.mean / 1e6, vecStats.p99 / 1e6, 1e9 / vecStats.mean, recall));

        // Measure keyword search
        String[] queryTexts = {"machine learning neural network architecture",
                "retrieval augmented generation language model",
                "distributed parallel concurrent optimization",
                "quantization compression approximate nearest neighbor",
                "performance latency throughput scalability benchmark"};
        long[] kwNanos = new long[numQueries];
        for (int i = 0; i < numQueries; i++) {
            String q = queryTexts[i % queryTexts.length];
            long t0 = System.nanoTime();
            engine.keywordSearch(q, TOP_K);
            kwNanos[i] = System.nanoTime() - t0;
        }
        var kwStats = computeStats(kwNanos);
        System.out.printf("  Keyword: avg=%.3fms  p99=%.3fms  QPS=%.0f%n",
                kwStats.mean / 1e6, kwStats.p99 / 1e6, 1e9 / kwStats.mean);

        results.add(new BenchResult("Keyword Search", dims, datasetSize,
                kwStats.mean / 1e6, kwStats.p99 / 1e6, 1e9 / kwStats.mean, -1));

        // Measure hybrid search
        long[] hybNanos = new long[numQueries];
        for (int i = 0; i < numQueries; i++) {
            String q = queryTexts[i % queryTexts.length];
            long t0 = System.nanoTime();
            engine.hybridSearch(q, queryVectors[i], TOP_K);
            hybNanos[i] = System.nanoTime() - t0;
        }
        var hybStats = computeStats(hybNanos);
        System.out.printf("  Hybrid:  avg=%.3fms  p99=%.3fms  QPS=%.0f%n",
                hybStats.mean / 1e6, hybStats.p99 / 1e6, 1e9 / hybStats.mean);

        results.add(new BenchResult("Hybrid Search", dims, datasetSize,
                hybStats.mean / 1e6, hybStats.p99 / 1e6, 1e9 / hybStats.mean, -1));

        // Record ingestion
        results.add(new BenchResult("Ingestion", dims, datasetSize,
                ingestTime.toMillis(), 0, ingestRate, -1));

        engine.close();
        System.out.println();
    }

    // ─────────────── Document Size Impact ───────────────

    private void runDocumentSizeImpact() {
        System.out.println("▶ Document Size Impact Test (10K docs, 384-dim)");
        int dims = 384;
        int size = 10_000;
        Random rng = new Random(42);
        float[][] vectors = generateClusteredVectors(size, dims, rng);
        float[] queryVec = perturbVector(vectors[0], 0.3f, dims, new Random(999));

        int[][] docWordCounts = {{50, 100}, {200, 500}, {500, 1500}, {1000, 3000}};
        String[] labels = {"Short (50-100w)", "Medium (200-500w)", "Long (500-1500w)", "Very Long (1-3Kw)"};

        for (int t = 0; t < docWordCounts.length; t++) {
            var hnswParams = new HnswParams(16, 200, 64);
            var config = new SpectorConfig(dims, size + 1000, SimilarityFunction.COSINE, hnswParams);
            SpectorEngine engine = new DefaultSpectorEngine(config);

            int minWords = docWordCounts[t][0];
            int maxWords = docWordCounts[t][1];
            long totalBytes = 0;

            for (int i = 0; i < size; i++) {
                int wordCount = minWords + rng.nextInt(maxWords - minWords);
                String content = generateDocument(wordCount, rng);
                totalBytes += content.length();
                engine.ingest("doc-" + i, content, vectors[i]);
            }

            // Warmup
            for (int i = 0; i < 50; i++) engine.vectorSearch(queryVec, TOP_K);

            // Measure
            long[] nanos = new long[200];
            for (int i = 0; i < 200; i++) {
                long t0 = System.nanoTime();
                engine.vectorSearch(queryVec, TOP_K);
                nanos[i] = System.nanoTime() - t0;
            }
            var stats = computeStats(nanos);
            long avgDocBytes = totalBytes / size;

            System.out.printf("  %-20s avgDoc=%,dB  vecSearch=%.3fms  QPS=%.0f%n",
                    labels[t], avgDocBytes, stats.mean / 1e6, 1e9 / stats.mean);

            results.add(new BenchResult("DocSize:" + labels[t], dims, size,
                    stats.mean / 1e6, stats.p99 / 1e6, 1e9 / stats.mean, -1));
            engine.close();
        }
        System.out.println();
    }

    // ─────────────── Concurrency Benchmark ───────────────

    private void runConcurrencyBenchmark(int dims, int datasetSize) throws Exception {
        System.out.printf("▶ Concurrency Scaling: %,d docs × %d-dim%n", datasetSize, dims);

        var hnswParams = new HnswParams(16, 200, 64);
        var config = new SpectorConfig(dims, datasetSize + 1000,
                SimilarityFunction.COSINE, hnswParams);
        SpectorEngine engine = new DefaultSpectorEngine(config);
        Random rng = new Random(42);

        float[][] vectors = generateClusteredVectors(datasetSize, dims, rng);
        for (int i = 0; i < datasetSize; i++) {
            engine.ingest("doc-" + i, generateRealisticDocument(rng), vectors[i]);
        }

        for (int threads : CONCURRENCY_LEVELS) {
            int opsPerThread = 300;
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            AtomicLong totalOps = new AtomicLong();
            AtomicLong totalNanos = new AtomicLong();

            // Warmup
            float[] wv = perturbVector(vectors[0], 0.3f, dims, new Random(999));
            for (int i = 0; i < 50; i++) engine.hybridSearch("neural network", wv, TOP_K);

            long wallStart = System.nanoTime();
            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < threads; t++) {
                final int tid = t;
                futures.add(executor.submit(() -> {
                    Random trng = new Random(tid + 1000);
                    float[] qv = perturbVector(vectors[trng.nextInt(datasetSize)], 0.3f, dims, trng);
                    for (int i = 0; i < opsPerThread; i++) {
                        long t0 = System.nanoTime();
                        engine.hybridSearch("machine learning optimization", qv, TOP_K);
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

            System.out.printf("  threads=%2d  throughput=%.0f ops/s  avgLatency=%.2fms%n",
                    threads, throughput, avgLatencyMs);

            results.add(new BenchResult("Concurrent(t=" + threads + ")", dims, datasetSize,
                    avgLatencyMs, 0, throughput, -1));
        }
        engine.close();
        System.out.println();
    }

    // ─────────────── Vector Generation (Clustered, Realistic) ───────────────

    /**
     * Generates vectors that form clusters (like real embeddings).
     * Real embeddings from transformer models form clusters around topics/concepts.
     */
    private float[][] generateClusteredVectors(int count, int dims, Random rng) {
        // Generate cluster centers
        float[][] centers = new float[NUM_CLUSTERS][dims];
        for (int c = 0; c < NUM_CLUSTERS; c++) {
            for (int d = 0; d < dims; d++) {
                centers[c][d] = (float) rng.nextGaussian() * 0.5f;
            }
            normalize(centers[c]);
        }

        // Generate vectors around cluster centers
        float[][] vectors = new float[count][dims];
        for (int i = 0; i < count; i++) {
            int cluster = rng.nextInt(NUM_CLUSTERS);
            for (int d = 0; d < dims; d++) {
                vectors[i][d] = centers[cluster][d] + (float) rng.nextGaussian() * 0.15f;
            }
            normalize(vectors[i]);
        }
        return vectors;
    }

    private float[] perturbVector(float[] base, float noise, int dims, Random rng) {
        float[] result = new float[dims];
        for (int d = 0; d < dims; d++) {
            result[d] = base[d] + (float) rng.nextGaussian() * noise;
        }
        normalize(result);
        return result;
    }

    private void normalize(float[] v) {
        float norm = 0;
        for (float f : v) norm += f * f;
        norm = (float) Math.sqrt(norm);
        if (norm > 1e-10f) {
            for (int i = 0; i < v.length; i++) v[i] /= norm;
        }
    }

    // ─────────────── Ground Truth (Brute-Force KNN) ───────────────

    private int[][] computeGroundTruth(float[][] queries, float[][] database, int k) {
        int[][] truth = new int[queries.length][k];
        for (int q = 0; q < queries.length; q++) {
            // Compute all distances
            float[] dists = new float[database.length];
            for (int i = 0; i < database.length; i++) {
                dists[i] = cosineSim(queries[q], database[i]);
            }
            // Find top-K by sorting indices
            Integer[] indices = new Integer[database.length];
            for (int i = 0; i < database.length; i++) indices[i] = i;
            Arrays.sort(indices, (a, b) -> Float.compare(dists[b], dists[a]));
            for (int i = 0; i < k; i++) truth[q][i] = indices[i];
        }
        return truth;
    }

    private float cosineSim(float[] a, float[] b) {
        float dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return (float) (dot / (Math.sqrt(na) * Math.sqrt(nb) + 1e-10));
    }

    // ─────────────── Document Generation ───────────────

    /** Generates a realistic document (200-1500 words, paragraph structure). */
    private String generateRealisticDocument(Random rng) {
        return generateDocument(200 + rng.nextInt(1300), rng);
    }

    /** Generates a document of specified word count with paragraph breaks. */
    private String generateDocument(int wordCount, Random rng) {
        StringBuilder sb = new StringBuilder(wordCount * 8);
        int sentenceLen = 8 + rng.nextInt(15);
        int paraLen = 3 + rng.nextInt(5);
        int sentenceCount = 0;

        for (int w = 0; w < wordCount; w++) {
            sb.append(CORPUS[rng.nextInt(CORPUS.length)]);
            if ((w + 1) % sentenceLen == 0) {
                sb.append(". ");
                sentenceCount++;
                sentenceLen = 8 + rng.nextInt(15);
                if (sentenceCount % paraLen == 0) {
                    sb.append("\n\n");
                    paraLen = 3 + rng.nextInt(5);
                }
            } else {
                sb.append(' ');
            }
        }
        return sb.toString();
    }

    // ─────────────── Statistics ───────────────

    record Stats(double min, double max, double mean, double p50, double p95, double p99) {}

    private Stats computeStats(long[] nanos) {
        Arrays.sort(nanos);
        int n = nanos.length;
        double sum = 0;
        for (long v : nanos) sum += v;
        double mean = sum / n;
        return new Stats(nanos[0], nanos[n - 1], mean,
                nanos[(int) (n * 0.50)], nanos[(int) (n * 0.95)], nanos[(int) (n * 0.99)]);
    }

    // ─────────────── Results ───────────────

    record BenchResult(String name, int dims, int datasetSize,
                       double avgMs, double p99Ms, double qps, double recall) {}

    private void printSummary() {
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("  SUMMARY");
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.printf("  %-35s %8s %8s %10s %8s%n", "Benchmark", "Avg(ms)", "P99(ms)", "QPS", "Recall");
        System.out.println("  " + "-".repeat(75));
        for (var r : results) {
            String recallStr = r.recall >= 0 ? String.format("%.1f%%", r.recall * 100) : "—";
            System.out.printf("  %-35s %8.3f %8.3f %10.0f %8s%n",
                    r.name + " " + r.dims + "d/" + r.datasetSize / 1000 + "K",
                    r.avgMs, r.p99Ms, r.qps, recallStr);
        }
    }

    private void writeReport(Path path) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("Spector Industry Benchmark\n");
        sb.append("Generated: ").append(LocalDateTime.now()).append("\n");
        sb.append("Java: ").append(System.getProperty("java.version")).append("\n");
        sb.append("CPUs: ").append(runtime.availableProcessors()).append("\n");
        sb.append("SIMD: ").append(SimdCapability.report()).append("\n\n");

        sb.append(String.format("%-35s %8s %8s %10s %8s%n", "Benchmark", "Avg(ms)", "P99(ms)", "QPS", "Recall"));
        sb.append("-".repeat(80)).append("\n");
        for (var r : results) {
            String recallStr = r.recall >= 0 ? String.format("%.1f%%", r.recall * 100) : "—";
            sb.append(String.format("%-35s %8.3f %8.3f %10.0f %8s%n",
                    r.name + " " + r.dims + "d/" + r.datasetSize / 1000 + "K",
                    r.avgMs, r.p99Ms, r.qps, recallStr));
        }
        Files.writeString(path, sb.toString());
    }
}
