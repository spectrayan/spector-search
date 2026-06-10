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
package com.spectrayan.spector.commons.concurrent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Supervises long-running periodic virtual threads with auto-restart and watchdog.
 *
 * <h3>Purpose</h3>
 * <p>{@link ConcurrentTasks} handles short-lived concurrent work (fork-join,
 * fire-and-forget). This class fills the gap for <b>long-running daemon loops</b>
 * — periodic background tasks that must survive failures and be observable.</p>
 *
 * <h3>Features</h3>
 * <ul>
 *   <li><b>Auto-restart</b> — restarts dead daemons with exponential backoff</li>
 *   <li><b>Watchdog</b> — logs WARNING if a cycle exceeds the configured timeout</li>
 *   <li><b>Named threads</b> — all daemons get descriptive names for thread dumps</li>
 *   <li><b>Observability</b> — {@link #status()} returns live snapshots of all daemons</li>
 *   <li><b>Graceful shutdown</b> — {@link #close()} interrupts all daemons and waits</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * var supervisor = new DaemonSupervisor("memory");
 * supervisor.schedule("checkpoint", () -> checkpoint(), Duration.ofSeconds(30),
 *         DaemonPolicy.CRITICAL);
 * // ... later ...
 * supervisor.close(); // stops all daemons
 * }</pre>
 *
 * @see DaemonPolicy
 * @see DaemonStatus
 * @see ConcurrentTasks
 */
public final class DaemonSupervisor implements AutoCloseable {

    private static final System.Logger log = System.getLogger(DaemonSupervisor.class.getName());

    private final String prefix;
    private final CopyOnWriteArrayList<ManagedDaemon> daemons = new CopyOnWriteArrayList<>();
    private volatile boolean closed = false;

    /**
     * Creates a supervisor with a name prefix for all managed threads.
     *
     * @param prefix thread name prefix (e.g., "memory" → "spector-memory-checkpoint")
     */
    public DaemonSupervisor(String prefix) {
        this.prefix = prefix;
    }

    /**
     * Registers and starts a periodic daemon.
     *
     * <p>The task is invoked once per {@code interval}. If it throws, the
     * supervisor applies the restart policy. The daemon sleeps between cycles.</p>
     *
     * @param name     daemon name (must be unique within this supervisor)
     * @param task     the work to execute each cycle (must not block indefinitely)
     * @param interval sleep duration between cycles
     * @param policy   restart and watchdog policy
     * @throws IllegalStateException if the supervisor is closed
     * @throws IllegalArgumentException if a daemon with this name already exists
     */
    public void schedule(String name, Runnable task, Duration interval, DaemonPolicy policy) {
        if (closed) throw new IllegalStateException("Supervisor is closed");
        for (ManagedDaemon d : daemons) {
            if (d.name.equals(name)) {
                throw new IllegalArgumentException("Daemon '" + name + "' already registered");
            }
        }

        ManagedDaemon daemon = new ManagedDaemon(name, task, interval, policy);
        daemons.add(daemon);
        daemon.start();
    }

    /**
     * Returns live status snapshots of all managed daemons.
     *
     * @return unmodifiable list of daemon statuses
     */
    public List<DaemonStatus> status() {
        return daemons.stream()
                .map(ManagedDaemon::snapshot)
                .toList();
    }

    /**
     * Returns the status of a specific daemon by name.
     *
     * @param name the daemon name
     * @return the status, or null if not found
     */
    public DaemonStatus status(String name) {
        for (ManagedDaemon d : daemons) {
            if (d.name.equals(name)) return d.snapshot();
        }
        return null;
    }

    /**
     * Cancels a specific daemon by name.
     *
     * @param name the daemon name
     * @return true if the daemon was found and cancelled
     */
    public boolean cancel(String name) {
        for (ManagedDaemon d : daemons) {
            if (d.name.equals(name)) {
                d.stop();
                return true;
            }
        }
        return false;
    }

    /**
     * Shuts down all managed daemons gracefully.
     *
     * <p>Interrupts all daemon threads and waits up to 5 seconds for each.
     * After this method returns, no daemon threads are running.</p>
     */
    @Override
    public void close() {
        if (closed) return;
        closed = true;

        log.log(System.Logger.Level.INFO, "DaemonSupervisor[{0}] shutting down {1} daemons",
                prefix, daemons.size());

        for (ManagedDaemon daemon : daemons) {
            daemon.stop();
        }

        // Wait for all to finish
        for (ManagedDaemon daemon : daemons) {
            daemon.join(5_000);
        }

        log.log(System.Logger.Level.INFO, "DaemonSupervisor[{0}] shutdown complete", prefix);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ManagedDaemon — internal supervised thread wrapper
    // ═══════════════════════════════════════════════════════════════════════

    private final class ManagedDaemon {
        final String name;
        final Runnable task;
        final Duration interval;
        final DaemonPolicy policy;

        // ── Mutable state (accessed from daemon thread + status() calls) ──
        volatile DaemonStatus.State state = DaemonStatus.State.IDLE;
        volatile int restartCount = 0;
        volatile Instant lastCycleStart;
        volatile Duration lastCycleDuration;
        volatile Throwable lastError;
        volatile Thread thread;
        volatile boolean running = true;

        ManagedDaemon(String name, Runnable task, Duration interval, DaemonPolicy policy) {
            this.name = name;
            this.task = task;
            this.interval = interval;
            this.policy = policy;
        }

        void start() {
            String threadName = "spector-" + prefix + "-" + name;
            thread = Thread.ofVirtual()
                    .name(threadName)
                    .uncaughtExceptionHandler((t, e) ->
                            log.log(System.Logger.Level.ERROR,
                                    "Uncaught error in daemon {0}: {1}", threadName, e.getMessage()))
                    .start(this::loop);

            log.log(System.Logger.Level.INFO,
                    "Daemon[{0}] started: interval={1}s, maxRestarts={2}, watchdog={3}s",
                    threadName, interval.toSeconds(), policy.maxRestarts(),
                    policy.watchdogTimeout().toSeconds());
        }

        void stop() {
            running = false;
            state = DaemonStatus.State.STOPPED;
            Thread t = thread;
            if (t != null) {
                t.interrupt();
            }
        }

        void join(long timeoutMs) {
            Thread t = thread;
            if (t != null) {
                try {
                    t.join(timeoutMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        DaemonStatus snapshot() {
            return new DaemonStatus(name, state, restartCount,
                    lastCycleStart, lastCycleDuration, lastError);
        }

        /**
         * Main supervised loop: sleep → execute → watchdog check → restart on failure.
         */
        private void loop() {
            while (running) {
                // ── Sleep phase ──
                state = DaemonStatus.State.SLEEPING;
                try {
                    Thread.sleep(interval);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                if (!running) break;

                // ── Execute phase ──
                state = DaemonStatus.State.RUNNING;
                lastCycleStart = Instant.now();

                try {
                    task.run();

                    // Successful cycle — reset restart counter
                    lastCycleDuration = Duration.between(lastCycleStart, Instant.now());
                    restartCount = 0;
                    lastError = null;

                    // Watchdog: warn if cycle took too long
                    if (lastCycleDuration.compareTo(policy.watchdogTimeout()) > 0) {
                        log.log(System.Logger.Level.WARNING,
                                "Daemon[{0}] cycle exceeded watchdog: {1}ms > {2}ms",
                                name, lastCycleDuration.toMillis(),
                                policy.watchdogTimeout().toMillis());
                    }

                } catch (Exception e) {
                    lastError = e;
                    lastCycleDuration = Duration.between(lastCycleStart, Instant.now());
                    log.log(System.Logger.Level.ERROR,
                            "Daemon[{0}] cycle failed (attempt {1}/{2}): {3}",
                            name, restartCount + 1, policy.maxRestarts(), e.getMessage());

                    if (!handleFailure()) break;

                } catch (Error e) {
                    lastError = e;
                    lastCycleDuration = Duration.between(lastCycleStart, Instant.now());
                    log.log(System.Logger.Level.ERROR,
                            "Daemon[{0}] hit Error: {1}", name, e.getMessage());

                    if (policy.restartOnError()) {
                        if (!handleFailure()) break;
                    } else {
                        state = DaemonStatus.State.DEAD;
                        log.log(System.Logger.Level.ERROR,
                                "Daemon[{0}] DEAD — Error not restartable per policy", name);
                        break;
                    }
                }
            }

            if (state != DaemonStatus.State.DEAD) {
                state = DaemonStatus.State.STOPPED;
            }
            log.log(System.Logger.Level.INFO, "Daemon[{0}] exited: state={1}", name, state);
        }

        /**
         * Handles a failure: increments restart count, applies backoff.
         *
         * @return true if the daemon should continue (restart), false if max restarts exceeded
         */
        private boolean handleFailure() {
            restartCount++;
            if (restartCount > policy.maxRestarts()) {
                state = DaemonStatus.State.DEAD;
                log.log(System.Logger.Level.ERROR,
                        "Daemon[{0}] DEAD — exceeded max restarts ({1})",
                        name, policy.maxRestarts());
                return false;
            }

            // Exponential backoff: base * 2^(attempt-1)
            state = DaemonStatus.State.RESTARTING;
            long backoffMs = policy.restartBackoff().toMillis() * (1L << (restartCount - 1));
            log.log(System.Logger.Level.WARNING,
                    "Daemon[{0}] restarting in {1}ms (attempt {2}/{3})",
                    name, backoffMs, restartCount, policy.maxRestarts());

            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }

            return running; // only continue if not stopped during backoff
        }
    }
}
