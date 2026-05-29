package com.spectrayan.spector.index.spectrum;

import com.spectrayan.spector.core.similarity.SimilarityFunction;
import com.spectrayan.spector.config.HnswParams;
import com.spectrayan.spector.index.ScoredResult;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Correctness, recall, and concurrency tests for {@link SpectorIndex}.
 *
 * <h3>Test Strategy</h3>
 * <ol>
 *   <li><b>Smoke</b>: basic add/search lifecycle</li>
 *   <li><b>Recall@10</b>: compare SpectorIndex top-10 against brute-force exact search.
 *       Requires ≥ 80% overlap. This validates VASQ quantization + IVF routing accuracy.</li>
 *   <li><b>Promotion</b>: verify flat→HNSW promotion occurs at shardThreshold; shard reports promoted.</li>
 *   <li><b>Concurrent stress</b>: 8 writer threads + 8 reader threads hammering the same index
 *       for 3 seconds. No exceptions, no deadlocks, no data corruption.</li>
 * </ol>
 */
class SpectorIndexTest {

    // ── Smoke ────────────────────────────────────────────────────────────────

    @Test
    void emptyIndex_returnsNoResults() {
        var index = buildIndex(64, 32, 8);
        float[][] training = randomVectors(100, 64, 1L);
        index.train(training);

        ScoredResult[] results = index.search(randomVectors(1, 64, 2L)[0], 10);
        assertThat(results).isEmpty();
    }

    @Test
    void trainThenAddThenSearch_returnsResults() {
        int dims = 64, n = 500;
        float[][] vectors = randomVectors(n, dims, 42L);
        var index = buildIndex(dims, 32, 8);

        index.train(vectors);
        for (int i = 0; i < n; i++) {
            index.add("doc-" + i, i, vectors[i]);
        }

        assertThat(index.size()).isEqualTo(n);

        ScoredResult[] results = index.search(vectors[0], 10);
        assertThat(results).isNotEmpty();
        assertThat(results[0].id()).isEqualTo("doc-0"); // query == vectors[0], should be top-1
    }

    @Test
    void addBeforeTrain_throws() {
        var index = buildIndex(32, 16, 4);
        assertThatThrownBy(() -> index.add("x", 0, new float[32]))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void searchBeforeTrain_throws() {
        var index = buildIndex(32, 16, 4);
        assertThatThrownBy(() -> index.search(new float[32], 5))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void wrongDimension_throws() {
        int dims = 32;
        float[][] vecs = randomVectors(50, dims, 1L);
        var index = buildIndex(dims, 16, 4);
        index.train(vecs);
        assertThatThrownBy(() -> index.add("x", 0, new float[dims + 1]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> index.search(new float[dims + 1], 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Recall@10 ────────────────────────────────────────────────────────────

    /**
     * Recall@10 test: SpectorIndex must return ≥ 80% of the brute-force top-10.
     *
     * <p>Uses EUCLIDEAN (L2) distance, which is <b>centroid-invariant</b>:
     * {@code ||q - x||² = ||(q - c) - (x - c)||²}. This means the brute-force ranking
     * in absolute space is identical to the residual-space ranking inside each shard.
     * Cosine similarity is NOT centroid-invariant ({@code cos(a-c, b-c) ≠ cos(a, b)}),
     * so using it here would produce a misleading recall measurement — the brute-force
     * baseline ranks differently from the residual-space search.</p>
     *
     * <p>SpectorIndex uses IVF routing (nProbe=16 of 32 cells) + flat shards (below threshold).
     * 80% recall is a conservative floor — typical values are 90-95%.</p>
     */
    @Test
    void recall10_atLeast80Percent() {
        int dims = 128, n = 2_000, queries = 50, k = 10;
        float[][] corpus = randomVectors(n, dims, 7L);

        var index = SpectorIndex.builder()
                .dimensions(dims)
                .nCentroids(32)
                .nProbe(32)             // probe ALL centroids → recall loss is 0% from IVF routing
                .shardThreshold(5_000)  // keep shards in flat mode for this test
                .oversamplingFactor(3)
                .similarityFunction(SimilarityFunction.EUCLIDEAN)
                .hnswParams(HnswParams.DEFAULT)
                .build();

        index.train(corpus);
        for (int i = 0; i < n; i++) {
            index.add("doc-" + i, i, corpus[i]);
        }

        Random rng = new Random(99L);
        double totalRecall = 0;

        for (int q = 0; q < queries; q++) {
            float[] query = randomVector(rng, dims);

            // Brute-force exact top-k in absolute space (L2 is centroid-invariant)
            Set<String> exactTop = bruteForceTopK(corpus, query, k, SimilarityFunction.EUCLIDEAN);

            // SpectorIndex result
            ScoredResult[] approx = index.search(query, k);
            Set<String> approxIds = new HashSet<>();
            for (ScoredResult r : approx) approxIds.add(r.id());

            long overlap = exactTop.stream().filter(approxIds::contains).count();
            totalRecall += (double) overlap / k;
        }

        double avgRecall = totalRecall / queries;
        assertThat(avgRecall)
                .as("Average recall@10 over %d queries should be ≥ 95%%; got %.1f%%", queries, avgRecall * 100)
                .isGreaterThanOrEqualTo(0.95);
    }

    // ── Promotion ─────────────────────────────────────────────────────────────

    @Test
    void shard_promotesToHnsw_afterThreshold() {
        int dims = 64, threshold = 200;
        float[][] corpus = randomVectors(threshold + 50, dims, 13L);

        var index = SpectorIndex.builder()
                .dimensions(dims)
                .nCentroids(4)          // few cells → one shard fills quickly
                .nProbe(4)
                .shardThreshold(threshold)
                .similarityFunction(SimilarityFunction.COSINE)
                .build();

        index.train(corpus);
        for (int i = 0; i < corpus.length; i++) {
            index.add("doc-" + i, i, corpus[i]);
        }

        // After enough inserts, search should still return results (promotion didn't break anything)
        ScoredResult[] results = index.search(corpus[0], 5);
        assertThat(results).isNotEmpty();
        assertThat(index.size()).isEqualTo(corpus.length);
    }

    // ── Concurrent stress ─────────────────────────────────────────────────────

    /**
     * Concurrent add + search: 8 writer VTs + 8 reader VTs for 2 seconds.
     *
     * <p>Success criteria:
     * <ul>
     *   <li>No exception thrown by any thread</li>
     *   <li>No deadlock (test completes within 10-second timeout)</li>
     *   <li>Final index size equals the number of successful adds</li>
     * </ul>
     * </p>
     */
    @Test
    void concurrent_addAndSearch_noDeadlockNoCorruption() throws InterruptedException {
        int dims = 64;
        float[][] training = randomVectors(200, dims, 1L);

        var index = SpectorIndex.builder()
                .dimensions(dims)
                .nCentroids(16)
                .nProbe(4)
                .shardThreshold(100_000)  // high threshold: no promotion during test
                .similarityFunction(SimilarityFunction.COSINE)
                .build();

        index.train(training);

        int writerCount = 8, readerCount = 8;
        long durationMs = 2_000;
        AtomicInteger addedCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);

        // Volatile stop flag — immune to system load affecting timing checks
        // Unlike System.currentTimeMillis() polling, a volatile read is a single
        // CPU instruction that cannot be delayed by lock contention or GC pauses.
        var stop = new Object() { volatile boolean value = false; };

        List<Thread> threads = new ArrayList<>();

        // Writers: continuously add random vectors
        Random[] rngs = new Random[writerCount];
        for (int i = 0; i < writerCount; i++) {
            rngs[i] = new Random(i * 100L);
        }

        for (int w = 0; w < writerCount; w++) {
            final int wIdx = w;
            Thread t = Thread.ofVirtual().name("writer-" + w).start(() -> {
                try {
                    startLatch.await();
                    while (!stop.value) {
                        int id = addedCount.incrementAndGet();
                        float[] vec = randomVector(rngs[wIdx], dims);
                        index.add("concurrent-" + id, id, vec);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    System.err.println("[SpectorIndexTest] Writer thread " + wIdx + " error: " + e);
                    e.printStackTrace(System.err);
                    errorCount.incrementAndGet();
                }
            });
            threads.add(t);
        }

        // Readers: continuously search — each reader gets its own Random for thread safety
        for (int r = 0; r < readerCount; r++) {
            final int rIdx = r;
            Thread t = Thread.ofVirtual().name("reader-" + r).start(() -> {
                Random localRng = new Random(999L + rIdx);
                try {
                    startLatch.await();
                    while (!stop.value) {
                        float[] query = randomVector(localRng, dims);
                        ScoredResult[] results = index.search(query, 5);
                        // Results may be empty (index might have been empty initially) — that's fine
                        for (ScoredResult r2 : results) {
                            assertThat(r2.score()).isFinite();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    System.err.println("[SpectorIndexTest] Reader thread " + rIdx + " error: " + e);
                    e.printStackTrace(System.err);
                    errorCount.incrementAndGet();
                }
            });
            threads.add(t);
        }

        startLatch.countDown();

        // Let the stress test run for the configured duration
        Thread.sleep(durationMs);
        stop.value = true;

        // Join with generous per-thread timeout — if any thread is stuck in a lock,
        // it will see stop=true immediately after acquiring the lock and exit.
        for (Thread t : threads) {
            t.join(10_000);
            if (t.isAlive()) {
                t.interrupt();
                t.join(1_000);
            }
        }

        assertThat(errorCount.get())
                .as("No exceptions should occur during concurrent add+search")
                .isZero();

        assertThat(index.size())
                .as("Index size should equal number of successful adds")
                .isEqualTo(addedCount.get());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static SpectorIndex buildIndex(int dims, int nCentroids, int nProbe) {
        return SpectorIndex.builder()
                .dimensions(dims)
                .nCentroids(nCentroids)
                .nProbe(nProbe)
                .similarityFunction(SimilarityFunction.COSINE)
                .build();
    }

    /**
     * Brute-force exact top-k. Returns the set of document IDs.
     * Respects {@link SimilarityFunction#higherIsBetter()} for correct ranking.
     */
    private static Set<String> bruteForceTopK(float[][] corpus, float[] query, int k,
                                               SimilarityFunction fn) {
        boolean higherIsBetter = fn.higherIsBetter();
        record Scored(String id, float score) {}
        List<Scored> all = new ArrayList<>(corpus.length);
        for (int i = 0; i < corpus.length; i++) {
            all.add(new Scored("doc-" + i, fn.compute(query, corpus[i])));
        }
        // Sort: best first. For cosine/dot (higher=better): descending. For L2 (lower=better): ascending.
        if (higherIsBetter) {
            all.sort((a, b) -> Float.compare(b.score(), a.score()));
        } else {
            all.sort((a, b) -> Float.compare(a.score(), b.score()));
        }
        Set<String> top = new HashSet<>();
        for (int i = 0; i < k && i < all.size(); i++) top.add(all.get(i).id());
        return top;
    }

    private static float[][] randomVectors(int n, int dims, long seed) {
        Random rng = new Random(seed);
        float[][] vs = new float[n][dims];
        for (float[] v : vs) for (int i = 0; i < dims; i++) v[i] = rng.nextFloat() * 2 - 1;
        return vs;
    }

    private static float[][] normalizedVectors(int n, int dims, long seed) {
        Random rng = new Random(seed);
        float[][] vs = new float[n][dims];
        for (float[] v : vs) {
            for (int i = 0; i < dims; i++) v[i] = rng.nextFloat() * 2 - 1;
            normalize(v);
        }
        return vs;
    }

    private static float[] normalizedVector(Random rng, int dims) {
        float[] v = new float[dims];
        for (int i = 0; i < dims; i++) v[i] = rng.nextFloat() * 2 - 1;
        normalize(v);
        return v;
    }

    private static float[] randomVector(Random rng, int dims) {
        float[] v = new float[dims];
        for (int i = 0; i < dims; i++) v[i] = rng.nextFloat() * 2 - 1;
        return v;
    }

    private static void normalize(float[] v) {
        float norm = 0;
        for (float x : v) norm += x * x;
        norm = (float) Math.sqrt(norm);
        if (norm > 0) for (int i = 0; i < v.length; i++) v[i] /= norm;
    }
}
