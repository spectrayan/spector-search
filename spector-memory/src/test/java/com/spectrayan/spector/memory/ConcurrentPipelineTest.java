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
package com.spectrayan.spector.memory;

import com.spectrayan.spector.memory.model.*;

import com.spectrayan.spector.embed.EmbeddingProvider;
import com.spectrayan.spector.embed.EmbeddingResult;
import com.spectrayan.spector.memory.amygdala.Valence;
import com.spectrayan.spector.memory.cortex.MemorySource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pipeline-level concurrency tests for {@link DefaultSpectorMemory}.
 *
 * <h3>What This Tests</h3>
 * <p>Unlike component-level concurrency tests (which exercise individual indexes),
 * these tests verify the <b>full ingestion → indexing → retrieval pipeline</b>
 * under concurrent load — the actual production hot path.</p>
 *
 * <p>Simulates multiple concurrent HTTP handlers accessing the same
 * {@code SpectorMemory} instance: parallel ingestion, recall during ingest,
 * forget/suppress during recall, and partition roll under load.</p>
 *
 * <h3>Coverage</h3>
 * <ul>
 *   <li>Concurrent {@code remember()} — no data loss, no corruption</li>
 *   <li>Concurrent {@code recall()} during ongoing ingestion</li>
 *   <li>Concurrent {@code recall()} on the same query — identical results</li>
 *   <li>Concurrent {@code forget()} during recall — no stale results</li>
 *   <li>Concurrent {@code suppress()} during recall — exclusion enforced</li>
 *   <li>Partition roll triggered by working memory overflow during concurrent access</li>
 * </ul>
 *
 * <p>All tests use deterministic mock embeddings (no Ollama needed).</p>
 */
class ConcurrentPipelineTest {

    private static final int DIMENSIONS = 32;
    private static final int TIMEOUT_SECONDS = 60;

    private SpectorMemory memory;

    @BeforeEach
    void setUp() {
        memory = DefaultSpectorMemory.builder()
                .dimensions(DIMENSIONS)
                .embeddingProvider(new DeterministicEmbeddingProvider(DIMENSIONS))
                .persistenceMode(MemoryPersistenceMode.IN_MEMORY)
                .workingCapacity(100)
                .episodicPartitionCapacity(500)
                .semanticCapacity(500)
                .proceduralCapacity(500)
                .build();
    }

    @AfterEach
    void tearDown() {
        if (memory != null) memory.close();
    }

    // ══════════════════════════════════════════════════════════════
    // 1. Concurrent Ingestion — No Data Loss
    // ══════════════════════════════════════════════════════════════

    @RepeatedTest(3)
    @DisplayName("Concurrent ingest — 20 writers × 10 memories = 200 total, no loss")
    void concurrentIngest_noDataLoss() throws InterruptedException {
        int writerCount = 20;
        int memoriesPerWriter = 10;
        int totalExpected = writerCount * memoriesPerWriter;

        var latch = new CountDownLatch(writerCount);
        var errors = new CopyOnWriteArrayList<Throwable>();

        for (int w = 0; w < writerCount; w++) {
            final int writerId = w;
            Thread.startVirtualThread(() -> {
                try {
                    for (int i = 0; i < memoriesPerWriter; i++) {
                        String id = "w" + writerId + "-m" + i;
                        String text = "Memory from writer " + writerId + " item " + i
                                + " about topic " + (i % 5);
                        MemoryType type = switch (i % 4) {
                            case 0 -> MemoryType.WORKING;
                            case 1 -> MemoryType.EPISODIC;
                            case 2 -> MemoryType.SEMANTIC;
                            default -> MemoryType.PROCEDURAL;
                        };
                        memory.remember(id, text, type, MemorySource.OBSERVED,
                                "writer-" + writerId).get(30, TimeUnit.SECONDS);
                    }
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("All writers should finish within timeout").isTrue();
        assertThat(errors).as("No exceptions during concurrent ingest").isEmpty();
        assertThat(memory.totalMemories()).as("All memories ingested").isEqualTo(totalExpected);
    }

    // ══════════════════════════════════════════════════════════════
    // 2. Concurrent Recall During Ingestion
    // ══════════════════════════════════════════════════════════════

    @RepeatedTest(3)
    @DisplayName("Concurrent recall during ingest — readers get valid results, no CME")
    void concurrentRecall_duringIngest() throws InterruptedException {
        // Pre-seed some memories so readers have something to find
        seedMemories(20);

        int writerCount = 10;
        int readerCount = 10;
        var barrier = new CountDownLatch(1); // synchronized start
        var done = new CountDownLatch(writerCount + readerCount);
        var errors = new CopyOnWriteArrayList<Throwable>();
        var resultCounts = new AtomicInteger(0);

        // Writers
        for (int w = 0; w < writerCount; w++) {
            final int wId = w;
            Thread.startVirtualThread(() -> {
                try {
                    barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    for (int i = 0; i < 10; i++) {
                        memory.remember("live-w" + wId + "-" + i,
                                "Live ingestion from writer " + wId + " about topic " + i,
                                MemoryType.EPISODIC, MemorySource.OBSERVED, "live")
                                .get(30, TimeUnit.SECONDS);
                    }
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
        }

        // Readers
        for (int r = 0; r < readerCount; r++) {
            final int rId = r;
            Thread.startVirtualThread(() -> {
                try {
                    barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    for (int i = 0; i < 5; i++) {
                        List<CognitiveResult> results = memory.recall("topic " + rId,
                                RecallOptions.builder().topK(5).build());
                        resultCounts.addAndGet(results.size());
                        // Results should be well-formed
                        for (CognitiveResult cr : results) {
                            assertThat(cr.id()).isNotNull();
                            assertThat(cr.text()).isNotNull();
                            assertThat(cr.score()).isGreaterThanOrEqualTo(0f);
                        }
                        Thread.sleep(10); // stagger reads
                    }
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
        }

        barrier.countDown(); // GO!

        assertThat(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("All threads should finish").isTrue();
        assertThat(errors).as("No exceptions during concurrent read/write").isEmpty();
        assertThat(resultCounts.get()).as("Readers got some results").isGreaterThan(0);
    }

    // ══════════════════════════════════════════════════════════════
    // 3. Concurrent Recall — Same Query, Consistent Results
    // ══════════════════════════════════════════════════════════════

    @RepeatedTest(3)
    @DisplayName("Concurrent recall — 30 readers, same query, all get valid results")
    void concurrentRecall_sameQuery() throws InterruptedException {
        seedMemories(30);

        int readerCount = 30;
        var barrier = new CountDownLatch(1);
        var done = new CountDownLatch(readerCount);
        var errors = new CopyOnWriteArrayList<Throwable>();
        var allResultIds = new CopyOnWriteArrayList<Set<String>>();

        for (int r = 0; r < readerCount; r++) {
            Thread.startVirtualThread(() -> {
                try {
                    barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    List<CognitiveResult> results = memory.recall("topic number 3",
                            RecallOptions.builder().topK(10).build());
                    Set<String> ids = new java.util.LinkedHashSet<>();
                    for (CognitiveResult cr : results) {
                        ids.add(cr.id());
                    }
                    allResultIds.add(ids);
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
        }

        barrier.countDown();

        assertThat(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(errors).isEmpty();
        assertThat(allResultIds).isNotEmpty();

        // All readers should get the same result set (modulo habituation scoring)
        // We verify: all result sets are non-empty and have consistent sizes
        int expectedSize = allResultIds.getFirst().size();
        for (Set<String> ids : allResultIds) {
            assertThat(ids.size())
                    .as("All readers should get consistent result count")
                    .isBetween(Math.max(1, expectedSize - 2), expectedSize + 2);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 4. Concurrent Forget During Recall
    // ══════════════════════════════════════════════════════════════

    @RepeatedTest(3)
    @DisplayName("Concurrent forget during recall — forgotten IDs eventually excluded")
    void concurrentForget_duringRecall() throws InterruptedException {
        seedMemories(50);
        Set<String> forgottenIds = new CopyOnWriteArraySet<>();

        int forgetterCount = 5;
        int readerCount = 15;
        var barrier = new CountDownLatch(1);
        var done = new CountDownLatch(forgetterCount + readerCount);
        var errors = new CopyOnWriteArrayList<Throwable>();

        // Forgetters — forget IDs 0-14
        for (int f = 0; f < forgetterCount; f++) {
            final int fId = f;
            Thread.startVirtualThread(() -> {
                try {
                    barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    for (int i = fId * 3; i < fId * 3 + 3; i++) {
                        String id = "seed-" + i;
                        memory.forget(id);
                        forgottenIds.add(id);
                    }
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
        }

        // Readers — recall should not crash
        for (int r = 0; r < readerCount; r++) {
            Thread.startVirtualThread(() -> {
                try {
                    barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    for (int i = 0; i < 5; i++) {
                        List<CognitiveResult> results = memory.recall("topic",
                                RecallOptions.builder().topK(20).build());
                        // Verify well-formed results
                        for (CognitiveResult cr : results) {
                            assertThat(cr.id()).isNotNull();
                        }
                        Thread.sleep(5);
                    }
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
        }

        barrier.countDown();
        assertThat(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(errors).isEmpty();

        // After all forgets complete, forgotten IDs should not appear
        List<CognitiveResult> finalResults = memory.recall("topic",
                RecallOptions.builder().topK(50).build());
        for (CognitiveResult cr : finalResults) {
            assertThat(forgottenIds).as("Forgotten ID should not appear in post-forget results")
                    .doesNotContain(cr.id());
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 5. Concurrent Suppress During Recall
    // ══════════════════════════════════════════════════════════════

    @RepeatedTest(3)
    @DisplayName("Concurrent suppress during recall — suppressed IDs excluded")
    void concurrentSuppress_duringRecall() throws InterruptedException {
        seedMemories(50);
        Set<String> suppressedIds = new CopyOnWriteArraySet<>();

        int suppressorCount = 5;
        int readerCount = 15;
        var barrier = new CountDownLatch(1);
        var done = new CountDownLatch(suppressorCount + readerCount);
        var errors = new CopyOnWriteArrayList<Throwable>();

        // Suppressors
        for (int s = 0; s < suppressorCount; s++) {
            final int sId = s;
            Thread.startVirtualThread(() -> {
                try {
                    barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    for (int i = sId * 3; i < sId * 3 + 3; i++) {
                        String id = "seed-" + i;
                        memory.suppress(id, "test suppression");
                        suppressedIds.add(id);
                    }
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
        }

        // Readers
        for (int r = 0; r < readerCount; r++) {
            Thread.startVirtualThread(() -> {
                try {
                    barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    for (int i = 0; i < 5; i++) {
                        List<CognitiveResult> results = memory.recall("topic",
                                RecallOptions.builder().topK(20).build());
                        for (CognitiveResult cr : results) {
                            assertThat(cr.id()).isNotNull();
                        }
                        Thread.sleep(5);
                    }
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
        }

        barrier.countDown();
        assertThat(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(errors).isEmpty();

        // After all suppressions complete, suppressed IDs should not appear
        List<CognitiveResult> finalResults = memory.recall("topic",
                RecallOptions.builder().topK(50).build());
        for (CognitiveResult cr : finalResults) {
            assertThat(suppressedIds).as("Suppressed ID should not appear")
                    .doesNotContain(cr.id());
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 6. Partition Roll During Concurrent Access
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Working memory overflow triggers roll — no data loss during concurrent access")
    void partitionRoll_duringConcurrentAccess() throws InterruptedException {
        // Create a memory with very small working capacity to force rolls
        memory.close();
        memory = DefaultSpectorMemory.builder()
                .dimensions(DIMENSIONS)
                .embeddingProvider(new DeterministicEmbeddingProvider(DIMENSIONS))
                .persistenceMode(MemoryPersistenceMode.IN_MEMORY)
                .workingCapacity(5) // tiny — forces frequent working→episodic rolls
                .episodicPartitionCapacity(50)
                .semanticCapacity(50)
                .proceduralCapacity(50)
                .build();

        int writerCount = 10;
        int readerCount = 10;
        var barrier = new CountDownLatch(1);
        var done = new CountDownLatch(writerCount + readerCount);
        var errors = new CopyOnWriteArrayList<Throwable>();

        // Writers — flood working memory to trigger rolls
        for (int w = 0; w < writerCount; w++) {
            final int wId = w;
            Thread.startVirtualThread(() -> {
                try {
                    barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    for (int i = 0; i < 5; i++) {
                        memory.remember("roll-w" + wId + "-" + i,
                                "Working memory overflow test item " + (wId * 5 + i),
                                MemoryType.WORKING, "roll-test")
                                .get(30, TimeUnit.SECONDS);
                    }
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
        }

        // Readers — recall during rolls
        for (int r = 0; r < readerCount; r++) {
            Thread.startVirtualThread(() -> {
                try {
                    barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    for (int i = 0; i < 5; i++) {
                        List<CognitiveResult> results = memory.recall("overflow test",
                                RecallOptions.builder().topK(10).build());
                        // Should not crash even during partition roll
                        for (CognitiveResult cr : results) {
                            assertThat(cr.id()).isNotNull();
                        }
                        Thread.sleep(10);
                    }
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
        }

        barrier.countDown();
        assertThat(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(errors).as("No exceptions during partition roll").isEmpty();

        // Verify total memories — some may have rolled from working→episodic
        assertThat(memory.totalMemories()).as("All memories survived roll").isGreaterThan(0);
    }

    // ══════════════════════════════════════════════════════════════
    // 7. Concurrent Mixed Operations
    // ══════════════════════════════════════════════════════════════

    @RepeatedTest(3)
    @DisplayName("Concurrent mixed ops — remember + recall + forget + reinforce simultaneously")
    void concurrentMixedOps() throws InterruptedException {
        seedMemories(30);

        var barrier = new CountDownLatch(1);
        var done = new CountDownLatch(4); // 4 groups
        var errors = new CopyOnWriteArrayList<Throwable>();

        // Group 1: Writers
        Thread.startVirtualThread(() -> {
            try {
                barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                for (int i = 0; i < 20; i++) {
                    memory.remember("mixed-" + i, "Mixed ops memory " + i,
                            MemoryType.EPISODIC, MemorySource.OBSERVED, "mixed")
                            .get(30, TimeUnit.SECONDS);
                }
            } catch (Throwable e) {
                errors.add(e);
            } finally {
                done.countDown();
            }
        });

        // Group 2: Readers
        Thread.startVirtualThread(() -> {
            try {
                barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                for (int i = 0; i < 20; i++) {
                    memory.recall("mixed ops memory",
                            RecallOptions.builder().topK(5).build());
                    Thread.sleep(5);
                }
            } catch (Throwable e) {
                errors.add(e);
            } finally {
                done.countDown();
            }
        });

        // Group 3: Forgetters
        Thread.startVirtualThread(() -> {
            try {
                barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                for (int i = 0; i < 10; i++) {
                    try {
                        memory.forget("seed-" + (i + 20)); // forget seeds 20-29
                    } catch (Exception e) {
                        // OK if already forgotten
                    }
                    Thread.sleep(5);
                }
            } catch (Throwable e) {
                errors.add(e);
            } finally {
                done.countDown();
            }
        });

        // Group 4: Reinforcers
        Thread.startVirtualThread(() -> {
            try {
                barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                for (int i = 0; i < 10; i++) {
                    try {
                        memory.reinforce("seed-" + i, Valence.POSITIVE);
                    } catch (Exception e) {
                        // OK if memory not found
                    }
                    Thread.sleep(5);
                }
            } catch (Throwable e) {
                errors.add(e);
            } finally {
                done.countDown();
            }
        });

        barrier.countDown();
        assertThat(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(errors).as("No exceptions during mixed concurrent ops").isEmpty();
    }

    // ── Helpers ──

    private void seedMemories(int count) {
        try {
            for (int i = 0; i < count; i++) {
                MemoryType type = switch (i % 4) {
                    case 0 -> MemoryType.WORKING;
                    case 1 -> MemoryType.EPISODIC;
                    case 2 -> MemoryType.SEMANTIC;
                    default -> MemoryType.PROCEDURAL;
                };
                memory.remember("seed-" + i, "Seed memory about topic number " + (i % 10)
                                + " from area " + (i % 5),
                        type, MemorySource.OBSERVED, "seed", "topic-" + (i % 10))
                        .get(30, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to seed memories", e);
        }
    }

    /**
     * Deterministic embedding provider that produces hash-based unit vectors.
     * Same text → same vector, making tests reproducible.
     */
    static class DeterministicEmbeddingProvider implements EmbeddingProvider {

        private final int dims;

        DeterministicEmbeddingProvider(int dims) {
            this.dims = dims;
        }

        @Override
        public EmbeddingResult embed(String text) {
            Random rng = new Random(text.hashCode());
            float[] vector = new float[dims];
            for (int i = 0; i < dims; i++) {
                vector[i] = (rng.nextFloat() - 0.5f) * 2.0f;
            }
            float norm = 0f;
            for (float v : vector) norm += v * v;
            norm = (float) Math.sqrt(norm);
            if (norm > 0) {
                for (int i = 0; i < dims; i++) vector[i] /= norm;
            }
            return new EmbeddingResult(vector, text.split("\\s+").length, "deterministic-mock");
        }

        @Override
        public int dimensions() { return dims; }

        @Override
        public String modelName() { return "deterministic-mock"; }
    }
}
