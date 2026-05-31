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
package com.spectrayan.spector.index.ivf;

import com.spectrayan.spector.core.similarity.SimilarityFunction;
import com.spectrayan.spector.index.ScoredResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Concurrent add + search stress tests for the IVF family of indexes.
 *
 * <p>These tests exercise the {@code StampedLock} optimistic-read path by running
 * concurrent writers (triggering writeLock) and readers (triggering the
 * optimistic-read fast path and falling back to readLock when a race is detected).
 * They validate that:</p>
 * <ul>
 *   <li>No {@link NullPointerException} or {@link ArrayIndexOutOfBoundsException}
 *       occurs from torn reads of the posting lists</li>
 *   <li>No deadlock occurs under sustained concurrent load</li>
 *   <li>All search results have finite scores (no NaN/Inf from partial reads)</li>
 * </ul>
 */
class IvfConcurrencyTest {

    private static final int DIMS         = 64;
    private static final int TRAIN_N      = 300;
    private static final int NUM_CELLS    = 16;
    private static final int DURATION_MS  = 1_500;
    private static final int WRITERS      = 4;
    private static final int READERS      = 8;

    // ── IvfFlatIndex ──────────────────────────────────────────────────────────

    @Test
    @Timeout(15)
    void ivfFlat_concurrent_addAndSearch_noCorruption() throws InterruptedException {
        float[][] training = randomVectors(TRAIN_N, DIMS, 1L);
        var index = new IvfFlatIndex(DIMS, SimilarityFunction.COSINE);
        index.train(training, NUM_CELLS);

        runConcurrentStress(
                index::search,
                (id, storeIdx, vec) -> index.add(id, storeIdx, vec),
                DIMS, WRITERS, READERS, DURATION_MS
        );
    }

    // ── IvfPqIndex ────────────────────────────────────────────────────────────

    @Test
    @Timeout(15)
    void ivfPq_concurrent_addAndSearch_noCorruption() throws InterruptedException {
        float[][] training = randomVectors(TRAIN_N, DIMS, 2L);
        var index = new IvfPqIndex(DIMS, NUM_CELLS, 8 /* nProbe */, 8 /* M */,
                SimilarityFunction.COSINE);
        index.train(training);

        runConcurrentStress(
                (q, k) -> index.search(q, k),
                (id, storeIdx, vec) -> index.add(id, storeIdx, vec),
                DIMS, WRITERS, READERS, DURATION_MS
        );
    }

    // ── QuantizedIvfPqIndex ───────────────────────────────────────────────────

    @Test
    @Timeout(15)
    void quantizedIvfPq_concurrent_addAndSearch_noCorruption() throws InterruptedException {
        float[][] training = randomVectors(TRAIN_N, DIMS, 3L);
        var index = new QuantizedIvfPqIndex(DIMS, NUM_CELLS, 8 /* nProbe */, 8 /* M */,
                SimilarityFunction.COSINE);
        index.train(training);

        runConcurrentStress(
                (q, k) -> index.search(q, k),
                (id, storeIdx, vec) -> index.add(id, storeIdx, vec),
                DIMS, WRITERS, READERS, DURATION_MS
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @FunctionalInterface
    interface Searcher {
        ScoredResult[] search(float[] query, int k);
    }

    @FunctionalInterface
    interface Adder {
        void add(String id, int storeIndex, float[] vector);
    }

    private static void runConcurrentStress(Searcher searcher, Adder adder,
                                             int dims, int writers, int readers,
                                             long durationMs) throws InterruptedException {
        AtomicInteger errorCount  = new AtomicInteger(0);
        AtomicInteger addedCount  = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Thread> threads      = new ArrayList<>();

        // Writers
        for (int w = 0; w < writers; w++) {
            final Random rng = new Random(w * 77L);
            Thread t = Thread.ofVirtual().start(() -> {
                try {
                    startLatch.await();
                    long end = System.currentTimeMillis() + durationMs;
                    while (System.currentTimeMillis() < end) {
                        int id = addedCount.incrementAndGet();
                        adder.add("doc-" + id, id, randomVector(rng, dims));
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    System.err.println("Writer error: " + e);
                }
            });
            threads.add(t);
        }

        // Readers
        for (int r = 0; r < readers; r++) {
            final Random rng = new Random(r * 33L + 1000);
            Thread t = Thread.ofVirtual().start(() -> {
                try {
                    startLatch.await();
                    long end = System.currentTimeMillis() + durationMs;
                    while (System.currentTimeMillis() < end) {
                        float[] query = randomVector(rng, dims);
                        ScoredResult[] results = searcher.search(query, 5);
                        for (ScoredResult result : results) {
                            if (!Float.isFinite(result.score())) {
                                errorCount.incrementAndGet();
                            }
                        }
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    System.err.println("Reader error: " + e);
                }
            });
            threads.add(t);
        }

        startLatch.countDown();
        for (Thread t : threads) t.join();

        assertThat(errorCount.get())
                .as("No errors (data races, NPE, AIOOBE, NaN scores) during concurrent add+search")
                .isZero();
    }

    private static float[][] randomVectors(int n, int dims, long seed) {
        Random rng = new Random(seed);
        float[][] vs = new float[n][dims];
        for (float[] v : vs) for (int i = 0; i < dims; i++) v[i] = rng.nextFloat() * 2 - 1;
        return vs;
    }

    private static float[] randomVector(Random rng, int dims) {
        float[] v = new float[dims];
        for (int i = 0; i < dims; i++) v[i] = rng.nextFloat() * 2 - 1;
        return v;
    }
}
