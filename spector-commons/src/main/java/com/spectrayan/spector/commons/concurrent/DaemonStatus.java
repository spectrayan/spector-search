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

/**
 * Observability snapshot of a supervised daemon's state.
 *
 * <p>Exposed via {@link DaemonSupervisor#status()} for monitoring,
 * alerting, and Cortex dashboard integration.</p>
 *
 * @param name              daemon name (e.g., "checkpoint-daemon")
 * @param state             current lifecycle state
 * @param restartCount      number of consecutive restarts (resets on successful cycle)
 * @param lastCycleStart    when the last cycle began (null if never run)
 * @param lastCycleDuration how long the last cycle took (null if never completed)
 * @param lastError         throwable from the last failure (null if healthy)
 */
public record DaemonStatus(
        String name,
        State state,
        int restartCount,
        Instant lastCycleStart,
        Duration lastCycleDuration,
        Throwable lastError
) {
    /** Lifecycle states for a supervised daemon. */
    public enum State {
        /** Registered but not yet started. */
        IDLE,
        /** Currently sleeping between cycles. */
        SLEEPING,
        /** Currently executing a cycle. */
        RUNNING,
        /** Waiting for restart backoff to expire. */
        RESTARTING,
        /** Exceeded max restarts — permanently stopped. */
        DEAD,
        /** Gracefully stopped via close(). */
        STOPPED
    }
}
