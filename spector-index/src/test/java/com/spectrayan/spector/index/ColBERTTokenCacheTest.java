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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Tests for {@link ColBERTTokenCache} — off-heap token embedding cache.
 *
 * <p>Covers put/get round-trip, LRU eviction, invalidation,
 * off-heap byte accounting, concurrent access, and edge cases.</p>
 */
class ColBERTTokenCacheTest {

    private static final int DIMS = 128;
    private ColBERTTokenCache cache;

    @BeforeEach
    void setUp() {
        cache = new ColBERTTokenCache(DIMS, 5); // small capacity for eviction tests
    }

    @AfterEach
    void tearDown() {
        cache.close();
    }

    // ══════════════════════════════════════════════════════════════
    // Put / Get round-trip
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Put and get — exact round-trip")
    void putGet_exactRoundTrip() {
        float[][] original = makeTokenEmbeddings(5, DIMS, 42);
        cache.put("doc-1", original);

        float[][] retrieved = cache.get("doc-1");

        assertThat(retrieved).isNotNull();
        assertThat(retrieved).hasNumberOfRows(original.length);
        for (int t = 0; t < original.length; t++) {
            for (int d = 0; d < DIMS; d++) {
                assertThat(retrieved[t][d]).isCloseTo(original[t][d], within(1e-6f));
            }
        }
    }

    @Test
    @DisplayName("Get — cache miss returns null")
    void get_cacheMissReturnsNull() {
        assertThat(cache.get("nonexistent")).isNull();
    }

    @Test
    @DisplayName("Contains — true after put, false before")
    void contains_correctAfterPut() {
        assertThat(cache.contains("doc-1")).isFalse();
        cache.put("doc-1", makeTokenEmbeddings(3, DIMS, 1));
        assertThat(cache.contains("doc-1")).isTrue();
    }

    @Test
    @DisplayName("Size — tracks entry count")
    void size_tracksEntryCount() {
        assertThat(cache.size()).isEqualTo(0);
        cache.put("a", makeTokenEmbeddings(2, DIMS, 1));
        cache.put("b", makeTokenEmbeddings(3, DIMS, 2));
        assertThat(cache.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("Put — overwrite existing entry")
    void put_overwriteExisting() {
        cache.put("doc-1", makeTokenEmbeddings(5, DIMS, 42));
        cache.put("doc-1", makeTokenEmbeddings(3, DIMS, 99)); // smaller

        float[][] retrieved = cache.get("doc-1");
        assertThat(retrieved).hasNumberOfRows(3);
        assertThat(cache.size()).isEqualTo(1);
    }

    // ══════════════════════════════════════════════════════════════
    // LRU Eviction
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("LRU eviction — oldest entry evicted when full")
    void lru_evictsOldest() {
        // Fill to capacity (5)
        for (int i = 0; i < 5; i++) {
            cache.put("doc-" + i, makeTokenEmbeddings(2, DIMS, i));
        }
        assertThat(cache.size()).isEqualTo(5);

        // Insert one more — doc-0 (oldest) should be evicted
        cache.put("doc-5", makeTokenEmbeddings(2, DIMS, 5));

        assertThat(cache.size()).isEqualTo(5);
        assertThat(cache.contains("doc-0")).isFalse(); // evicted
        assertThat(cache.contains("doc-5")).isTrue(); // new entry
    }

    @Test
    @DisplayName("LRU eviction — accessed entries survive eviction")
    void lru_accessedEntrySurvives() {
        // Fill to capacity
        for (int i = 0; i < 5; i++) {
            cache.put("doc-" + i, makeTokenEmbeddings(2, DIMS, i));
        }

        // Access doc-0 to make it recent
        cache.get("doc-0");

        // Insert new entry — doc-1 (now oldest accessed) should be evicted
        cache.put("doc-5", makeTokenEmbeddings(2, DIMS, 5));

        assertThat(cache.contains("doc-0")).isTrue(); // survived due to access
        assertThat(cache.contains("doc-1")).isFalse(); // evicted (oldest un-accessed)
    }

    // ══════════════════════════════════════════════════════════════
    // Off-heap accounting
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("offHeapBytes — tracks allocated memory")
    void offHeapBytes_tracksMemory() {
        assertThat(cache.offHeapBytes()).isEqualTo(0);

        cache.put("d1", makeTokenEmbeddings(10, DIMS, 1)); // 10 × 128 × 4 = 5120
        assertThat(cache.offHeapBytes()).isEqualTo(10L * DIMS * Float.BYTES);

        cache.put("d2", makeTokenEmbeddings(5, DIMS, 2)); // 5 × 128 × 4 = 2560
        assertThat(cache.offHeapBytes()).isEqualTo(15L * DIMS * Float.BYTES);
    }

    @Test
    @DisplayName("tokenDims — returns configured dimensionality")
    void tokenDims_returnsConfigured() {
        assertThat(cache.tokenDims()).isEqualTo(DIMS);
    }

    // ══════════════════════════════════════════════════════════════
    // Invalidation
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Invalidate — removes entry")
    void invalidate_removesEntry() {
        cache.put("doc-1", makeTokenEmbeddings(3, DIMS, 1));
        assertThat(cache.contains("doc-1")).isTrue();

        cache.invalidate("doc-1");
        assertThat(cache.contains("doc-1")).isFalse();
        assertThat(cache.get("doc-1")).isNull();
    }

    @Test
    @DisplayName("Invalidate — no-op for missing key")
    void invalidate_noOpForMissing() {
        cache.invalidate("nonexistent"); // should not throw
        assertThat(cache.size()).isEqualTo(0);
    }

    // ══════════════════════════════════════════════════════════════
    // Edge cases
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Put null embeddings — no-op")
    void put_nullEmbeddings_noOp() {
        cache.put("doc-1", null);
        assertThat(cache.size()).isEqualTo(0);
    }

    @Test
    @DisplayName("Put empty embeddings — no-op")
    void put_emptyEmbeddings_noOp() {
        cache.put("doc-1", new float[0][]);
        assertThat(cache.size()).isEqualTo(0);
    }

    @Test
    @DisplayName("Single token embedding — round trips correctly")
    void singleTokenEmbedding() {
        float[][] single = {{1.0f, 2.0f, 3.0f}};
        // Use 3-dim cache for this test
        try (var smallCache = new ColBERTTokenCache(3, 10)) {
            smallCache.put("tiny", single);
            float[][] result = smallCache.get("tiny");
            assertThat(result).hasNumberOfRows(1);
            assertThat(result[0][0]).isCloseTo(1.0f, within(1e-6f));
            assertThat(result[0][1]).isCloseTo(2.0f, within(1e-6f));
            assertThat(result[0][2]).isCloseTo(3.0f, within(1e-6f));
        }
    }

    @Test
    @DisplayName("Close — clears all entries")
    void close_clearsAll() {
        cache.put("d1", makeTokenEmbeddings(3, DIMS, 1));
        cache.put("d2", makeTokenEmbeddings(3, DIMS, 2));
        cache.close();
        assertThat(cache.size()).isEqualTo(0);
    }

    // ══════════════════════════════════════════════════════════════
    // ColBERTReranker integration (cache wiring)
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ColBERTReranker with cache — cache hit avoids re-encoding")
    void rerankerWithCache_cacheHitWorks() {
        try (var tokenCache = new ColBERTTokenCache(DIMS, 100)) {
            var countingProvider = new CountingTokenProvider(DIMS);
            var reranker = new ColBERTReranker(countingProvider, tokenCache);

            var candidates = java.util.List.of(
                    new ColBERTReranker.RerankCandidate("doc-1", "hello world", 0.5f)
            );

            // First call — should encode query + doc (2 calls)
            reranker.rerank("query text", candidates, 10);
            int firstCallCount = countingProvider.encodeCount();

            // Second call same docs — doc should be cached, only query re-encoded
            reranker.rerank("different query", candidates, 10);
            int secondCallCount = countingProvider.encodeCount() - firstCallCount;

            // First call: 1 query + 1 doc = 2 encodes
            assertThat(firstCallCount).isEqualTo(2);
            // Second call: 1 query + 0 docs (cached) = 1 encode
            assertThat(secondCallCount).isEqualTo(1);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Concurrency
    // ══════════════════════════════════════════════════════════════

    @RepeatedTest(3)
    @DisplayName("Concurrent put/get — no crashes or data corruption")
    void concurrentPutGet() throws InterruptedException {
        try (var bigCache = new ColBERTTokenCache(DIMS, 100)) {
            int threadCount = 20;
            var latch = new CountDownLatch(threadCount);
            var errors = new CopyOnWriteArrayList<Throwable>();

            for (int t = 0; t < threadCount; t++) {
                final int tId = t;
                Thread.startVirtualThread(() -> {
                    try {
                        for (int i = 0; i < 50; i++) {
                            String key = "doc-" + (tId * 50 + i);
                            float[][] emb = makeTokenEmbeddings(3, DIMS, tId * 50 + i);
                            bigCache.put(key, emb);

                            float[][] result = bigCache.get(key);
                            if (result != null) {
                                // Verify data integrity
                                assertThat(result).hasNumberOfRows(3);
                            }
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
    }

    // ── Test helpers ──

    private static float[][] makeTokenEmbeddings(int tokens, int dims, int seed) {
        float[][] emb = new float[tokens][dims];
        Random rng = new Random(seed);
        for (int t = 0; t < tokens; t++) {
            for (int d = 0; d < dims; d++) {
                emb[t][d] = rng.nextFloat() - 0.5f;
            }
        }
        return emb;
    }

    /**
     * Token provider that counts encode() calls for verifying cache behavior.
     */
    static class CountingTokenProvider implements com.spectrayan.spector.embed.TokenEmbeddingProvider {
        private final int dims;
        private int count = 0;

        CountingTokenProvider(int dims) {
            this.dims = dims;
        }

        @Override
        public synchronized com.spectrayan.spector.embed.TokenEmbeddingResult encode(String text) {
            count++;
            String[] tokens = text.split("\\s+");
            float[][] emb = new float[tokens.length][dims];
            Random rng = new Random(text.hashCode());
            for (int t = 0; t < tokens.length; t++) {
                for (int d = 0; d < dims; d++) emb[t][d] = rng.nextFloat() - 0.5f;
            }
            return new com.spectrayan.spector.embed.TokenEmbeddingResult(
                    emb, tokens, tokens.length, "counting-mock");
        }

        @Override
        public int tokenDimensions() { return dims; }

        @Override
        public String modelName() { return "counting-mock"; }

        synchronized int encodeCount() { return count; }
    }
}
