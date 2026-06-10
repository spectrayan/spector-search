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

/**
 * Restart and watchdog policy for supervised daemons.
 *
 * <h3>Restart Behavior</h3>
 * <p>When a daemon's task throws an exception, the supervisor waits
 * {@code restartBackoff} (doubling each attempt) before restarting.
 * After {@code maxRestarts} consecutive failures, the daemon is
 * marked as {@code DEAD} and no further restarts are attempted.</p>
 *
 * <h3>Watchdog</h3>
 * <p>If a single cycle exceeds {@code watchdogTimeout}, the supervisor
 * logs a WARNING. The watchdog does <b>not</b> kill the thread —
 * it only alerts, because I/O-bound tasks (like {@code force()})
 * may legitimately take longer under heavy load.</p>
 *
 * @param maxRestarts      max consecutive restart attempts before giving up (default: 3)
 * @param restartBackoff   initial delay between restarts; doubles each attempt (default: 5s)
 * @param watchdogTimeout  log WARNING if a single cycle exceeds this (default: 60s)
 * @param restartOnError   whether to restart on {@link Error} (not just Exception); default: false
 */
public record DaemonPolicy(
        int maxRestarts,
        Duration restartBackoff,
        Duration watchdogTimeout,
        boolean restartOnError
) {
    /** Default policy: 3 restarts, 5s backoff, 60s watchdog, no Error restart. */
    public static final DaemonPolicy DEFAULT = new DaemonPolicy(
            3, Duration.ofSeconds(5), Duration.ofSeconds(60), false);

    /** Policy for critical daemons: 5 restarts, 2s backoff, 30s watchdog. */
    public static final DaemonPolicy CRITICAL = new DaemonPolicy(
            5, Duration.ofSeconds(2), Duration.ofSeconds(30), false);

    /** Compact validation. */
    public DaemonPolicy {
        if (maxRestarts < 0) throw new IllegalArgumentException("maxRestarts must be ≥ 0");
        if (restartBackoff == null) throw new IllegalArgumentException("restartBackoff must not be null");
        if (watchdogTimeout == null) throw new IllegalArgumentException("watchdogTimeout must not be null");
    }
}
