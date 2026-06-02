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
package com.spectrayan.spector.memory.temporal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for TemporalChain: linking, traversal, and persistence.
 */
class TemporalChainTest {

    @TempDir
    Path tempDir;

    private TemporalChain chain;

    @BeforeEach
    void setUp() {
        chain = new TemporalChain(100);
    }

    @AfterEach
    void tearDown() {
        chain.close();
    }

    @Test
    void initialStateIsUnlinked() {
        assertThat(chain.isLinked(0)).isFalse();
        assertThat(chain.isLinked(99)).isFalse();
    }

    @Test
    void linkCreatesChain() {
        // Simulate session: memory 0 → memory 1 → memory 2
        chain.link(1, 0, 1);
        chain.link(2, 1, 1);

        assertThat(chain.isLinked(0)).isTrue();
        assertThat(chain.isLinked(1)).isTrue();
        assertThat(chain.isLinked(2)).isTrue();
    }

    @Test
    void followForwardTraversesChain() {
        chain.link(1, 0, 1);
        chain.link(2, 1, 1);
        chain.link(3, 2, 1);

        int[] forward = chain.followForward(0, 10);
        assertThat(forward).containsExactly(1, 2, 3);
    }

    @Test
    void followBackwardTraversesChain() {
        chain.link(1, 0, 1);
        chain.link(2, 1, 1);
        chain.link(3, 2, 1);

        int[] backward = chain.followBackward(3, 10);
        assertThat(backward).containsExactly(2, 1, 0);
    }

    @Test
    void maxHopsLimitsTraversal() {
        chain.link(1, 0, 1);
        chain.link(2, 1, 1);
        chain.link(3, 2, 1);
        chain.link(4, 3, 1);

        int[] limited = chain.followForward(0, 2);
        assertThat(limited).hasSize(2);
        assertThat(limited).containsExactly(1, 2);
    }

    @Test
    void sessionIdTracked() {
        chain.link(1, 0, 42);
        assertThat(chain.sessionOf(1)).isEqualTo(42);
        assertThat(chain.sessionOf(0)).isEqualTo(0); // prev not explicitly set
    }

    @Test
    void saveAndLoadPreservesChain() {
        chain.link(1, 0, 1);
        chain.link(2, 1, 1);
        chain.link(3, 2, 1);

        Path file = tempDir.resolve("test.chain");
        chain.save(file);
        chain.close();

        chain = TemporalChain.load(file, 100);
        assertThat(chain.followForward(0, 10)).containsExactly(1, 2, 3);
        assertThat(chain.followBackward(3, 10)).containsExactly(2, 1, 0);
    }

    @Test
    void loadNonExistentFileCreatesNew() {
        Path file = tempDir.resolve("nonexistent.chain");
        chain.close();
        chain = TemporalChain.load(file, 50);

        assertThat(chain.capacity()).isEqualTo(50);
        assertThat(chain.isLinked(0)).isFalse();
    }

    @Test
    void boundsCheckDoesNotCrash() {
        chain.link(-1, 0, 1); // ignored
        chain.link(0, 500, 1); // ignored (out of capacity)
        chain.link(0, 0, 1); // self-link: ignored
        assertThat(chain.isLinked(0)).isFalse();
        assertThat(chain.followForward(-1, 5)).isEmpty();
        assertThat(chain.followBackward(500, 5)).isEmpty();
    }

    @Test
    void multipleSessions() {
        // Session 1: 0 → 1 → 2
        chain.link(1, 0, 1);
        chain.link(2, 1, 1);

        // Session 2: 5 → 6 → 7 (separate chain)
        chain.link(6, 5, 2);
        chain.link(7, 6, 2);

        // Session 1 chain doesn't leak into session 2
        assertThat(chain.followForward(0, 10)).containsExactly(1, 2);
        assertThat(chain.followForward(5, 10)).containsExactly(6, 7);
    }

    @Test
    void capacityAccessor() {
        assertThat(chain.capacity()).isEqualTo(100);
    }
}
