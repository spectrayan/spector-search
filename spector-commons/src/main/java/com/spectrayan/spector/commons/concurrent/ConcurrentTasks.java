package com.spectrayan.spector.commons.concurrent;

import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Subtask;
import java.util.stream.Stream;

/**
 * Centralized concurrency utilities for Spector Search.
 *
 * <p>Provides a dual-mode execution model controlled by a feature flag:
 * <ul>
 *   <li><b>Structured mode</b> (default): Uses {@link StructuredTaskScope}
 *       from JEP 505 for automatic cancellation propagation and thread-leak prevention.</li>
 *   <li><b>Classic mode</b> (fallback): Uses {@link ExecutorService} with virtual threads,
 *       matching the original behavior.</li>
 * </ul>
 *
 * <h3>Feature Flag</h3>
 * <p>Set {@code -Dspector.concurrency.structured=false} to disable structured concurrency
 * and fall back to the classic {@link ExecutorService} path. By default, structured
 * concurrency is enabled.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // Fan out N tasks — all must succeed
 * List<Result> results = ConcurrentTasks.forkJoinAll(List.of(
 *     () -> keywordSearch(query),
 *     () -> vectorSearch(query)
 * ));
 *
 * // Fan out with deadline — partial results accepted
 * var partial = ConcurrentTasks.forkJoinPartial(List.of(
 *     new LabeledTask<>("shard-1", () -> searchShard(s1)),
 *     new LabeledTask<>("shard-2", () -> searchShard(s2))
 * ), Duration.ofSeconds(10));
 * }</pre>
 *
 * @see StructuredTaskScope
 */
public final class ConcurrentTasks {

    private static final System.Logger log = System.getLogger(ConcurrentTasks.class.getName());

    /**
     * Feature flag: set to {@code false} to disable structured concurrency.
     * Defaults to {@code true}.
     */
    private static final boolean STRUCTURED_ENABLED =
            Boolean.parseBoolean(System.getProperty("spector.concurrency.structured", "true"));

    /**
     * Whether structured concurrency is actually available at runtime.
     * Checks both the feature flag AND JDK support.
     */
    private static final boolean STRUCTURED_AVAILABLE;

    static {
        boolean available = false;
        if (STRUCTURED_ENABLED) {
            try {
                Class.forName("java.util.concurrent.StructuredTaskScope");
                available = true;
            } catch (ClassNotFoundException e) {
                log.log(System.Logger.Level.INFO,
                        "StructuredTaskScope not available, falling back to ExecutorService");
            }
        } else {
            log.log(System.Logger.Level.INFO,
                    "Structured concurrency disabled via spector.concurrency.structured=false");
        }
        STRUCTURED_AVAILABLE = available;
    }

    private ConcurrentTasks() {}

    /**
     * Returns whether structured concurrency is active.
     *
     * @return true if using {@link StructuredTaskScope}, false if using {@link ExecutorService}
     */
    public static boolean isStructuredConcurrencyEnabled() {
        return STRUCTURED_AVAILABLE;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Fork-Join All: all tasks must succeed (fail-fast with auto-cancel)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Forks all tasks concurrently and joins them. All must succeed.
     *
     * <p>In structured mode, if any task fails, all siblings are automatically cancelled
     * and a {@link ConcurrentExecutionException} is thrown.</p>
     *
     * <p>In classic mode, if any task fails, remaining futures are cancelled manually.</p>
     *
     * @param tasks the tasks to execute concurrently
     * @param <T>   result type
     * @return list of results in submission order
     * @throws ConcurrentExecutionException if any task fails
     * @throws InterruptedException         if the calling thread is interrupted
     */
    public static <T> List<T> forkJoinAll(List<Callable<T>> tasks)
            throws ConcurrentExecutionException, InterruptedException {
        if (tasks.isEmpty()) return List.of();
        if (tasks.size() == 1) {
            try {
                return List.of(tasks.getFirst().call());
            } catch (Exception e) {
                throw new ConcurrentExecutionException("Single task failed", e);
            }
        }

        return STRUCTURED_AVAILABLE
                ? forkJoinAllStructured(tasks)
                : forkJoinAllClassic(tasks);
    }

    /**
     * Convenience overload for exactly two tasks of the same type.
     *
     * @return a two-element list [resultA, resultB]
     */
    public static <T> List<T> forkJoinAll(Callable<T> taskA, Callable<T> taskB)
            throws ConcurrentExecutionException, InterruptedException {
        return forkJoinAll(List.of(taskA, taskB));
    }

    /**
     * Optimized two-task fork-join for heterogeneous result types.
     *
     * <p>Avoids all list allocations — forks exactly two tasks and returns
     * a typed pair. This is the hot-path specialization for
     * {@code HybridSearchOrchestrator} (keyword ∥ vector).</p>
     *
     * @param taskA first task
     * @param taskB second task
     * @param <A>   result type of first task
     * @param <B>   result type of second task
     * @return a {@link Pair} containing both results
     * @throws ConcurrentExecutionException if either task fails
     * @throws InterruptedException         if the calling thread is interrupted
     */
    public static <A, B> Pair<A, B> forkJoin2(Callable<A> taskA, Callable<B> taskB)
            throws ConcurrentExecutionException, InterruptedException {
        return STRUCTURED_AVAILABLE
                ? forkJoin2Structured(taskA, taskB)
                : forkJoin2Classic(taskA, taskB);
    }

    // ── Structured implementation ───────────────────────────────────────

    @SuppressWarnings("preview")
    private static <T> List<T> forkJoinAllStructured(List<Callable<T>> tasks)
            throws ConcurrentExecutionException, InterruptedException {
        try (var scope = StructuredTaskScope.open(
                StructuredTaskScope.Joiner.<T>awaitAllSuccessfulOrThrow())) {
            List<Subtask<T>> subtasks = new ArrayList<>(tasks.size());
            for (Callable<T> task : tasks) {
                subtasks.add(scope.fork(task::call));
            }
            scope.join(); // auto-cancels siblings on first failure

            // Direct loop — avoids Stream/Iterator/intermediate list allocation
            List<T> results = new ArrayList<>(subtasks.size());
            for (Subtask<T> st : subtasks) {
                results.add(st.get());
            }
            return results;
        } catch (StructuredTaskScope.FailedException e) {
            throw new ConcurrentExecutionException("Structured fork-join failed", e.getCause());
        }
    }

    @SuppressWarnings({"preview", "unchecked"})
    private static <A, B> Pair<A, B> forkJoin2Structured(Callable<A> taskA, Callable<B> taskB)
            throws ConcurrentExecutionException, InterruptedException {
        try (var scope = StructuredTaskScope.open(
                StructuredTaskScope.Joiner.awaitAllSuccessfulOrThrow())) {
            Subtask<A> a = scope.fork(taskA::call);
            Subtask<B> b = scope.fork(taskB::call);
            scope.join();
            return new Pair<>(a.get(), b.get());
        } catch (StructuredTaskScope.FailedException e) {
            throw new ConcurrentExecutionException("Structured fork-join failed", e.getCause());
        }
    }

    private static <A, B> Pair<A, B> forkJoin2Classic(Callable<A> taskA, Callable<B> taskB)
            throws ConcurrentExecutionException, InterruptedException {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<A> futureA = executor.submit(taskA);
            Future<B> futureB = executor.submit(taskB);
            try {
                return new Pair<>(futureA.get(), futureB.get());
            } catch (java.util.concurrent.ExecutionException e) {
                futureA.cancel(true);
                futureB.cancel(true);
                throw new ConcurrentExecutionException("Task failed", e.getCause());
            }
        }
    }

    // ── Classic (ExecutorService) implementation ─────────────────────────

    private static <T> List<T> forkJoinAllClassic(List<Callable<T>> tasks)
            throws ConcurrentExecutionException, InterruptedException {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<T>> futures = new ArrayList<>(tasks.size());
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(task));
            }

            List<T> results = new ArrayList<>(tasks.size());
            Exception firstFailure = null;
            int failIndex = -1;

            for (int i = 0; i < futures.size(); i++) {
                try {
                    results.add(futures.get(i).get());
                } catch (ExecutionException e) {
                    if (firstFailure == null) {
                        firstFailure = e;
                        failIndex = i;
                        // Cancel remaining
                        for (int j = i + 1; j < futures.size(); j++) {
                            futures.get(j).cancel(true);
                        }
                    }
                    results.add(null); // placeholder
                }
            }

            if (firstFailure != null) {
                throw new ConcurrentExecutionException(
                        "Task " + failIndex + " failed", firstFailure.getCause());
            }
            return results;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Fork-Join Partial: deadline-based, collects successful + failed
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Forks all tasks concurrently and joins with a deadline.
     * Returns partial results for tasks that completed, and reports
     * timed-out and failed tasks separately.
     *
     * @param tasks   the tasks to execute (each identified by a label)
     * @param timeout maximum time to wait for all tasks
     * @param <T>     result type
     * @return a {@link PartialResult} containing successes, timeouts, and failures
     * @throws InterruptedException if the calling thread is interrupted
     */
    public static <T> PartialResult<T> forkJoinPartial(
            List<LabeledTask<T>> tasks, Duration timeout) throws InterruptedException {
        if (tasks.isEmpty()) return PartialResult.empty();

        return STRUCTURED_AVAILABLE
                ? forkJoinPartialStructured(tasks, timeout)
                : forkJoinPartialClassic(tasks, timeout);
    }

    // ── Structured implementation ───────────────────────────────────────

    @SuppressWarnings("preview")
    private static <T> PartialResult<T> forkJoinPartialStructured(
            List<LabeledTask<T>> tasks, Duration timeout) throws InterruptedException {
        // Use awaitAll() joiner (never auto-cancels) + Configuration.withTimeout()
        try (var scope = StructuredTaskScope.open(
                StructuredTaskScope.Joiner.<T>awaitAll(),
                cf -> cf.withTimeout(timeout))) {

            List<Subtask<T>> subtasks = new ArrayList<>(tasks.size());
            for (LabeledTask<T> task : tasks) {
                subtasks.add(scope.fork(task.callable()::call));
            }

            try {
                scope.join();
            } catch (StructuredTaskScope.TimeoutException e) {
                // Expected — some tasks didn't finish within the deadline
            }

            // Inspect subtask states after join — pre-sized to avoid resize
            int n = subtasks.size();
            List<PartialResult.Entry<T>> successes = new ArrayList<>(n);
            List<String> timedOut = new ArrayList<>(n);
            List<PartialResult.Failure> failures = new ArrayList<>(n);

            for (int i = 0; i < subtasks.size(); i++) {
                Subtask<T> subtask = subtasks.get(i);
                String label = tasks.get(i).label();
                switch (subtask.state()) {
                    case SUCCESS -> successes.add(new PartialResult.Entry<>(label, subtask.get()));
                    case FAILED -> failures.add(new PartialResult.Failure(label, subtask.exception()));
                    case UNAVAILABLE -> timedOut.add(label);
                }
            }
            return new PartialResult<>(successes, timedOut, failures);
        }
    }

    // ── Classic implementation ──────────────────────────────────────────

    private static <T> PartialResult<T> forkJoinPartialClassic(
            List<LabeledTask<T>> tasks, Duration timeout) throws InterruptedException {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            record FutureEntry<T>(String label, Future<T> future) {}
            List<FutureEntry<T>> entries = new ArrayList<>(tasks.size());

            for (LabeledTask<T> task : tasks) {
                entries.add(new FutureEntry<>(task.label(), executor.submit(task.callable())));
            }

            int n = entries.size();
            List<PartialResult.Entry<T>> successes = new ArrayList<>(n);
            List<String> timedOut = new ArrayList<>(n);
            List<PartialResult.Failure> failures = new ArrayList<>(n);

            long deadlineMs = System.currentTimeMillis() + timeout.toMillis();

            for (FutureEntry<T> entry : entries) {
                long remaining = deadlineMs - System.currentTimeMillis();
                if (remaining <= 0) {
                    timedOut.add(entry.label());
                    entry.future().cancel(true);
                    continue;
                }
                try {
                    T result = entry.future().get(remaining, java.util.concurrent.TimeUnit.MILLISECONDS);
                    successes.add(new PartialResult.Entry<>(entry.label(), result));
                } catch (java.util.concurrent.TimeoutException e) {
                    timedOut.add(entry.label());
                    entry.future().cancel(true);
                } catch (ExecutionException e) {
                    failures.add(new PartialResult.Failure(entry.label(), e.getCause()));
                }
            }

            return new PartialResult<>(successes, timedOut, failures);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Supporting types
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * A typed pair of results from {@link #forkJoin2}.
     *
     * <p>Zero-overhead alternative to {@code List<T>} when exactly two tasks
     * with potentially different result types are forked concurrently.
     * Avoids list allocation, iterator creation, and index-based access.</p>
     *
     * @param first  result of the first task
     * @param second result of the second task
     * @param <A>    type of first result
     * @param <B>    type of second result
     */
    public record Pair<A, B>(A first, B second) {}

    /**
     * A labeled task for use with {@link #forkJoinPartial}.
     *
     * @param label    human-readable identifier (e.g., shard ID)
     * @param callable the work to execute
     * @param <T>      result type
     */
    public record LabeledTask<T>(String label, Callable<T> callable) {
        public LabeledTask {
            if (label == null) { throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "label"); }
            if (callable == null) { throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "callable"); }
        }
    }

    /**
     * Result of a partial fork-join with deadline. Contains successful results,
     * timed-out task labels, and failed task details.
     *
     * @param <T> the result type of successful tasks
     */
    public record PartialResult<T>(
            List<Entry<T>> successes,
            List<String> timedOut,
            List<Failure> failures
    ) {
        /** A successful result with its task label. */
        public record Entry<T>(String label, T result) {}

        /** A failed task with its label and cause. */
        public record Failure(String label, Throwable cause) {}

        /** Returns true if all tasks completed successfully (no timeouts or failures). */
        public boolean isComplete() {
            return timedOut.isEmpty() && failures.isEmpty();
        }

        /** Returns true if no tasks succeeded at all. */
        public boolean allFailed() {
            return successes.isEmpty();
        }

        /** Returns the combined list of timed-out and failed task labels. */
        public List<String> unreachableLabels() {
            List<String> result = new ArrayList<>(timedOut);
            for (Failure f : failures) result.add(f.label());
            return result;
        }

        static <T> PartialResult<T> empty() {
            return new PartialResult<>(List.of(), List.of(), List.of());
        }
    }
}
