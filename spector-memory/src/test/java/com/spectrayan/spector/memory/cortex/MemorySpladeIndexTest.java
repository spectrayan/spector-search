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
package com.spectrayan.spector.memory.cortex;

import com.spectrayan.spector.memory.cortex.MemorySpladeIndex.SpladeCandidate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link MemorySpladeIndex} — per-partition SPLADE index manager.
 *
 * <p>Covers partitioning, cross-partition search, rebuild, concurrency,
 * and graceful degradation on empty/missing partitions.</p>
 */
class MemorySpladeIndexTest {

    private MemorySpladeIndex spladeIndex;

    @BeforeEach
    void setUp() {
        spladeIndex = new MemorySpladeIndex();
    }

    @AfterEach
    void tearDown() {
        spladeIndex.close();
    }

    // ══════════════════════════════════════════════════════════════
    // Happy path
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Index and search — single partition")
    void indexAndSearch_singlePartition() {
        spladeIndex.addPartition();
        spladeIndex.index(0, "doc-1",
                Map.of("java", 2.5f, "virtual", 1.5f));
        spladeIndex.index(0, "doc-2",
                Map.of("python", 2.0f, "virtual", 0.5f));

        List<SpladeCandidate> results = spladeIndex.search(
                Map.of("java", 1.0f, "virtual", 1.0f), 10);

        assertThat(results).isNotEmpty();
        assertThat(results.stream().map(SpladeCandidate::id))
                .contains("doc-1");
    }

    @Test
    @DisplayName("Search across multiple partitions")
    void searchAcrossPartitions() {
        spladeIndex.addPartition();
        spladeIndex.addPartition();
        spladeIndex.addPartition();

        spladeIndex.index(0, "p0-doc", Map.of("error", 2.0f, "handling", 1.5f));
        spladeIndex.index(1, "p1-doc", Map.of("error", 1.8f, "java", 1.0f));
        spladeIndex.index(2, "p2-doc", Map.of("error", 2.5f, "rust", 1.0f));

        List<SpladeCandidate> results = spladeIndex.search(
                Map.of("error", 1.0f), 10);

        assertThat(results).hasSize(3);
        assertThat(results.stream().map(SpladeCandidate::id))
                .containsExactlyInAnyOrder("p0-doc", "p1-doc", "p2-doc");
    }

    @Test
    @DisplayName("rebuildPartition — replaces index content")
    void rebuildPartition_replaces() {
        spladeIndex.addPartition();
        spladeIndex.index(0, "old-doc", Map.of("cats", 1.0f));

        spladeIndex.rebuildPartition(0, Map.of(
                "new-1", Map.of("dogs", 2.0f),
                "new-2", Map.of("dogs", 1.5f, "puppies", 1.0f)
        ));

        assertThat(spladeIndex.search(Map.of("cats", 1.0f), 10)).isEmpty();
        assertThat(spladeIndex.search(Map.of("dogs", 1.0f), 10)).hasSize(2);
    }

    @Test
    @DisplayName("addPartition — returns sequential indices")
    void addPartition_correctIndex() {
        int idx0 = spladeIndex.addPartition();
        int idx1 = spladeIndex.addPartition();
        int idx2 = spladeIndex.addPartition();

        assertThat(idx0).isZero();
        assertThat(idx1).isEqualTo(1);
        assertThat(idx2).isEqualTo(2);
        assertThat(spladeIndex.partitionCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("totalDocuments — spans all partitions")
    void totalDocuments_spansAll() {
        spladeIndex.addPartition();
        spladeIndex.addPartition();

        spladeIndex.index(0, "d1", Map.of("a", 1.0f));
        spladeIndex.index(0, "d2", Map.of("b", 1.0f));
        spladeIndex.index(1, "d3", Map.of("c", 1.0f));

        assertThat(spladeIndex.totalDocuments()).isEqualTo(3);
    }

    @Test
    @DisplayName("topK limits results")
    void topK_limitsResults() {
        spladeIndex.addPartition();
        for (int i = 0; i < 20; i++) {
            spladeIndex.index(0, "doc-" + i,
                    Map.of("shared", 1.0f + i * 0.1f));
        }

        List<SpladeCandidate> results = spladeIndex.search(
                Map.of("shared", 1.0f), 5);
        assertThat(results).hasSize(5);
    }

    @Test
    @DisplayName("Results sorted by score descending")
    void resultsSortedByScore() {
        spladeIndex.addPartition();
        spladeIndex.index(0, "weak", Map.of("term", 0.5f));
        spladeIndex.index(0, "strong", Map.of("term", 5.0f));
        spladeIndex.index(0, "medium", Map.of("term", 2.0f));

        List<SpladeCandidate> results = spladeIndex.search(
                Map.of("term", 1.0f), 10);

        for (int i = 1; i < results.size(); i++) {
            assertThat(results.get(i - 1).spladeScore())
                    .isGreaterThanOrEqualTo(results.get(i).spladeScore());
        }
    }

    @Test
    @DisplayName("Partition index tracked in candidate")
    void partitionIndex_trackedInCandidate() {
        spladeIndex.addPartition();
        spladeIndex.addPartition();

        spladeIndex.index(0, "p0-doc", Map.of("deploy", 1.0f));
        spladeIndex.index(1, "p1-doc", Map.of("deploy", 2.0f));

        List<SpladeCandidate> results = spladeIndex.search(
                Map.of("deploy", 1.0f), 10);

        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        boolean hasP0 = results.stream().anyMatch(c -> c.partitionIndex() == 0);
        boolean hasP1 = results.stream().anyMatch(c -> c.partitionIndex() == 1);
        assertThat(hasP0).isTrue();
        assertThat(hasP1).isTrue();
    }

    // ══════════════════════════════════════════════════════════════
    // Negative / Edge cases
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Empty partitions — returns empty results")
    void emptyPartitions_returnEmpty() {
        List<SpladeCandidate> results = spladeIndex.search(
                Map.of("anything", 1.0f), 10);
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("Empty index in partition — returns empty")
    void emptyIndex_inPartition() {
        spladeIndex.addPartition();
        List<SpladeCandidate> results = spladeIndex.search(
                Map.of("anything", 1.0f), 10);
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("ensurePartition — auto-creates partitions")
    void ensurePartition_autoCreates() {
        spladeIndex.index(5, "auto-doc",
                Map.of("auto", 1.0f, "created", 1.0f));

        assertThat(spladeIndex.partitionCount()).isGreaterThan(5);
        List<SpladeCandidate> results = spladeIndex.search(
                Map.of("auto", 1.0f), 10);
        assertThat(results).isNotEmpty();
    }

    @Test
    @DisplayName("close — clears all partitions")
    void close_clearsAllPartitions() {
        spladeIndex.addPartition();
        spladeIndex.index(0, "d1", Map.of("a", 1.0f));

        spladeIndex.close();

        assertThat(spladeIndex.partitionCount()).isEqualTo(0);
        assertThat(spladeIndex.search(Map.of("a", 1.0f), 10)).isEmpty();
    }

    // ══════════════════════════════════════════════════════════════
    // Concurrency
    // ══════════════════════════════════════════════════════════════

    @RepeatedTest(3)
    @DisplayName("Concurrent search across partitions — no crashes")
    void concurrentSearchAcrossPartitions() throws InterruptedException {
        // Populate 5 partitions with data
        for (int p = 0; p < 5; p++) {
            spladeIndex.addPartition();
            for (int d = 0; d < 20; d++) {
                spladeIndex.index(p, "p" + p + "-d" + d,
                        Map.of("shared", 1.0f + d * 0.1f, "p" + p, 2.0f));
            }
        }

        int threadCount = 20;
        var latch = new CountDownLatch(threadCount);
        var errors = new CopyOnWriteArrayList<Throwable>();

        for (int t = 0; t < threadCount; t++) {
            final int tId = t;
            Thread.startVirtualThread(() -> {
                try {
                    for (int i = 0; i < 50; i++) {
                        List<SpladeCandidate> results = spladeIndex.search(
                                Map.of("shared", 1.0f, "p" + (tId % 5), 1.0f), 10);
                        assertThat(results).isNotEmpty();
                    }
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(errors).isEmpty();
    }

    @RepeatedTest(3)
    @DisplayName("Concurrent index and search — no data corruption")
    void concurrentIndexAndSearch() throws InterruptedException {
        spladeIndex.addPartition();
        spladeIndex.addPartition();

        int writers = 5, readers = 10, opsPerThread = 50;
        var latch = new CountDownLatch(writers + readers);
        var errors = new CopyOnWriteArrayList<Throwable>();

        for (int w = 0; w < writers; w++) {
            final int wId = w;
            Thread.startVirtualThread(() -> {
                try {
                    for (int i = 0; i < opsPerThread; i++) {
                        spladeIndex.index(wId % 2, "w" + wId + "-d" + i,
                                Map.of("term-" + (i % 5), 1.0f + i * 0.05f));
                    }
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    latch.countDown();
                }
            });
        }

        for (int r = 0; r < readers; r++) {
            Thread.startVirtualThread(() -> {
                try {
                    for (int i = 0; i < opsPerThread; i++) {
                        spladeIndex.search(Map.of("term-" + (i % 5), 1.0f), 5);
                    }
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(errors).isEmpty();
    }
}
