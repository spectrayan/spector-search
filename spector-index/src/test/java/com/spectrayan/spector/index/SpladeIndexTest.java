/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.spectrayan.spector.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Tests for {@link SpladeIndex} — learned sparse retrieval index.
 *
 * <p>Covers indexing, searching, scoring correctness, edge cases,
 * negative inputs, thread-safety, and read/write concurrency.</p>
 */
class SpladeIndexTest {

    private SpladeIndex index;

    @BeforeEach
    void setUp() {
        index = new SpladeIndex();
    }

    // ══════════════════════════════════════════════════════════════
    // Happy path
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Index and search — single matching term")
    void indexAndSearch_singleTerm() {
        index.indexSparse("doc-1", Map.of("java", 2.5f));
        ScoredResult[] results = index.searchSparse(Map.of("java", 1.0f), 10);

        assertThat(results).hasSize(1);
        assertThat(results[0].id()).isEqualTo("doc-1");
        assertThat(results[0].score()).isGreaterThan(0);
    }

    @Test
    @DisplayName("Index and search — multi-term document, partial query match")
    void indexAndSearch_multiTerm() {
        index.indexSparse("doc-1", Map.of(
                "java", 2.0f, "virtual", 1.5f, "machine", 1.0f));
        index.indexSparse("doc-2", Map.of(
                "python", 2.0f, "virtual", 0.5f, "environment", 1.0f));

        ScoredResult[] results = index.searchSparse(
                Map.of("java", 1.0f, "virtual", 1.0f), 10);

        // doc-1 matches both terms, doc-2 matches only "virtual"
        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        assertThat(results[0].id()).isEqualTo("doc-1"); // higher score
    }

    @Test
    @DisplayName("Inner product scoring — correct score computation")
    void innerProductScoring_correct() {
        index.indexSparse("doc-1", Map.of("a", 3.0f, "b", 2.0f));

        ScoredResult[] results = index.searchSparse(
                Map.of("a", 4.0f, "b", 5.0f), 10);

        // Score = 3.0*4.0 + 2.0*5.0 = 12 + 10 = 22
        assertThat(results).hasSize(1);
        assertThat(results[0].score()).isCloseTo(22.0f, within(0.01f));
    }

    @Test
    @DisplayName("Higher weight doc ranks higher")
    void ranksHigherWeightHigher() {
        index.indexSparse("weak", Map.of("term", 0.5f));
        index.indexSparse("strong", Map.of("term", 5.0f));

        ScoredResult[] results = index.searchSparse(Map.of("term", 1.0f), 10);

        assertThat(results[0].id()).isEqualTo("strong");
    }

    @Test
    @DisplayName("topK limits number of results")
    void topK_limits() {
        for (int i = 0; i < 20; i++) {
            index.indexSparse("doc-" + i, Map.of("shared", 1.0f + i * 0.1f));
        }

        ScoredResult[] results = index.searchSparse(Map.of("shared", 1.0f), 5);
        assertThat(results).hasSize(5);
    }

    @Test
    @DisplayName("Results sorted by score descending")
    void resultsSortedByScore() {
        for (int i = 0; i < 10; i++) {
            index.indexSparse("doc-" + i, Map.of("term", (float) (i + 1)));
        }

        ScoredResult[] results = index.searchSparse(Map.of("term", 1.0f), 10);

        for (int i = 1; i < results.length; i++) {
            assertThat(results[i - 1].score())
                    .isGreaterThanOrEqualTo(results[i].score());
        }
    }

    @Test
    @DisplayName("size() tracks document count correctly")
    void size_tracksCorrectly() {
        assertThat(index.size()).isEqualTo(0);
        index.indexSparse("d1", Map.of("a", 1.0f));
        assertThat(index.size()).isEqualTo(1);
        index.indexSparse("d2", Map.of("b", 1.0f));
        assertThat(index.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("remove — removes document from search results")
    void remove_removesFromPostings() {
        index.indexSparse("d1", Map.of("shared", 1.0f));
        index.indexSparse("d2", Map.of("shared", 2.0f));

        index.remove("d1");

        ScoredResult[] results = index.searchSparse(Map.of("shared", 1.0f), 10);
        assertThat(results).hasSize(1);
        assertThat(results[0].id()).isEqualTo("d2");
    }

    @Test
    @DisplayName("close() clears everything")
    void close_clearsEverything() {
        index.indexSparse("d1", Map.of("a", 1.0f));
        index.close();

        assertThat(index.size()).isEqualTo(0);
        assertThat(index.searchSparse(Map.of("a", 1.0f), 10)).isEmpty();
    }

    // ══════════════════════════════════════════════════════════════
    // Negative / Edge cases
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Empty index — search returns empty array")
    void emptyIndex_returnsEmpty() {
        assertThat(index.searchSparse(Map.of("anything", 1.0f), 10)).isEmpty();
    }

    @Test
    @DisplayName("Empty query sparse vector — returns empty")
    void emptyQuery_returnsEmpty() {
        index.indexSparse("d1", Map.of("java", 1.0f));
        assertThat(index.searchSparse(Map.of(), 10)).isEmpty();
    }

    @Test
    @DisplayName("No term overlap — returns empty")
    void noOverlap_returnsEmpty() {
        index.indexSparse("d1", Map.of("java", 1.0f));
        assertThat(index.searchSparse(Map.of("python", 1.0f), 10)).isEmpty();
    }

    @Test
    @DisplayName("Zero weight in sparse vec — term not indexed")
    void zeroWeight_ignored() {
        index.indexSparse("d1", Map.of("visible", 1.0f, "invisible", 0.0f));

        // "invisible" should not be searchable
        assertThat(index.searchSparse(Map.of("invisible", 1.0f), 10)).isEmpty();
        // "visible" should be searchable
        assertThat(index.searchSparse(Map.of("visible", 1.0f), 10)).hasSize(1);
    }

    @Test
    @DisplayName("Negative weight — term not indexed (weights must be positive)")
    void negativeWeight_ignored() {
        index.indexSparse("d1", Map.of("good", 1.0f, "bad", -0.5f));
        assertThat(index.searchSparse(Map.of("bad", 1.0f), 10)).isEmpty();
    }

    @Test
    @DisplayName("Re-index same ID — replaces previous content")
    void reindex_replacesPrevious() {
        index.indexSparse("d1", Map.of("old", 1.0f));
        index.indexSparse("d1", Map.of("new", 1.0f));

        assertThat(index.searchSparse(Map.of("old", 1.0f), 10)).isEmpty();
        assertThat(index.searchSparse(Map.of("new", 1.0f), 10)).hasSize(1);
        assertThat(index.size()).isEqualTo(1); // not doubled
    }

    // ══════════════════════════════════════════════════════════════
    // Compatibility shims
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Compatibility shim — index(id, text) works for basic text")
    void compatibilityShim_index() {
        index.index("d1", "the quick brown fox");

        ScoredResult[] results = index.search("quick fox", 10);
        assertThat(results).isNotEmpty();
        assertThat(results[0].id()).isEqualTo("d1");
    }

    // ══════════════════════════════════════════════════════════════
    // Concurrency
    // ══════════════════════════════════════════════════════════════

    @RepeatedTest(3)
    @DisplayName("Concurrent read/write — no crashes or data corruption")
    void concurrentReadWrite_safe() throws InterruptedException {
        int writerCount = 10, readerCount = 10, opsPerThread = 100;
        var latch = new CountDownLatch(writerCount + readerCount);
        var errors = new CopyOnWriteArrayList<Throwable>();

        // Writers
        for (int w = 0; w < writerCount; w++) {
            final int wId = w;
            Thread.startVirtualThread(() -> {
                try {
                    for (int i = 0; i < opsPerThread; i++) {
                        index.indexSparse("doc-" + wId + "-" + i,
                                Map.of("term-" + (i % 10), 1.0f + i * 0.1f));
                    }
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    latch.countDown();
                }
            });
        }

        // Readers
        for (int r = 0; r < readerCount; r++) {
            Thread.startVirtualThread(() -> {
                try {
                    for (int i = 0; i < opsPerThread; i++) {
                        index.searchSparse(Map.of("term-" + (i % 10), 1.0f), 5);
                    }
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(30, TimeUnit.SECONDS))
                .as("All threads should complete within 30 seconds")
                .isTrue();
        assertThat(errors)
                .as("No errors during concurrent read/write")
                .isEmpty();
    }
}
