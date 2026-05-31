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

import com.spectrayan.spector.memory.synapse.SynapticTagEncoder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Time-triggered memory injection scheduler.
 *
 * <h3>Biological Analog: Prospective Memory</h3>
 * <p>Remembering to do something <em>in the future</em>. The hippocampus encodes
 * time-triggered retrieval cues — "pick up milk on the way home" is not recalled
 * until you approach the store.</p>
 *
 * <h3>Design</h3>
 * <p>The agent schedules a reminder via {@link #schedule}. A background check
 * (triggered during {@code recall()}) scans for due reminders and injects them
 * into the result set, regardless of query similarity.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>Uses {@link ConcurrentLinkedQueue} for lock-free enqueue/dequeue.</p>
 */
public final class ProspectiveScheduler {

    private static final Logger log = LoggerFactory.getLogger(ProspectiveScheduler.class);

    private final ConcurrentLinkedQueue<Reminder> reminders = new ConcurrentLinkedQueue<>();
    private final AtomicLong idCounter = new AtomicLong(0);

    /**
     * Schedules a reminder to surface at a future time.
     *
     * @param text     the reminder text
     * @param triggerAt when to surface the reminder
     * @param tags     contextual tags for the reminder
     * @return the created reminder
     */
    public Reminder schedule(String text, Instant triggerAt, String... tags) {
        long synapticTags = SynapticTagEncoder.encode(tags);
        Reminder reminder = new Reminder(
                "prospective-" + idCounter.incrementAndGet(),
                text,
                triggerAt,
                synapticTags,
                Instant.now()
        );

        reminders.offer(reminder);
        log.info("Prospective memory scheduled: '{}' at {}", text, triggerAt);
        return reminder;
    }

    /**
     * Convenience: schedule a reminder relative to now.
     *
     * @param text    the reminder text
     * @param delay   duration from now
     * @param tags    contextual tags
     * @return the created reminder
     */
    public Reminder scheduleAfter(String text, Duration delay, String... tags) {
        return schedule(text, Instant.now().plus(delay), tags);
    }

    /**
     * Collects and removes all due reminders.
     *
     * <p>Called during each {@code recall()} to inject prospective memories
     * into the result set.</p>
     *
     * @return list of due reminders (removed from the queue)
     */
    public List<Reminder> collectDue() {
        return collectDueAt(Instant.now());
    }

    /**
     * Collects reminders due at a specific time (for testing).
     */
    public List<Reminder> collectDueAt(Instant now) {
        List<Reminder> due = new ArrayList<>();
        reminders.removeIf(r -> {
            if (r.isDueAt(now)) {
                due.add(r);
                log.info("Prospective memory triggered: '{}'", r.text());
                return true;
            }
            return false;
        });
        return due;
    }

    /**
     * Returns the number of pending reminders.
     */
    public int pendingCount() {
        return reminders.size();
    }

    /**
     * Cancels all pending reminders.
     */
    public void cancelAll() {
        int count = reminders.size();
        reminders.clear();
        log.debug("Cancelled {} pending prospective memories", count);
    }
}
