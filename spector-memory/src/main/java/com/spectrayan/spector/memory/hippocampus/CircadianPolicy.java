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
package com.spectrayan.spector.memory.hippocampus;

import java.time.Duration;

/**
 * Configuration for the {@link ReflectDaemon}'s sleep cycle triggers.
 *
 * <h3>Biological Analog: Circadian Rhythm</h3>
 * <p>The brain consolidates memories during sleep, triggered by both volume
 * (amount of new information) and time (circadian clock). This policy mirrors
 * that dual-trigger approach.</p>
 *
 * <h3>Three-Mode Trigger</h3>
 * <ul>
 *   <li><b>Volume:</b> Triggers after N new episodic memories (burst workloads)</li>
 *   <li><b>Time:</b> At most once per interval (steady-state operation)</li>
 *   <li><b>Manual:</b> {@code memory.reflect()} called explicitly (developer control)</li>
 * </ul>
 */
public record CircadianPolicy(
        int volumeTrigger,
        Duration timeTrigger,
        float tombstoneThreshold,
        float decayPruneThreshold,
        float interferenceThreshold,
        float interferenceDecayFactor
) {

    /** Default policy: reflect after 100 memories or 1 hour, prune below 0.05 decay. */
    public static final CircadianPolicy DEFAULT = new CircadianPolicy(
            100,
            Duration.ofHours(1),
            0.30f,
            0.05f,
            0.12f,
            0.7f
    );

    /**
     * Creates a builder for custom configuration.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link CircadianPolicy}.
     */
    public static final class Builder {
        private int volumeTrigger = 100;
        private Duration timeTrigger = Duration.ofHours(1);
        private float tombstoneThreshold = 0.30f;
        private float decayPruneThreshold = 0.05f;
        private float interferenceThreshold = 0.12f;
        private float interferenceDecayFactor = 0.7f;

        /**
         * Number of new episodic memories that triggers a reflection cycle.
         */
        public Builder volumeTrigger(int volumeTrigger) {
            this.volumeTrigger = volumeTrigger;
            return this;
        }

        /**
         * Maximum time between reflection cycles.
         */
        public Builder timeTrigger(Duration timeTrigger) {
            this.timeTrigger = timeTrigger;
            return this;
        }

        /**
         * Tombstone ratio that triggers partition rebuild (default: 0.30 = 30%).
         */
        public Builder tombstoneThreshold(float tombstoneThreshold) {
            this.tombstoneThreshold = tombstoneThreshold;
            return this;
        }

        /**
         * Decay score below which memories are tombstoned during Deep Sleep.
         */
        public Builder decayPruneThreshold(float decayPruneThreshold) {
            this.decayPruneThreshold = decayPruneThreshold;
            return this;
        }

        /**
         * L2 distance threshold for near-duplicate interference detection (default: 0.12).
         * Records within this distance compete during sleep — the older one decays.
         */
        public Builder interferenceThreshold(float t) { this.interferenceThreshold = t; return this; }

        /**
         * Importance decay factor for the older near-duplicate (default: 0.7 = 30% reduction).
         */
        public Builder interferenceDecayFactor(float f) { this.interferenceDecayFactor = f; return this; }

        public CircadianPolicy build() {
            return new CircadianPolicy(volumeTrigger, timeTrigger,
                    tombstoneThreshold, decayPruneThreshold,
                    interferenceThreshold, interferenceDecayFactor);
        }
    }
}
