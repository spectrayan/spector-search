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
package com.spectrayan.spector.memory.hebbian;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CoActivationTrackerTest {

    @Test
    void initialCountIsZero() {
        var tracker = new CoActivationTracker();
        assertThat(tracker.getCoActivation("java", "python")).isZero();
    }

    @Test
    void recordCoActivationIncrements() {
        var tracker = new CoActivationTracker();
        tracker.recordCoActivation("java", "performance");
        assertThat(tracker.getCoActivation("java", "performance")).isEqualTo(1);

        tracker.recordCoActivation("java", "performance");
        assertThat(tracker.getCoActivation("java", "performance")).isEqualTo(2);
    }

    @Test
    void pairKeyIsCanonical() {
        var tracker = new CoActivationTracker();
        tracker.recordCoActivation("java", "python");
        // Reverse order should access same pair
        assertThat(tracker.getCoActivation("python", "java")).isEqualTo(1);
    }

    @Test
    void getAssociatedTagsReturnsSorted() {
        var tracker = new CoActivationTracker();
        for (int i = 0; i < 5; i++) tracker.recordCoActivation("java", "performance");
        for (int i = 0; i < 3; i++) tracker.recordCoActivation("java", "gc");
        tracker.recordCoActivation("java", "concurrency");

        var associated = tracker.getAssociatedTags("java", 3);
        assertThat(associated).hasSize(3);
        assertThat(associated.getFirst()).isEqualTo("performance"); // highest count
    }

    @Test
    void singleTagDoesNotRecord() {
        var tracker = new CoActivationTracker();
        tracker.recordCoActivation("java");
        assertThat(tracker.pairCount()).isZero();
    }

    @Test
    void resetClearsAll() {
        var tracker = new CoActivationTracker();
        tracker.recordCoActivation("java", "python", "rust");
        assertThat(tracker.pairCount()).isGreaterThan(0);

        tracker.reset();
        assertThat(tracker.pairCount()).isZero();
    }
}
