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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for HebbianGraph persistence (save/load).
 */
class HebbianGraphPersistenceTest {

    @TempDir
    Path tempDir;

    private HebbianGraph graph;

    @BeforeEach
    void setUp() {
        graph = new HebbianGraph(100);
    }

    @AfterEach
    void tearDown() {
        graph.close();
    }

    @Test
    void saveAndLoadPreservesEdges() {
        graph.strengthen(0, 1, 2.0f);
        graph.strengthen(0, 2, 5.0f);
        graph.strengthen(3, 4, 1.0f);

        Path file = tempDir.resolve("test.graph");
        graph.save(file);
        graph.close();

        // Load
        graph = HebbianGraph.load(file, 100);
        assertThat(graph.degree(0)).isEqualTo(2);
        assertThat(graph.degree(1)).isEqualTo(1);
        assertThat(graph.degree(3)).isEqualTo(1);
        assertThat(graph.degree(4)).isEqualTo(1);

        // Verify weights preserved
        var neighbors = graph.neighbors(0);
        assertThat(neighbors).hasSize(2);
        assertThat(neighbors.get(0).weight()).isEqualTo(5.0f); // node 2 (strongest)
        assertThat(neighbors.get(1).weight()).isEqualTo(2.0f); // node 1
    }

    @Test
    void loadNonExistentFileCreatesNew() {
        Path file = tempDir.resolve("nonexistent.graph");
        graph.close();
        graph = HebbianGraph.load(file, 50);

        assertThat(graph.capacity()).isEqualTo(50);
        assertThat(graph.degree(0)).isZero();
    }

    @Test
    void loadCorruptedFileCreatesNew() throws Exception {
        // Write garbage to file
        Path file = tempDir.resolve("corrupt.graph");
        java.nio.file.Files.write(file, new byte[]{0, 1, 2, 3});

        graph.close();
        graph = HebbianGraph.load(file, 75);
        assertThat(graph.capacity()).isEqualTo(75);
        assertThat(graph.degree(0)).isZero();
    }

    @Test
    void saveAndLoadPreservesCapacity() {
        Path file = tempDir.resolve("cap.graph");
        graph.save(file);
        graph.close();

        graph = HebbianGraph.load(file, 200); // defaultCapacity ignored when file exists
        assertThat(graph.capacity()).isEqualTo(100); // original capacity preserved
    }

    @Test
    void saveAndLoadRoundTripWithDecayedEdges() {
        // Add edges and decay
        graph.strengthen(0, 1, 1.0f);
        graph.strengthen(0, 2, 0.001f); // very weak edge
        graph.decayEdges(0.5f);

        Path file = tempDir.resolve("decay.graph");
        graph.save(file);
        graph.close();

        graph = HebbianGraph.load(file, 100);
        // Edge 0→2 should have been removed by decay (0.001 × 0.5 = 0.0005 < 0.01 threshold)
        assertThat(graph.degree(0)).isEqualTo(1);
        // Edge 0→1 should be preserved but decayed
        var neighbors = graph.neighbors(0);
        assertThat(neighbors).hasSize(1);
        assertThat(neighbors.get(0).weight()).isEqualTo(0.5f);
    }

    @Test
    void totalEdgesCorrect() {
        graph.strengthen(0, 1, 1.0f);
        graph.strengthen(2, 3, 1.0f);
        // Each strengthen creates 2 edges (bidirectional)
        assertThat(graph.totalEdges()).isEqualTo(4);
    }

    @Test
    void savingCreatesParentDirectories() {
        Path nested = tempDir.resolve("a/b/c/test.graph");
        graph.strengthen(0, 1, 1.0f);
        graph.save(nested);
        graph.close();

        graph = HebbianGraph.load(nested, 100);
        assertThat(graph.degree(0)).isEqualTo(1);
    }

    @Test
    void spreadingActivationPersistence() {
        // Build a chain: 0 ↔ 1 ↔ 2
        graph.strengthen(0, 1, 3.0f);
        graph.strengthen(1, 2, 2.0f);

        Path file = tempDir.resolve("spread.graph");
        graph.save(file);
        graph.close();

        graph = HebbianGraph.load(file, 100);
        // Spreading activation from 0 should reach 2
        var activated = graph.activateNeighbors(0, 2);
        assertThat(activated).hasSizeGreaterThanOrEqualTo(2);
        // Node 1 should be first (direct, stronger)
        assertThat(activated.get(0).neighborIndex()).isEqualTo(1);
    }

    @Test
    void boundsCheckDoesNotCrash() {
        graph.strengthen(-1, 0, 1.0f); // out of bounds: ignored
        graph.strengthen(0, 1000, 1.0f); // out of bounds: ignored
        graph.strengthen(0, 0, 1.0f); // self-loop: ignored
        assertThat(graph.degree(0)).isZero();
        assertThat(graph.degree(-1)).isZero();
        assertThat(graph.degree(1000)).isZero();
        assertThat(graph.neighbors(-1)).isEmpty();
    }
}
