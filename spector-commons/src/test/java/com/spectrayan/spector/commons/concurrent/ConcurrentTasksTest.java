/*
 * Copyright 2026 Spectrayan
 */
package com.spectrayan.spector.commons.concurrent;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import com.spectrayan.spector.commons.error.SpectorValidationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ConcurrentTasks} — fork-join, fire-and-forget,
 * partial results, supporting types, edge cases.
 */
@DisplayName("ConcurrentTasks")
class ConcurrentTasksTest {

    // ══════════════════════════════════════════════════════════════
    // isStructuredConcurrencyEnabled
    // ══════════════════════════════════════════════════════════════

    @Test @DisplayName("structured concurrency flag is accessible")
    void structuredFlag() {
        // On Java 25 with --enable-preview, this should be true
        assertThat(ConcurrentTasks.isStructuredConcurrencyEnabled()).isTrue();
    }

    // ══════════════════════════════════════════════════════════════
    // forkJoinAll
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("forkJoinAll")
    class ForkJoinAllTests {

        @Test @DisplayName("empty task list returns empty results")
        void emptyTasks() throws Exception {
            var results = ConcurrentTasks.forkJoinAll(List.of());
            assertThat(results).isEmpty();
        }

        @Test @DisplayName("single task runs and returns result")
        void singleTask() throws Exception {
            List<String> results = ConcurrentTasks.forkJoinAll(List.of(() -> "hello"));
            assertThat(results).containsExactly("hello");
        }

        @Test @DisplayName("multiple tasks run concurrently")
        void multipleTasks() throws Exception {
            List<Integer> results = ConcurrentTasks.forkJoinAll(List.of(
                    () -> 1,
                    () -> 2,
                    () -> 3
            ));
            assertThat(results).containsExactly(1, 2, 3);
        }

        @Test @DisplayName("two-arg convenience overload works")
        void twoArgOverload() throws Exception {
            var results = ConcurrentTasks.forkJoinAll(() -> "a", () -> "b");
            assertThat(results).containsExactly("a", "b");
        }

        @Test @DisplayName("single task failure throws ConcurrentExecutionException")
        void singleTaskFailure() {
            assertThatThrownBy(() -> ConcurrentTasks.forkJoinAll(
                    List.of((Callable<String>) () -> { throw new RuntimeException("boom"); })))
                    .isInstanceOf(ConcurrentExecutionException.class)
                    .hasCauseInstanceOf(RuntimeException.class);
        }

        @Test @DisplayName("task failure propagates cause")
        void taskFailurePropagatesCause() {
            assertThatThrownBy(() -> ConcurrentTasks.forkJoinAll(List.of(
                    () -> "ok",
                    (Callable<String>) () -> { throw new IllegalStateException("broken"); })))
                    .isInstanceOf(ConcurrentExecutionException.class);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // forkJoin2
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("forkJoin2")
    class ForkJoin2Tests {

        @Test @DisplayName("returns typed pair of results")
        void typedPair() throws Exception {
            var pair = ConcurrentTasks.forkJoin2(() -> "hello", () -> 42);
            assertThat(pair.first()).isEqualTo("hello");
            assertThat(pair.second()).isEqualTo(42);
        }

        @Test @DisplayName("failure in first task throws")
        void firstTaskFails() {
            assertThatThrownBy(() -> ConcurrentTasks.forkJoin2(
                    () -> { throw new RuntimeException("fail"); }, () -> 42))
                    .isInstanceOf(ConcurrentExecutionException.class);
        }

        @Test @DisplayName("failure in second task throws")
        void secondTaskFails() {
            assertThatThrownBy(() -> ConcurrentTasks.forkJoin2(
                    () -> "ok", () -> { throw new RuntimeException("fail"); }))
                    .isInstanceOf(ConcurrentExecutionException.class);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // fireAndForget
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("fireAndForget")
    class FireAndForgetTests {

        @Test @DisplayName("executes task asynchronously")
        void executesAsync() throws Exception {
            var counter = new AtomicInteger(0);
            ConcurrentTasks.fireAndForget(counter::incrementAndGet);
            Thread.sleep(100); // allow virtual thread to run
            assertThat(counter.get()).isEqualTo(1);
        }

        @Test @DisplayName("exception in task does not propagate")
        void exceptionSwallowed() throws Exception {
            // Should not throw — exception is caught and logged
            ConcurrentTasks.fireAndForget(() -> { throw new RuntimeException("oops"); });
            Thread.sleep(50);
            // If we get here, the exception was handled
        }

        @Test @DisplayName("fireAndForgetAll dispatches all tasks")
        void fireAndForgetAll() throws Exception {
            var counter = new AtomicInteger(0);
            ConcurrentTasks.fireAndForgetAll(List.of(
                    counter::incrementAndGet,
                    counter::incrementAndGet,
                    counter::incrementAndGet
            ));
            Thread.sleep(200);
            assertThat(counter.get()).isEqualTo(3);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // virtualExecutor
    // ══════════════════════════════════════════════════════════════

    @Test @DisplayName("virtualExecutor returns non-null executor")
    void virtualExecutor() {
        assertThat(ConcurrentTasks.virtualExecutor()).isNotNull();
    }

    // ══════════════════════════════════════════════════════════════
    // forkJoinPartial
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("forkJoinPartial")
    class ForkJoinPartialTests {

        @Test @DisplayName("empty tasks returns empty partial result")
        void emptyTasks() throws Exception {
            var result = ConcurrentTasks.forkJoinPartial(List.of(), Duration.ofSeconds(1));
            assertThat(result.successes()).isEmpty();
            assertThat(result.timedOut()).isEmpty();
            assertThat(result.failures()).isEmpty();
            assertThat(result.isComplete()).isTrue();
        }

        @Test @DisplayName("all tasks succeed within deadline")
        void allSucceed() throws Exception {
            var result = ConcurrentTasks.forkJoinPartial(List.of(
                    new ConcurrentTasks.LabeledTask<>("a", () -> 1),
                    new ConcurrentTasks.LabeledTask<>("b", () -> 2)
            ), Duration.ofSeconds(5));
            assertThat(result.successes()).hasSize(2);
            assertThat(result.isComplete()).isTrue();
            assertThat(result.allFailed()).isFalse();
        }

        @Test @DisplayName("failed tasks appear in failures")
        void taskFailure() throws Exception {
            var result = ConcurrentTasks.forkJoinPartial(List.of(
                    new ConcurrentTasks.LabeledTask<>("ok", () -> 1),
                    new ConcurrentTasks.LabeledTask<>("bad", () -> { throw new RuntimeException("boom"); })
            ), Duration.ofSeconds(5));
            assertThat(result.failures()).hasSize(1);
            assertThat(result.failures().get(0).label()).isEqualTo("bad");
            assertThat(result.isComplete()).isFalse();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Supporting types
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Supporting types")
    class SupportingTypeTests {

        @Test @DisplayName("Pair holds two values")
        void pair() {
            var pair = new ConcurrentTasks.Pair<>("hello", 42);
            assertThat(pair.first()).isEqualTo("hello");
            assertThat(pair.second()).isEqualTo(42);
        }

        @Test @DisplayName("LabeledTask rejects null label")
        void labeledTaskNullLabel() {
            assertThatThrownBy(() -> new ConcurrentTasks.LabeledTask<>(null, () -> 1))
                    .isInstanceOf(SpectorValidationException.class);
        }

        @Test @DisplayName("LabeledTask rejects null callable")
        void labeledTaskNullCallable() {
            assertThatThrownBy(() -> new ConcurrentTasks.LabeledTask<>("test", null))
                    .isInstanceOf(SpectorValidationException.class);
        }

        @Test @DisplayName("PartialResult.unreachableLabels combines timeouts and failures")
        void unreachableLabels() {
            var result = new ConcurrentTasks.PartialResult<>(
                    List.of(),
                    List.of("shard-1"),
                    List.of(new ConcurrentTasks.PartialResult.Failure("shard-2", new RuntimeException()))
            );
            assertThat(result.unreachableLabels()).containsExactly("shard-1", "shard-2");
            assertThat(result.allFailed()).isTrue();
        }

        @Test @DisplayName("PartialResult.isComplete when no timeouts or failures")
        void isComplete() {
            var entry = new ConcurrentTasks.PartialResult.Entry<>("ok", 1);
            var result = new ConcurrentTasks.PartialResult<>(List.of(entry), List.of(), List.of());
            assertThat(result.isComplete()).isTrue();
        }
    }
}
