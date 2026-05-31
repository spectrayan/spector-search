/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Business Source License 1.1 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://github.com/spectrayan/spector/blob/main/spector-memory/LICENSE
 *
 * Change Date: May 27, 2030
 * Change License: Apache License, Version 2.0
 */
package com.spectrayan.spector.memory.prospective;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ProspectiveSchedulerTest {

    @Test
    void scheduleAndCollectDue() {
        var scheduler = new ProspectiveScheduler();
        Instant past = Instant.now().minus(Duration.ofMinutes(5));
        scheduler.schedule("Check build status", past, "ci", "build");

        var due = scheduler.collectDue();
        assertThat(due).hasSize(1);
        assertThat(due.getFirst().text()).isEqualTo("Check build status");
        assertThat(scheduler.pendingCount()).isZero(); // removed after collection
    }

    @Test
    void futureReminderNotDueYet() {
        var scheduler = new ProspectiveScheduler();
        Instant future = Instant.now().plus(Duration.ofHours(1));
        scheduler.schedule("Future reminder", future, "later");

        var due = scheduler.collectDue();
        assertThat(due).isEmpty();
        assertThat(scheduler.pendingCount()).isEqualTo(1);
    }

    @Test
    void collectDueAtSpecificTime() {
        var scheduler = new ProspectiveScheduler();
        Instant target = Instant.parse("2026-12-01T10:00:00Z");
        scheduler.schedule("Year-end review", target, "review");

        // Not due yet
        var beforeDue = scheduler.collectDueAt(Instant.parse("2026-11-01T10:00:00Z"));
        assertThat(beforeDue).isEmpty();

        // Now it's due
        var afterDue = scheduler.collectDueAt(Instant.parse("2026-12-02T10:00:00Z"));
        assertThat(afterDue).hasSize(1);
    }

    @Test
    void scheduleAfterConvenience() {
        var scheduler = new ProspectiveScheduler();
        var reminder = scheduler.scheduleAfter("Check in 30min", Duration.ofMinutes(30), "followup");

        assertThat(reminder.id()).startsWith("prospective-");
        assertThat(reminder.triggerAt()).isAfter(Instant.now());
        assertThat(scheduler.pendingCount()).isEqualTo(1);
    }

    @Test
    void cancelAllClearsPending() {
        var scheduler = new ProspectiveScheduler();
        scheduler.scheduleAfter("r1", Duration.ofHours(1), "a");
        scheduler.scheduleAfter("r2", Duration.ofHours(2), "b");

        scheduler.cancelAll();
        assertThat(scheduler.pendingCount()).isZero();
    }

    @Test
    void reminderIsDue() {
        var past = new Reminder("id", "text", Instant.now().minusSeconds(10), 0L, Instant.now());
        assertThat(past.isDue()).isTrue();

        var future = new Reminder("id", "text", Instant.now().plusSeconds(60), 0L, Instant.now());
        assertThat(future.isDue()).isFalse();
    }
}
