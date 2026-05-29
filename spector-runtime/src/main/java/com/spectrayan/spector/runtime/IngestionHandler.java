package com.spectrayan.spector.runtime;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.commons.concurrent.ConcurrentTasks;

import com.spectrayan.spector.config.SpectorMode;
import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.ingestion.FileDiscoveryService;
import com.spectrayan.spector.ingestion.IngestionPipeline;
import com.spectrayan.spector.ingestion.IngestionResult;
import com.spectrayan.spector.memory.SpectorMemory;

/**
 * Mode-aware ingestion service — thin routing layer over the unified {@link IngestionPipeline}.
 *
 * <p>Handles all ingestion variants — raw text, single file, directory scan —
 * by delegating to a pre-configured {@link IngestionPipeline} that knows
 * how to chunk, embed, and store data for the active mode (search or memory).</p>
 *
 * <p>The pipeline is built by {@link SpectorRuntime} with the appropriate
 * {@link com.spectrayan.spector.ingestion.IngestionTarget} (engine or cognitive)
 * and chunking configuration from {@code spector.yml}.</p>
 *
 * <p>Obtained via {@code runtime.ingestion()}. Not instantiated directly.</p>
 */
public final class IngestionHandler {

    private static final Logger log = LoggerFactory.getLogger(IngestionHandler.class);

    private final IngestionPipeline pipeline;
    private final SpectorEngine engine;   // for count() and backward-compat
    private final SpectorMemory memory;   // for count() (nullable)
    private final SpectorMode mode;

    IngestionHandler(IngestionPipeline pipeline, SpectorEngine engine,
                     SpectorMemory memory, SpectorMode mode) {
        this.pipeline = pipeline;
        this.engine = engine;
        this.memory = memory;
        this.mode = mode;
    }

    // ─────────────── Text Ingestion ───────────────

    /**
     * Ingests raw text content. The pipeline handles chunking and embedding.
     *
     * @param id   document/memory ID
     * @param text content text
     */
    public void ingest(String id, String text) {
        pipeline.ingest(id, text);
    }

    /**
     * Ingests a long text by auto-chunking via the pipeline.
     *
     * <p>The pipeline decides whether to chunk based on content length
     * and its configured chunk threshold.</p>
     *
     * @param id      document ID
     * @param content full document content
     * @return ingestion result
     */
    public IngestionResult ingestChunked(String id, String content) {
        return pipeline.ingest(id, content);
    }

    // ─────────────── File Ingestion ───────────────

    /**
     * Ingests a single file. Reads content and delegates to the pipeline.
     *
     * @param file      path to the file
     * @param chunkSize chunk size in characters (used for title extraction threshold)
     * @return ingestion result
     */
    public IngestionResult ingest(Path file, int chunkSize) {
        try {
            String content = Files.readString(file);
            if (content.isBlank()) {
                return IngestionResult.single(file.toString(), 0);
            }

            String id = file.getFileName().toString();
            return pipeline.ingest(id, content);
        } catch (Exception e) {
            log.error("Failed to ingest file '{}': {}", file, e.getMessage());
            return IngestionResult.chunked(file.toString(), 0,
                    List.of(file.toString()), 0);
        }
    }

    /**
     * Discovers and ingests files from a directory.
     *
     * @param rootDir     root directory to scan
     * @param filePattern glob pattern (e.g., {@code "**\/*.md"})
     * @param chunkSize   chunk size in characters
     * @param chunkOverlap overlap between chunks
     * @param skipDirs    directories to skip (e.g., {@code ".git,.idea"})
     * @return list of ingestion results (one per file)
     */
    public List<IngestionResult> ingest(Path rootDir, String filePattern,
                                         int chunkSize, int chunkOverlap, String skipDirs) {
        return ingest(rootDir, filePattern, chunkSize, chunkOverlap, skipDirs, null);
    }

    /**
     * Discovers and ingests files from a directory with progress reporting.
     */
    public List<IngestionResult> ingest(Path rootDir, String filePattern,
                                         int chunkSize, int chunkOverlap, String skipDirs,
                                         IngestionProgress progress) {
        return ingest(rootDir, filePattern, chunkSize, chunkOverlap, skipDirs,
                progress, 4, 3, 2000);
    }

    /**
     * Discovers and ingests files from a directory with full configuration.
     * Uses virtual threads for parallelism and retry with exponential backoff.
     */
    public List<IngestionResult> ingest(Path rootDir, String filePattern,
                                         int chunkSize, int chunkOverlap, String skipDirs,
                                         IngestionProgress progress,
                                         int parallelism, int maxRetries, int retryDelayMs) {
        var discovery = FileDiscoveryService.builder()
                .rootDirectory(rootDir)
                .filePattern(filePattern)
                .chunkSize(chunkSize)
                .chunkOverlap(chunkOverlap)
                .skipDirs(skipDirs.split(","))
                .build();
        List<Path> files;
        try {
            files = discovery.discover();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to discover files in " + rootDir, e);
        }

        int totalFiles = files.size();
        log.info("[Ingestion] Discovered {} files in {} (pattern: {}, parallelism: {})",
                totalFiles, rootDir, filePattern, parallelism);

        // Semaphore bounds concurrency — ConcurrentTasks launches all tasks,
        // but only `parallelism` file ingestions run at a time
        var semaphore = new Semaphore(parallelism);
        var completedCount = new AtomicInteger(0);
        List<ConcurrentTasks.LabeledTask<IngestionResult>> tasks = new ArrayList<>(totalFiles);
        for (int fi = 0; fi < files.size(); fi++) {
            Path file = files.get(fi);
            final int fileIndex = fi + 1; // 1-based for display
            String relativePath = rootDir.relativize(file).toString().replace('\\', '/');
            tasks.add(new ConcurrentTasks.LabeledTask<>(relativePath, () -> {
                semaphore.acquire();
                try {
                    return ingestFileWithRetry(file, rootDir, chunkSize, maxRetries, retryDelayMs,
                            completedCount, totalFiles, progress, fileIndex);
                } finally {
                    semaphore.release();
                }
            }));
        }

        // Execute via ConcurrentTasks
        Duration timeout = Duration.ofMinutes(Math.max(totalFiles * 2L, 30));
        ConcurrentTasks.PartialResult<IngestionResult> partial;
        try {
            partial = ConcurrentTasks.forkJoinPartial(tasks, timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Ingestion interrupted");
            return List.of();
        }

        // Collect results
        var results = new ArrayList<IngestionResult>(totalFiles);
        for (var entry : partial.successes()) {
            results.add(entry.result());
        }
        for (String timedOut : partial.timedOut()) {
            log.warn("[Ingestion] Timed out: {}", timedOut);
            if (progress != null) {
                progress.onFile(completedCount.incrementAndGet(), totalFiles,
                        timedOut, 0, -1, "Timed out");
            }
            results.add(IngestionResult.chunked(timedOut, 0, List.of(timedOut), -1));
        }
        for (var failure : partial.failures()) {
            log.error("[Ingestion] Failed: {} — {}", failure.label(), failure.cause().getMessage());
            if (progress != null) {
                progress.onFile(completedCount.incrementAndGet(), totalFiles,
                        failure.label(), 0, -1, failure.cause().getMessage());
            }
            results.add(IngestionResult.chunked(failure.label(), 0,
                    List.of(failure.label()), -1));
        }

        return results;
    }

    /**
     * Ingests a single file with retry logic (exponential backoff).
     */
    private IngestionResult ingestFileWithRetry(Path file, Path rootDir, int chunkSize,
                                                 int maxRetries, int retryDelayMs,
                                                 AtomicInteger completedCount, int totalFiles,
                                                 IngestionProgress progress, int fileIndex) {
        String relativePath = rootDir.relativize(file).toString().replace('\\', '/');
        long fileStart = System.currentTimeMillis();

        if (progress != null) {
            progress.onFileStart(fileIndex, totalFiles, relativePath);
        }

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                // Check file size to decide strategy:
                // - Small files (≤ 2× chunkSize bytes): read into memory
                // - Large files: use streaming pipeline to avoid heap exhaustion
                long fileSize = Files.size(file);
                IngestionResult result;

                if (fileSize <= chunkSize * 2L) {
                    // Small file — safe to read fully into memory
                    String content = Files.readString(file);
                    if (content.isBlank()) {
                        int idx = completedCount.incrementAndGet();
                        if (progress != null) {
                            progress.onFile(idx, totalFiles, relativePath, 0,
                                    System.currentTimeMillis() - fileStart, null);
                        }
                        return IngestionResult.single(relativePath, 0);
                    }
                    result = pipeline.ingest(relativePath, content);
                } else {
                    // Large file — stream chunk-by-chunk (bounded memory)
                    result = pipeline.ingest(file, relativePath);
                }

                long elapsed = System.currentTimeMillis() - fileStart;
                int idx = completedCount.incrementAndGet();
                log.info("  [{}] {} chunks, {}ms", relativePath, result.chunksStored(), elapsed);
                if (progress != null) {
                    progress.onFile(idx, totalFiles, relativePath,
                            result.chunksStored(), elapsed, null);
                }
                return result;

            } catch (Exception e) {
                if (attempt < maxRetries) {
                    long delay = (long) retryDelayMs * (1L << (attempt - 1));
                    log.warn("  [{}] attempt {}/{} failed: {} — retrying in {}ms",
                            relativePath, attempt, maxRetries, e.getMessage(), delay);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    long elapsed = System.currentTimeMillis() - fileStart;
                    int idx = completedCount.incrementAndGet();
                    log.error("  [{}] all {} attempts failed: {}",
                            relativePath, maxRetries, e.getMessage());
                    if (progress != null) {
                        progress.onFile(idx, totalFiles, relativePath, 0, elapsed, e.getMessage());
                    }
                    return IngestionResult.chunked(relativePath, 0,
                            List.of(relativePath), elapsed);
                }
            }
        }

        // Safety net
        long elapsed = System.currentTimeMillis() - fileStart;
        return IngestionResult.chunked(relativePath, 0,
                List.of(relativePath), elapsed);
    }

    /**
     * Progress callback for directory ingestion.
     */
    public interface IngestionProgress {

        /** Called when a file starts processing (before embedding). */
        default void onFileStart(int fileIndex, int totalFiles, String relativePath) {}

        /** Called when a file finishes processing (success or failure). */
        void onFile(int fileIndex, int totalFiles, String relativePath,
                    int chunks, long elapsedMs, String error);
    }

    // ─────────────── Count ───────────────

    /**
     * Returns the total number of indexed documents/memories.
     */
    public int count() {
        if (mode == SpectorMode.MEMORY && memory != null) {
            return memory.totalMemories();
        }
        return engine.documentCount();
    }
}
