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

import static org.assertj.core.api.Assertions.assertThat;

class HebbianGraphTest {

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
    void initialDegreeIsZero() {
        assertThat(graph.degree(0)).isZero();
    }

    @Test
    void strengthenCreatesBidirectionalEdge() {
        graph.strengthen(0, 1, 1.0f);
        assertThat(graph.degree(0)).isEqualTo(1);
        assertThat(graph.degree(1)).isEqualTo(1);
    }

    @Test
    void repeatedStrengthenIncreasesWeight() {
        graph.strengthen(0, 1, 1.0f);
        graph.strengthen(0, 1, 2.0f);

        var neighbors = graph.neighbors(0);
        assertThat(neighbors).hasSize(1);
        assertThat(neighbors.getFirst().weight()).isEqualTo(3.0f);
    }

    @Test
    void neighborsSortedByDescendingWeight() {
        graph.strengthen(0, 1, 1.0f);
        graph.strengthen(0, 2, 5.0f);
        graph.strengthen(0, 3, 3.0f);

        var neighbors = graph.neighbors(0);
        assertThat(neighbors).hasSize(3);
        assertThat(neighbors.get(0).weight()).isEqualTo(5.0f); // node 2
        assertThat(neighbors.get(1).weight()).isEqualTo(3.0f); // node 3
        assertThat(neighbors.get(2).weight()).isEqualTo(1.0f); // node 1
    }

    @Test
    void maxDegreeEnforced() {
        // Fill node 0 to MAX_DEGREE
        for (int i = 1; i <= HebbianGraph.MAX_DEGREE; i++) {
            graph.strengthen(0, i, 1.0f);
        }
        assertThat(graph.degree(0)).isEqualTo(HebbianGraph.MAX_DEGREE);

        // Adding one more with higher weight should replace weakest
        graph.strengthen(0, HebbianGraph.MAX_DEGREE + 1, 10.0f);
        assertThat(graph.degree(0)).isEqualTo(HebbianGraph.MAX_DEGREE);

        // The new strong edge should be present
        var neighbors = graph.neighbors(0);
        assertThat(neighbors.getFirst().weight()).isEqualTo(10.0f);
    }
}
