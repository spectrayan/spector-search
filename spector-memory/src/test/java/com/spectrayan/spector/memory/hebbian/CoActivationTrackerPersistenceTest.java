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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link CoActivationTracker} off-heap persistence (save/load).
 */
class CoActivationTrackerPersistenceTest {

    @TempDir
    Path tempDir;

    @Test
    void saveAndLoadPreservesCoActivations() {
        Path file = tempDir.resolve("coax.bin");

        try (var tracker = new CoActivationTracker(1000, 2000)) {
            tracker.recordCoActivation("java", "performance");
            tracker.recordCoActivation("java", "performance");
            tracker.recordCoActivation("java", "gc");
            assertThat(tracker.pairCount()).isEqualTo(2);

            tracker.save(file);
        }

        try (var loaded = CoActivationTracker.load(file, 1000, 2000)) {
            assertThat(loaded.pairCount()).isEqualTo(2);
            assertThat(loaded.getCoActivation("java", "performance")).isEqualTo(2);
            assertThat(loaded.getCoActivation("java", "gc")).isEqualTo(1);
            assertThat(loaded.getCoActivation("java", "python")).isZero();
        }
    }

    @Test
    void saveAndLoadPreservesStdpEdges() {
        Path file = tempDir.resolve("coax-stdp.bin");

        try (var tracker = new CoActivationTracker(1000, 2000)) {
            tracker.recordSequentialActivation("java", "gc", 1000L, 2000L);
            assertThat(tracker.edgeCount()).isGreaterThan(0);

            var edge = tracker.getEdge("java", "gc");
            assertThat(edge).isNotNull();
            float originalWeight = edge.weight();

            tracker.save(file);

            try (var loaded = CoActivationTracker.load(file, 1000, 2000)) {
                assertThat(loaded.edgeCount()).isEqualTo(tracker.edgeCount());
                var loadedEdge = loaded.getEdge("java", "gc");
                assertThat(loadedEdge).isNotNull();
                assertThat(loadedEdge.weight()).isEqualTo(originalWeight);
                assertThat(loadedEdge.activationCount()).isEqualTo(1);
            }
        }
    }

    @Test
    void loadMissingFileCreatesNew() {
        Path missing = tempDir.resolve("nonexistent.bin");
        try (var tracker = CoActivationTracker.load(missing, 500, 1000)) {
            assertThat(tracker.pairCount()).isZero();
            assertThat(tracker.edgeCount()).isZero();
        }
    }

    @Test
    void saveAndLoadPreservesAssociatedTags() {
        Path file = tempDir.resolve("coax-assoc.bin");

        try (var tracker = new CoActivationTracker(1000, 2000)) {
            for (int i = 0; i < 5; i++) tracker.recordCoActivation("java", "performance");
            for (int i = 0; i < 3; i++) tracker.recordCoActivation("java", "gc");
            tracker.recordCoActivation("java", "concurrency");

            tracker.save(file);
        }

        try (var loaded = CoActivationTracker.load(file, 1000, 2000)) {
            var associated = loaded.getAssociatedTags("java", 3);
            assertThat(associated).hasSize(3);
            assertThat(associated.getFirst()).isEqualTo("performance");
        }
    }

    @Test
    void resetClearsOffHeapData() {
        try (var tracker = new CoActivationTracker(1000, 2000)) {
            tracker.recordCoActivation("java", "python", "rust");
            assertThat(tracker.pairCount()).isGreaterThan(0);

            tracker.reset();
            assertThat(tracker.pairCount()).isZero();
            assertThat(tracker.edgeCount()).isZero();
        }
    }

    @Test
    void canonicalPairOrderPreserved() {
        try (var tracker = new CoActivationTracker(1000, 2000)) {
            tracker.recordCoActivation("java", "python");
            // Reverse order should access same pair
            assertThat(tracker.getCoActivation("python", "java")).isEqualTo(1);
        }
    }

    @Test
    void predictiveStrengthFromStdp() {
        try (var tracker = new CoActivationTracker(1000, 2000)) {
            tracker.recordSequentialActivation("search", "relevance", 1000L, 1500L);

            float strength = tracker.getPredictiveStrength(
                    java.util.List.of("search"), new String[]{"relevance"});
            assertThat(strength).isGreaterThan(0.0f);

            float avgStrength = tracker.getAveragePredictiveStrength(
                    java.util.List.of("search"), new String[]{"relevance"});
            assertThat(avgStrength).isGreaterThan(0.0f);
        }
    }
}
