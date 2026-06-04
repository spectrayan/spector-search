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

import com.spectrayan.spector.memory.cortex.MemoryBM25Index.BM25Candidate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link MemoryBM25Index} — per-partition BM25 index manager.
 */
class MemoryBM25IndexTest {

    private MemoryBM25Index bm25Index;

    @BeforeEach
    void setUp() {
        bm25Index = new MemoryBM25Index();
    }

    @AfterEach
    void tearDown() {
        bm25Index.close();
    }

    @Test
    void index_and_search_single_partition() {
        bm25Index.addPartition();
        bm25Index.index(0, "doc-1", "The quick brown fox jumps over the lazy dog");
        bm25Index.index(0, "doc-2", "A lazy dog sleeps in the sun");
        bm25Index.index(0, "doc-3", "The fox is quick and clever");

        List<BM25Candidate> results = bm25Index.search("lazy dog", 10);

        assertThat(results).isNotEmpty();
        // Both doc-1 and doc-2 mention "lazy dog"
        assertThat(results.stream().map(BM25Candidate::id))
                .contains("doc-1", "doc-2");
    }

    @Test
    void search_across_multiple_partitions() {
        bm25Index.addPartition();
        bm25Index.addPartition();
        bm25Index.addPartition();

        bm25Index.index(0, "p0-doc", "error handling in Python");
        bm25Index.index(1, "p1-doc", "Java exception error handling best practices");
        bm25Index.index(2, "p2-doc", "Rust error handling with Result types");

        List<BM25Candidate> results = bm25Index.search("error handling", 10);

        assertThat(results).hasSize(3);
        // All three should be found across all partitions
        assertThat(results.stream().map(BM25Candidate::id))
                .containsExactlyInAnyOrder("p0-doc", "p1-doc", "p2-doc");
    }

    @Test
    void empty_partitions_return_empty_results() {
        List<BM25Candidate> results = bm25Index.search("anything", 10);
        assertThat(results).isEmpty();
    }

    @Test
    void empty_index_in_partition_returns_empty() {
        bm25Index.addPartition();
        List<BM25Candidate> results = bm25Index.search("anything", 10);
        assertThat(results).isEmpty();
    }

    @Test
    void rebuildPartition_replaces_index() {
        bm25Index.addPartition();
        bm25Index.index(0, "old-doc", "old content about cats");

        // Rebuild partition with new data
        bm25Index.rebuildPartition(0, Map.of(
                "new-doc-1", "new content about dogs",
                "new-doc-2", "more content about dogs and puppies"
        ));

        // Old content should not be found
        List<BM25Candidate> oldResults = bm25Index.search("cats", 10);
        assertThat(oldResults).isEmpty();

        // New content should be found
        List<BM25Candidate> newResults = bm25Index.search("dogs", 10);
        assertThat(newResults).hasSize(2);
    }

    @Test
    void addPartition_returns_correct_index() {
        int idx0 = bm25Index.addPartition();
        int idx1 = bm25Index.addPartition();
        int idx2 = bm25Index.addPartition();

        assertThat(idx0).isZero();
        assertThat(idx1).isEqualTo(1);
        assertThat(idx2).isEqualTo(2);
        assertThat(bm25Index.partitionCount()).isEqualTo(3);
    }

    @Test
    void totalDocuments_spans_all_partitions() {
        bm25Index.addPartition();
        bm25Index.addPartition();

        bm25Index.index(0, "d1", "text one");
        bm25Index.index(0, "d2", "text two");
        bm25Index.index(1, "d3", "text three");

        assertThat(bm25Index.totalDocuments()).isEqualTo(3);
    }

    @Test
    void topK_limits_results() {
        bm25Index.addPartition();
        for (int i = 0; i < 20; i++) {
            bm25Index.index(0, "doc-" + i, "the common keyword appears here number " + i);
        }

        List<BM25Candidate> results = bm25Index.search("common keyword", 5);
        assertThat(results).hasSize(5);
    }

    @Test
    void results_sorted_by_score_descending() {
        bm25Index.addPartition();
        bm25Index.index(0, "weak", "some text about other things");
        bm25Index.index(0, "strong", "error error error handling error debugging");
        bm25Index.index(0, "medium", "single error mention");

        List<BM25Candidate> results = bm25Index.search("error", 10);

        // Verify descending score order
        for (int i = 1; i < results.size(); i++) {
            assertThat(results.get(i).bm25Score())
                    .isLessThanOrEqualTo(results.get(i - 1).bm25Score());
        }
    }

    @Test
    void partition_index_tracked_in_candidates() {
        bm25Index.addPartition();
        bm25Index.addPartition();

        bm25Index.index(0, "p0-doc", "deployment pipeline error");
        bm25Index.index(1, "p1-doc", "deployment pipeline fix");

        List<BM25Candidate> results = bm25Index.search("deployment pipeline", 10);

        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        boolean hasPartition0 = results.stream().anyMatch(c -> c.partitionIndex() == 0);
        boolean hasPartition1 = results.stream().anyMatch(c -> c.partitionIndex() == 1);
        assertThat(hasPartition0).isTrue();
        assertThat(hasPartition1).isTrue();
    }

    @Test
    void ensurePartition_auto_creates() {
        // Index into partition 5 directly without calling addPartition
        bm25Index.index(5, "auto-doc", "auto-created partition content");

        assertThat(bm25Index.partitionCount()).isGreaterThan(5);
        List<BM25Candidate> results = bm25Index.search("auto-created partition", 10);
        assertThat(results).isNotEmpty();
    }
}
