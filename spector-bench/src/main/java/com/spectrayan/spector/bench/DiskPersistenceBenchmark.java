package com.spectrayan.spector.bench;

import com.spectrayan.spector.config.HnswParams;
import com.spectrayan.spector.config.PersistenceMode;
import com.spectrayan.spector.config.SpectorConfig;
import com.spectrayan.spector.core.simd.SimdCapability;
import com.spectrayan.spector.core.similarity.SimilarityFunction;
import com.spectrayan.spector.embed.ollama.OllamaEmbeddingProvider;
import com.spectrayan.spector.engine.DefaultSpectorEngine;
import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.memory.*;
import com.spectrayan.spector.memory.cortex.MemorySource;
import com.spectrayan.spector.memory.sync.MemoryWal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Benchmarks Spector in DISK persistence mode — engine index, cognitive memory,
 * and Write-Ahead Log (WAL) with real Ollama embeddings.
 *
 * <h3>Tests</h3>
 * <ul>
 *   <li>D1: Engine DISK mode — mmap'd sharded vector store search latency</li>
 *   <li>D2: Engine DISK mode — cold-start (first search after open) vs warm</li>
 *   <li>D3: Cognitive Memory — remember + recall with real Ollama embeddings</li>
 *   <li>D4: WAL — append throughput and replay speed (file-backed, fsync'd)</li>
 *   <li>D5: Memory DISK mode — full pipeline: ingest → recall → reinforce → reflect</li>
 * </ul>
 *
 * <p>Requires Ollama running at localhost:11434 with an embedding model.</p>
 *
 * <p>Run: {@code mvn exec:java -pl spector-bench
 *   -Dexec.mainClass=com.spectrayan.spector.bench.DiskPersistenceBenchmark}</p>
 */
public class DiskPersistenceBenchmark {

    // ─── Configuration ───
    private static final int TOP_K = 10;
    private static final String EMBEDDING_MODEL = "qwen3-embedding:latest";
    private int DIMS;  // auto-detected from Ollama

    private final List<String[]> verdicts = new ArrayList<>();

    // ─── Main ───

    public static void main(String[] args) throws Exception {
        new DiskPersistenceBenchmark().run();
    }

    public void run() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║   SPECTOR — DISK PERSISTENCE + MEMORY BENCHMARK             ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();
        printSystemInfo();
        System.out.println();

        // Verify Ollama connectivity
        OllamaEmbeddingProvider embedder = OllamaEmbeddingProvider.create(EMBEDDING_MODEL);
        DIMS = embedder.dimensions();
        System.out.printf("  Ollama model: %s (%d-dim)%n%n", EMBEDDING_MODEL, DIMS);

        // D1: Engine DISK mode search latency
        runD1_DiskEngineLatency(embedder);

        // D2: Cold start vs warm
        runD2_ColdVsWarm();

        // D3: Cognitive memory with real embeddings
        runD3_CognitiveMemoryRecall(embedder);

        // D4: WAL throughput
        runD4_WalThroughput();

        // D5: Full memory pipeline
        runD5_FullMemoryPipeline(embedder);

        // Summary
        printVerdictTable();
        writeReport();
    }

    // ═══════════════════════════════════════════════════════════════
    //  D1: Engine DISK mode — search latency with mmap'd vectors
    // ═══════════════════════════════════════════════════════════════

    private void runD1_DiskEngineLatency(OllamaEmbeddingProvider embedder) throws Exception {
        System.out.println("▶ D1: Engine DISK Mode — Search Latency (mmap sharded store)");

        Path dataDir = Files.createTempDirectory("spector-disk-bench");
        int datasetSize = 5_000;

        var config = new SpectorConfig(DIMS, datasetSize + 1000,
                SimilarityFunction.COSINE, new HnswParams(16, 200, 64))
                .withPersistence(PersistenceMode.DISK, dataDir);

        SpectorEngine engine = new DefaultSpectorEngine(config);
        Random rng = new Random(42);

        // Ingest with synthetic vectors (skip Ollama for scale — embeddings are slow)
        float[][] vectors = generateClusteredVectors(datasetSize, DIMS, rng);
        Instant ingestStart = Instant.now();
        for (int i = 0; i < datasetSize; i++) {
            engine.ingest("doc-" + i, "document content " + i, vectors[i]);
        }
        Duration ingestTime = Duration.between(ingestStart, Instant.now());
        System.out.printf("  Ingested %,d docs to disk in %.1fs (%.0f docs/s)%n",
                datasetSize, ingestTime.toMillis() / 1000.0,
                datasetSize / (ingestTime.toMillis() / 1000.0));

        // Warmup
        float[] qv = perturbVector(vectors[0], 0.3f, DIMS, new Random(999));
        for (int i = 0; i < 200; i++) engine.vectorSearch(qv, TOP_K);

        // Measure search
        long[] nanos = new long[1000];
        for (int i = 0; i < 1000; i++) {
            long t0 = System.nanoTime();
            engine.vectorSearch(qv, TOP_K);
            nanos[i] = System.nanoTime() - t0;
        }
        var stats = computeStats(nanos);

        System.out.printf("  DISK search: p50=%.0fµs  p95=%.0fµs  p99=%.0fµs  QPS=%.0f%n",
                stats.p50 / 1000.0, stats.p95 / 1000.0, stats.p99 / 1000.0, 1e9 / stats.mean);

        // Compare with IN_MEMORY baseline on same data
        var memConfig = new SpectorConfig(DIMS, datasetSize + 1000,
                SimilarityFunction.COSINE, new HnswParams(16, 200, 64));
        SpectorEngine memEngine = new DefaultSpectorEngine(memConfig);
        for (int i = 0; i < datasetSize; i++) {
            memEngine.ingest("doc-" + i, "content " + i, vectors[i]);
        }
        for (int i = 0; i < 200; i++) memEngine.vectorSearch(qv, TOP_K);

        long[] memNanos = new long[1000];
        for (int i = 0; i < 1000; i++) {
            long t0 = System.nanoTime();
            memEngine.vectorSearch(qv, TOP_K);
            memNanos[i] = System.nanoTime() - t0;
        }
        var memStats = computeStats(memNanos);

        double overhead = (stats.p50 / memStats.p50 - 1.0) * 100;
        System.out.printf("  IN_MEMORY:   p50=%.0fµs  p95=%.0fµs  p99=%.0fµs  QPS=%.0f%n",
                memStats.p50 / 1000.0, memStats.p95 / 1000.0, memStats.p99 / 1000.0, 1e9 / memStats.mean);
        System.out.printf("  DISK overhead: %.1f%% (vs IN_MEMORY p50)%n%n", overhead);

        engine.close();
        memEngine.close();
        deleteDirectory(dataDir);

        verdicts.add(new String[]{"D1: DISK search latency",
                String.format("p50=%.0fµs (%.1f%% overhead)", stats.p50 / 1000.0, overhead),
                overhead < 50 ? "✅ VALIDATED" : "⚠️ OVERHEAD"});
    }

    // ═══════════════════════════════════════════════════════════════
    //  D2: Cold-start vs warm (page cache populated)
    // ═══════════════════════════════════════════════════════════════

    private void runD2_ColdVsWarm() throws Exception {
        System.out.println("▶ D2: Cold-Start vs Warm Search (page cache effects)");

        Path dataDir = Files.createTempDirectory("spector-cold-bench");
        int datasetSize = 10_000;

        var config = new SpectorConfig(DIMS, datasetSize + 1000,
                SimilarityFunction.COSINE, new HnswParams(16, 200, 64))
                .withPersistence(PersistenceMode.DISK, dataDir);

        // Build and close (writes to disk)
        SpectorEngine engine = new DefaultSpectorEngine(config);
        Random rng = new Random(42);
        float[][] vectors = generateClusteredVectors(datasetSize, DIMS, rng);
        for (int i = 0; i < datasetSize; i++) {
            engine.ingest("doc-" + i, "content " + i, vectors[i]);
        }
        engine.close();

        // Reopen — first search is "cold" (mmap page faults)
        float[] qv = perturbVector(vectors[0], 0.3f, DIMS, new Random(999));

        SpectorEngine engine2 = new DefaultSpectorEngine(config);
        long coldStart = System.nanoTime();
        engine2.vectorSearch(qv, TOP_K);
        long coldNanos = System.nanoTime() - coldStart;

        // Warm up — pages are now in OS cache
        for (int i = 0; i < 200; i++) engine2.vectorSearch(qv, TOP_K);
        long[] warmNanos = new long[500];
        for (int i = 0; i < 500; i++) {
            long t0 = System.nanoTime();
            engine2.vectorSearch(qv, TOP_K);
            warmNanos[i] = System.nanoTime() - t0;
        }
        var warmStats = computeStats(warmNanos);

        System.out.printf("  Cold-start (first search): %.2fms%n", coldNanos / 1e6);
        System.out.printf("  Warm (page-cached):        p50=%.0fµs  p99=%.0fµs%n",
                warmStats.p50 / 1000.0, warmStats.p99 / 1000.0);
        System.out.printf("  Cold/warm ratio:           %.0f×%n%n", (coldNanos / warmStats.p50));

        engine2.close();
        deleteDirectory(dataDir);

        verdicts.add(new String[]{"D2: Cold-start vs warm",
                String.format("cold=%.1fms, warm=%.0fµs", coldNanos / 1e6, warmStats.p50 / 1000.0),
                "✅ MEASURED"});
    }

    // ═══════════════════════════════════════════════════════════════
    //  D3: Cognitive Memory — real Ollama embeddings recall
    // ═══════════════════════════════════════════════════════════════

    private void runD3_CognitiveMemoryRecall(OllamaEmbeddingProvider embedder) throws Exception {
        System.out.println("▶ D3: Cognitive Memory — Remember + Recall with Ollama Embeddings");

        Path memDir = Files.createTempDirectory("spector-mem-bench");

        SpectorMemory memory = DefaultSpectorMemory.builder()
                .dimensions(DIMS)
                .embeddingProvider(embedder)
                .persistence(memDir)
                .persistenceMode(MemoryPersistenceMode.DISK)
                .semanticCapacity(10_000)
                .build();

        // Ingest real memories
        String[] memories = {
                "User prefers dark mode with high contrast colors for accessibility.",
                "The project uses Java 25 with Panama FFI for zero-copy vector operations.",
                "Meeting scheduled for Friday at 3 PM with the engineering team about SIMD optimizations.",
                "The HNSW index uses M=16, efConstruction=200 for production workloads.",
                "User's favorite programming language is Java, followed by Rust and Go.",
                "Database migration from PostgreSQL to Spector completed on March 15th.",
                "API rate limits set to 1000 requests per minute for free tier users.",
                "The neural network training uses cosine similarity as the loss function.",
                "Deployment uses Kubernetes with 3 replicas and auto-scaling enabled.",
                "Bug fix: resolved memory leak in the vector quantization pipeline last sprint."
        };

        System.out.printf("  Ingesting %d memories via Ollama...%n", memories.length);
        long ingestStart = System.nanoTime();
        for (int i = 0; i < memories.length; i++) {
            memory.remember("mem-" + i, memories[i], MemoryType.SEMANTIC,
                    MemorySource.USER_STATED, "benchmark").join();
        }
        long ingestElapsed = System.nanoTime() - ingestStart;
        System.out.printf("  Ingestion: %d memories in %.1fs (%.0fms/memory, Ollama embedding included)%n",
                memories.length, ingestElapsed / 1e9, ingestElapsed / 1e6 / memories.length);

        // Recall queries
        String[] queries = {
                "What color theme does the user prefer?",
                "What programming language is used?",
                "When is the next meeting?",
                "How is the deployment configured?",
                "What database was migrated?"
        };

        System.out.println("  Recall latencies (includes Ollama embedding):");
        long[] recallNanos = new long[queries.length];
        for (int i = 0; i < queries.length; i++) {
            long t0 = System.nanoTime();
            List<CognitiveResult> results = memory.recall(queries[i]);
            recallNanos[i] = System.nanoTime() - t0;
            String topMatch = results.isEmpty() ? "none" : results.getFirst().id();
            System.out.printf("    Q: \"%s\"%n      → %s (%.1fms, %d results)%n",
                    queries[i], topMatch, recallNanos[i] / 1e6, results.size());
        }

        // Measure repeated recall (shows consistency)
        System.out.println("  Recall consistency (5 rounds):");
        for (int r = 0; r < 5; r++) {
            long t0 = System.nanoTime();
            memory.recall("What color theme?");
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.printf("    Round %d: %dms%n", r + 1, ms);
        }
        System.out.println();

        memory.close();
        deleteDirectory(memDir);

        double avgRecallMs = 0;
        for (long n : recallNanos) avgRecallMs += n / 1e6;
        avgRecallMs /= recallNanos.length;

        verdicts.add(new String[]{"D3: Cognitive recall (Ollama)",
                String.format("avg=%.0fms (embed+score)", avgRecallMs),
                "✅ MEASURED"});
    }

    // ═══════════════════════════════════════════════════════════════
    //  D4: WAL — append throughput and replay speed
    // ═══════════════════════════════════════════════════════════════

    private void runD4_WalThroughput() throws Exception {
        System.out.println("▶ D4: WAL Append + Replay Throughput (file-backed, fsync'd)");

        Path walDir = Files.createTempDirectory("spector-wal-bench");
        int eventCount = 50_000;

        // Test 1: WAL with fsync per write (durability guarantee)
        try (MemoryWal walSync = new MemoryWal(walDir.resolve("fsync"),
                8L * 1024 * 1024, false, 1024, true)) {

            long t0 = System.nanoTime();
            for (int i = 0; i < eventCount; i++) {
                walSync.appendRemember("mem-" + i, ("content-" + i).getBytes());
            }
            long appendSyncNanos = System.nanoTime() - t0;

            double syncOpsPerSec = eventCount / (appendSyncNanos / 1e9);
            double syncLatencyUs = appendSyncNanos / (double) eventCount / 1000.0;

            System.out.printf("  fsync WAL:    %,d appends in %.1fs  (%.0f ops/s, %.0fµs/op)%n",
                    eventCount, appendSyncNanos / 1e9, syncOpsPerSec, syncLatencyUs);

            // Replay from disk
            long replayStart = System.nanoTime();
            var replayed = walSync.replayFromDisk();
            long replayNanos = System.nanoTime() - replayStart;
            System.out.printf("  fsync replay: %,d events in %.1fms (%.0f events/s)%n",
                    replayed.size(), replayNanos / 1e6, replayed.size() / (replayNanos / 1e9));

            verdicts.add(new String[]{"D4a: WAL fsync append",
                    String.format("%.0f ops/s, %.0fµs/op", syncOpsPerSec, syncLatencyUs),
                    "✅ MEASURED"});
        }

        // Test 2: WAL without fsync (buffered — much faster)
        try (MemoryWal walBuf = new MemoryWal(walDir.resolve("buffered"),
                8L * 1024 * 1024, false, 1024, false)) {

            long t0 = System.nanoTime();
            for (int i = 0; i < eventCount; i++) {
                walBuf.appendRemember("mem-" + i, ("content-" + i).getBytes());
            }
            long appendBufNanos = System.nanoTime() - t0;

            double bufOpsPerSec = eventCount / (appendBufNanos / 1e9);
            double bufLatencyUs = appendBufNanos / (double) eventCount / 1000.0;

            System.out.printf("  buffered WAL: %,d appends in %.1fms (%.0f ops/s, %.1fµs/op)%n",
                    eventCount, appendBufNanos / 1e6, bufOpsPerSec, bufLatencyUs);

            verdicts.add(new String[]{"D4b: WAL buffered append",
                    String.format("%.0f ops/s, %.1fµs/op", bufOpsPerSec, bufLatencyUs),
                    "✅ MEASURED"});
        }

        // Test 3: Concurrent WAL writes (simulating multi-agent scenario)
        try (MemoryWal walConc = new MemoryWal(walDir.resolve("concurrent"),
                8L * 1024 * 1024, false, 1024, false)) {

            int threads = 8;
            int opsPerThread = 10_000;
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            AtomicLong totalOps = new AtomicLong();

            long wallStart = System.nanoTime();
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int tid = t;
                futures.add(executor.submit(() -> {
                    for (int i = 0; i < opsPerThread; i++) {
                        walConc.appendRemember("t" + tid + "-mem-" + i,
                                ("concurrent-" + tid + "-" + i).getBytes());
                        totalOps.incrementAndGet();
                    }
                }));
            }
            for (var f : futures) f.get();
            long wallElapsed = System.nanoTime() - wallStart;
            executor.shutdown();

            double concOpsPerSec = totalOps.get() / (wallElapsed / 1e9);
            System.out.printf("  concurrent:   %d threads × %,d ops = %,.0f ops/s%n",
                    threads, opsPerThread, concOpsPerSec);

            verdicts.add(new String[]{"D4c: WAL concurrent writes",
                    String.format("%,.0f ops/s (%d threads)", concOpsPerSec, threads),
                    "✅ MEASURED"});
        }

        System.out.println();
        deleteDirectory(walDir);
    }

    // ═══════════════════════════════════════════════════════════════
    //  D5: Full Memory Pipeline — ingest → recall → reinforce → reflect
    // ═══════════════════════════════════════════════════════════════

    private void runD5_FullMemoryPipeline(OllamaEmbeddingProvider embedder) throws Exception {
        System.out.println("▶ D5: Full Cognitive Pipeline (remember → recall → reinforce → reflect)");

        Path memDir = Files.createTempDirectory("spector-pipeline-bench");

        SpectorMemory memory = DefaultSpectorMemory.builder()
                .dimensions(DIMS)
                .embeddingProvider(embedder)
                .persistence(memDir)
                .persistenceMode(MemoryPersistenceMode.DISK)
                .semanticCapacity(10_000)
                .build();

        // Phase 1: Remember
        String[] texts = {
                "Implemented SIMD-accelerated cosine similarity using Java Vector API with AVX-512.",
                "The Panama FFI provides zero-copy access to native memory segments without JNI overhead.",
                "HNSW graph construction uses M=16, efConstruction=200 for 95%+ recall at 10K scale.",
                "Write-Ahead Log uses append-only binary format with CRC32 checksums for crash recovery.",
                "Cognitive memory scoring fuses similarity, importance, decay, and valence in one SIMD pass."
        };

        System.out.printf("  Phase 1: Remember (%d memories)...%n", texts.length);
        long rememberStart = System.nanoTime();
        for (int i = 0; i < texts.length; i++) {
            memory.remember("pipeline-" + i, texts[i], MemoryType.SEMANTIC,
                    MemorySource.OBSERVED, "pipeline", "benchmark").join();
        }
        long rememberMs = (System.nanoTime() - rememberStart) / 1_000_000;
        System.out.printf("    Done: %dms total (%.0fms/memory)%n", rememberMs, (double) rememberMs / texts.length);

        // Phase 2: Recall
        System.out.println("  Phase 2: Recall...");
        long recallStart = System.nanoTime();
        List<CognitiveResult> results = memory.recall("What is the HNSW configuration?");
        long recallMs = (System.nanoTime() - recallStart) / 1_000_000;
        System.out.printf("    Recall: %dms, %d results%n", recallMs, results.size());
        if (!results.isEmpty()) {
            System.out.printf("    Top: %s (score=%.3f)%n", results.getFirst().id(),
                    results.getFirst().score());
        }

        // Phase 3: Reinforce
        System.out.println("  Phase 3: Reinforce...");
        if (!results.isEmpty()) {
            long reinforceStart = System.nanoTime();
            memory.reinforce(results.getFirst().id(), (byte) 64);
            long reinforceUs = (System.nanoTime() - reinforceStart) / 1000;
            System.out.printf("    Reinforced '%s' in %dµs%n", results.getFirst().id(), reinforceUs);
        }

        // Phase 4: Reflect (sleep consolidation)
        System.out.println("  Phase 4: Reflect (sleep consolidation)...");
        long reflectStart = System.nanoTime();
        ReflectReport report = memory.reflect();
        long reflectMs = (System.nanoTime() - reflectStart) / 1_000_000;
        System.out.printf("    Reflect: %dms (promoted=%d, pruned=%d)%n",
                reflectMs, report.consolidatedCount(), report.tombstonedCount());

        // Phase 5: Stats
        System.out.println("  Phase 5: Final stats...");
        System.out.printf("    Total memories: %d%n", memory.totalMemories());
        System.out.printf("    Working:  %d%n", memory.memoryCount(MemoryType.WORKING));
        System.out.printf("    Episodic: %d%n", memory.memoryCount(MemoryType.EPISODIC));
        System.out.printf("    Semantic: %d%n", memory.memoryCount(MemoryType.SEMANTIC));
        System.out.printf("    Procedural: %d%n", memory.memoryCount(MemoryType.PROCEDURAL));
        System.out.println();

        memory.close();
        deleteDirectory(memDir);

        verdicts.add(new String[]{"D5: Full pipeline cycle",
                String.format("remember=%dms, recall=%dms, reflect=%dms", rememberMs, recallMs, reflectMs),
                "✅ MEASURED"});
    }

    // ═══════════════════════════════════════════════════════════════
    //  Results & Report
    // ═══════════════════════════════════════════════════════════════

    private void printVerdictTable() {
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("             DISK PERSISTENCE BENCHMARK REPORT                ");
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.printf("  %-38s %-35s %-15s%n", "TEST", "RESULT", "VERDICT");
        System.out.println("  " + "─".repeat(88));
        for (var v : verdicts) {
            System.out.printf("  %-38s %-35s %-15s%n", v[0], v[1], v[2]);
        }
        System.out.println("═══════════════════════════════════════════════════════════════");
    }

    private void writeReport() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Spector — Disk Persistence Benchmark Report\n\n");
        sb.append("**Generated:** ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n\n");

        sb.append("## System\n\n");
        sb.append("| Property | Value |\n|---|---|\n");
        sb.append("| CPU | ").append(getCpuModel()).append(" |\n");
        sb.append("| Java | ").append(System.getProperty("java.version")).append(" |\n");
        sb.append("| SIMD | ").append(SimdCapability.report()).append(" |\n");
        sb.append("| Embedding | ").append(EMBEDDING_MODEL).append(" (Ollama, localhost) |\n\n");

        sb.append("## Results\n\n");
        sb.append("| Test | Result | Verdict |\n|---|---|---|\n");
        for (var v : verdicts) {
            sb.append("| ").append(v[0]).append(" | ").append(v[1]).append(" | ").append(v[2]).append(" |\n");
        }

        Path reportPath = Path.of("spector-bench", "target", "disk-persistence-report.md");
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, sb.toString());
        System.out.printf("%nReport saved: %s%n", reportPath.toAbsolutePath());
    }

    // ─── System Info ───

    private void printSystemInfo() {
        System.out.printf("  OS:    %s %s%n", System.getProperty("os.name"), System.getProperty("os.arch"));
        System.out.printf("  Java:  %s%n", System.getProperty("java.version"));
        System.out.printf("  CPU:   %s (%d cores)%n", getCpuModel(), Runtime.getRuntime().availableProcessors());
        System.out.printf("  Heap:  %d MB%n", Runtime.getRuntime().maxMemory() / (1024 * 1024));
        System.out.printf("  SIMD:  %s%n", SimdCapability.report());
        System.out.printf("  Time:  %s%n", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
    }

    private static String getCpuModel() {
        try {
            Process p = new ProcessBuilder("powershell", "-Command",
                    "(Get-CimInstance Win32_Processor).Name").start();
            String result = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            if (!result.isBlank()) return result;
        } catch (Exception ignored) {}
        return System.getProperty("os.arch");
    }

    // ─── Helpers ───

    private static float[][] generateClusteredVectors(int count, int dims, Random rng) {
        int clusters = 50;
        float[][] centers = new float[clusters][dims];
        for (int c = 0; c < clusters; c++) {
            for (int d = 0; d < dims; d++) centers[c][d] = (float) rng.nextGaussian() * 0.5f;
            normalize(centers[c]);
        }
        float[][] vectors = new float[count][dims];
        for (int i = 0; i < count; i++) {
            int cluster = rng.nextInt(clusters);
            for (int d = 0; d < dims; d++) vectors[i][d] = centers[cluster][d] + (float) rng.nextGaussian() * 0.15f;
            normalize(vectors[i]);
        }
        return vectors;
    }

    private static float[] perturbVector(float[] base, float noise, int dims, Random rng) {
        float[] result = new float[dims];
        for (int d = 0; d < dims; d++) result[d] = base[d] + (float) rng.nextGaussian() * noise;
        normalize(result);
        return result;
    }

    private static void normalize(float[] v) {
        float norm = 0;
        for (float f : v) norm += f * f;
        norm = (float) Math.sqrt(norm);
        if (norm > 1e-10f) for (int i = 0; i < v.length; i++) v[i] /= norm;
    }

    private static void deleteDirectory(Path path) throws IOException {
        if (Files.exists(path)) {
            try (var stream = Files.walk(path)) {
                stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.delete(p); } catch (IOException ignored) {}
                });
            }
        }
    }

    record Stats(double min, double max, double mean, double p50, double p95, double p99) {}

    private Stats computeStats(long[] nanos) {
        Arrays.sort(nanos);
        int n = nanos.length;
        double sum = 0;
        for (long v : nanos) sum += v;
        return new Stats(nanos[0], nanos[n - 1], sum / n,
                nanos[(int) (n * 0.50)], nanos[(int) (n * 0.95)], nanos[(int) (n * 0.99)]);
    }
}
