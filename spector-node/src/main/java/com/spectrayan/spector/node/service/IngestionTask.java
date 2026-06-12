package com.spectrayan.spector.node.service;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks an in-flight async ingestion operation (remember, file ingest, or directory ingest).
 *
 * <p>Mutable counters ({@code chunksStored}, {@code totalChunks}, {@code failures})
 * are updated by the worker thread and read by the SSE publisher. Thread-safe
 * via {@link AtomicInteger}.</p>
 */
public final class IngestionTask {

    public enum TaskType { REMEMBER, FILE_INGEST, DIR_INGEST }
    public enum TaskStatus { ACCEPTED, RUNNING, COMPLETED, FAILED }

    private final String taskId;
    private final String description;
    private final TaskType type;
    private final Instant startedAt;
    private final AtomicInteger chunksStored = new AtomicInteger(0);
    private final AtomicInteger totalChunks = new AtomicInteger(0);
    private final AtomicInteger failures = new AtomicInteger(0);
    private volatile TaskStatus status = TaskStatus.ACCEPTED;
    private volatile String errorMessage;
    private volatile Instant completedAt;

    public IngestionTask(String taskId, String description, TaskType type) {
        this.taskId = taskId;
        this.description = description;
        this.type = type;
        this.startedAt = Instant.now();
    }

    // ── Getters ──
    public String taskId() { return taskId; }
    public String description() { return description; }
    public TaskType type() { return type; }
    public Instant startedAt() { return startedAt; }
    public int chunksStored() { return chunksStored.get(); }
    public int totalChunks() { return totalChunks.get(); }
    public int failures() { return failures.get(); }
    public TaskStatus status() { return status; }
    public String errorMessage() { return errorMessage; }
    public Instant completedAt() { return completedAt; }

    // ── Mutators (called by worker thread) ──
    public void setRunning() { this.status = TaskStatus.RUNNING; }
    public void setTotalChunks(int total) { this.totalChunks.set(total); }
    public int incrementStored() { return this.chunksStored.incrementAndGet(); }
    public int incrementFailures() { return this.failures.incrementAndGet(); }

    public void setCompleted() {
        this.status = TaskStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    public void setFailed(String error) {
        this.status = TaskStatus.FAILED;
        this.errorMessage = error;
        this.completedAt = Instant.now();
    }

    /** Returns progress as 0.0–100.0, or -1 if total is unknown. */
    public double progressPercent() {
        int total = totalChunks.get();
        if (total <= 0) return -1;
        return Math.min(100.0, (chunksStored.get() * 100.0) / total);
    }

    /** Duration in milliseconds from start to now (or to completion). */
    public long durationMs() {
        Instant end = completedAt != null ? completedAt : Instant.now();
        return end.toEpochMilli() - startedAt.toEpochMilli();
    }

    public boolean isTerminal() {
        return status == TaskStatus.COMPLETED || status == TaskStatus.FAILED;
    }
}
