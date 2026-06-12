package com.spectrayan.spector.node.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.commons.concurrent.ConcurrentTasks;
import com.spectrayan.spector.node.event.SpectorEventBus;
import com.spectrayan.spector.node.event.SpectorIngestionCompletedEvent;
import com.spectrayan.spector.node.event.SpectorIngestionProgressEvent;

/**
 * Manages async ingestion tasks — submit, track progress, publish SSE events.
 *
 * <p>Tasks execute on virtual threads via {@link ConcurrentTasks}. Progress
 * events are throttled (every 5 chunks or when completed) to avoid flooding
 * the SSE event bus.</p>
 *
 * <p>Keeps recent completed tasks for 5 minutes so the UI can fetch them
 * on page load via the {@code GET /memory/tasks} endpoint.</p>
 */
public class IngestionTaskService {

    private static final Logger log = LoggerFactory.getLogger(IngestionTaskService.class);

    /** Publish progress every N chunks. */
    private static final int PROGRESS_INTERVAL = 5;

    private final Map<String, IngestionTask> tasks = new ConcurrentHashMap<>();
    private final SpectorEventBus eventBus;
    private final String nodeId;

    public IngestionTaskService(SpectorEventBus eventBus, String nodeId) {
        this.eventBus = eventBus;
        this.nodeId = nodeId;
    }

    /**
     * Submits an async task that runs the given work on a virtual thread.
     *
     * <p>The {@code work} supplier receives an {@link IngestionTask} which it should
     * update as progress is made (setRunning, setTotalChunks, incrementStored, etc.).</p>
     *
     * @param task the task tracker (pre-created with ID and description)
     * @param work the actual work to execute (remember/ingest logic)
     */
    public void submit(IngestionTask task, Runnable work) {
        tasks.put(task.taskId(), task);
        log.info("Task submitted: {} ({})", task.taskId(), task.description());

        ConcurrentTasks.fireAndForget(() -> {
            try {
                task.setRunning();
                publishProgress(task);

                work.run();

                task.setCompleted();
                publishCompletion(task);
                log.info("Task completed: {} — {} chunks, {} failures, {}ms",
                        task.taskId(), task.chunksStored(), task.failures(), task.durationMs());
            } catch (Exception e) {
                task.setFailed(e.getMessage());
                publishCompletion(task);
                log.error("Task failed: {} — {}", task.taskId(), e.getMessage(), e);
            } finally {
                scheduleCleanup(task.taskId());
            }
        });
    }

    /**
     * Called by the worker to report chunk-level progress.
     * Publishes an SSE event every {@code PROGRESS_INTERVAL} chunks.
     */
    public void reportChunkStored(IngestionTask task) {
        int stored = task.incrementStored();
        if (stored % PROGRESS_INTERVAL == 0 || stored == task.totalChunks()) {
            publishProgress(task);
        }
    }

    /** Returns a task by ID, or null if not found. */
    public IngestionTask getTask(String taskId) {
        return tasks.get(taskId);
    }

    /** Returns all active (non-terminal) tasks. */
    public List<IngestionTask> getActiveTasks() {
        return tasks.values().stream()
                .filter(t -> !t.isTerminal())
                .toList();
    }

    /** Returns all tasks (including recently completed). */
    public List<IngestionTask> getAllTasks() {
        return List.copyOf(tasks.values());
    }

    // ── SSE Publishing ──────────────────────────────────────────────

    private void publishProgress(IngestionTask task) {
        eventBus.publish(new SpectorIngestionProgressEvent(
                nodeId, Instant.now(),
                task.taskId(), task.description(),
                task.chunksStored(), task.totalChunks(),
                task.failures(), task.progressPercent()));
    }

    private void publishCompletion(IngestionTask task) {
        eventBus.publish(new SpectorIngestionCompletedEvent(
                nodeId, Instant.now(),
                task.taskId(), task.description(),
                task.chunksStored(), task.failures(),
                task.durationMs(),
                task.status() == IngestionTask.TaskStatus.COMPLETED));
    }

    /** Removes finished tasks after 5 minutes. */
    private void scheduleCleanup(String taskId) {
        ConcurrentTasks.fireAndForget(() -> {
            try {
                Thread.sleep(5 * 60 * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            tasks.remove(taskId);
            log.debug("Cleaned up task: {}", taskId);
        });
    }
}
