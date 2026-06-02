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
package com.spectrayan.spector.memory.e2e;

import com.spectrayan.spector.memory.*;

import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static com.spectrayan.spector.memory.e2e.E2EAssertions.*;
import static org.assertj.core.api.Assertions.*;

/**
 * E2E tests for persistence: WAL integrity, reflect/consolidation, and
 * DISK mode save → close → reload round-trip.
 */
@DisplayName("🧠 E2E: Persistence, WAL & Reflect")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PersistenceE2ETest extends AbstractE2ETest {

    // ══════════════════════════════════════════════════════════════
    // WAL INTEGRITY
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("WAL contains at least one event per ingested memory")
    void walContainsEvents() {
        int walSize = memory.wal().size();
        log.info("WAL size: {} events (seed memories: {})", walSize, seedMemories.size());

        assertThat(walSize)
                .as("WAL should have at least one event per ingested memory")
                .isGreaterThanOrEqualTo(seedMemories.size());
    }

    // ══════════════════════════════════════════════════════════════
    // REFLECT (SLEEP CONSOLIDATION)
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(10)
    @DisplayName("Reflect cycle completes with valid report")
    void reflectCycleCompletes() {
        ReflectReport report = memory.reflect();

        log.info("Reflect report: {}", report);
        assertThat(report).as("Reflect should return a report").isNotNull();
        assertThat(report.duration()).as("Report should have a duration").isNotNull();
        assertThat(report.duration().toNanos()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @Order(11)
    @DisplayName("Memories are still accessible after reflect")
    void memoriesAccessibleAfterReflect() {
        List<CognitiveResult> results = memory.recall("database optimization",
                RecallOptions.builder().topK(5).build());

        assertThat(results)
                .as("Memories should be accessible after reflect cycle")
                .isNotEmpty();
    }

    // ══════════════════════════════════════════════════════════════
    // DISK PERSISTENCE ROUND-TRIP
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(20)
    @DisplayName("DISK persistence: save → close → reload → recall")
    void diskPersistenceRoundTrip() throws Exception {
        Path testDataDir = Path.of(".test-data", "e2e-persistence-" + System.currentTimeMillis());
        Files.createDirectories(testDataDir);

        try {
            // 1. Create a DISK-mode memory and ingest a few memories
            SpectorMemory diskMemory = DefaultSpectorMemory.builder()
                    .dimensions(embeddingProvider.dimensions())
                    .embeddingProvider(embeddingProvider)
                    .persistenceMode(MemoryPersistenceMode.DISK)
                    .persistence(testDataDir)
                    .workingCapacity(20)
                    .episodicPartitionCapacity(100)
                    .semanticCapacity(50)
                    .proceduralCapacity(20)
                    .hebbianGraphCapacity(100)
                    .temporalChainCapacity(100)
                    .build();

            diskMemory.remember("persist-001", "This is a test memory for persistence validation",
                    MemoryType.EPISODIC, com.spectrayan.spector.memory.cortex.MemorySource.OBSERVED,
                    "test", "persistence").join();
            diskMemory.remember("persist-002", "Second test memory for round-trip verification",
                    MemoryType.SEMANTIC, com.spectrayan.spector.memory.cortex.MemorySource.REFLECTED,
                    "test", "verification").join();

            int countBefore = diskMemory.totalMemories();
            log.info("DISK memory count before save: {}", countBefore);

            // 2. Close (triggers save)
            diskMemory.close();

            // 3. Verify persistence files exist
            assertThat(Files.exists(testDataDir.resolve("memory-index.mem")))
                    .as("memory-index.mem should exist").isTrue();
            assertThat(Files.exists(testDataDir.resolve("hebbian.graph")))
                    .as("hebbian.graph should exist").isTrue();
            assertThat(Files.exists(testDataDir.resolve("temporal.chain")))
                    .as("temporal.chain should exist").isTrue();

            // 4. Reload from disk
            SpectorMemory reloaded = DefaultSpectorMemory.builder()
                    .dimensions(embeddingProvider.dimensions())
                    .embeddingProvider(embeddingProvider)
                    .persistenceMode(MemoryPersistenceMode.DISK)
                    .persistence(testDataDir)
                    .workingCapacity(20)
                    .episodicPartitionCapacity(100)
                    .semanticCapacity(50)
                    .proceduralCapacity(20)
                    .hebbianGraphCapacity(100)
                    .temporalChainCapacity(100)
                    .build();

            int countAfter = reloaded.totalMemories();
            log.info("DISK memory count after reload: {}", countAfter);

            assertThat(countAfter)
                    .as("Memory count should survive round-trip")
                    .isEqualTo(countBefore);

            // 5. Verify recall works after reload
            List<CognitiveResult> results = reloaded.recall("persistence test memory",
                    RecallOptions.builder().topK(5).build());

            assertThat(results).as("Recall should work after reload").isNotEmpty();
            assertRecallContainsAny(results, "persist-001", "persist-002");

            // 6. Verify specific ID is in the reloaded index
            assertThat(reloaded.index().locate("persist-001"))
                    .as("persist-001 should be in reloaded index").isNotNull();
            assertThat(reloaded.index().locate("persist-002"))
                    .as("persist-002 should be in reloaded index").isNotNull();

            reloaded.close();

        } finally {
            // Clean up test data directory
            deleteRecursively(testDataDir);
        }
    }

    @Test
    @Order(21)
    @DisplayName("Double close throws IllegalStateException (MemorySegment already closed)")
    void doubleCloseThrowsExpectedException() throws Exception {
        Path testDataDir = Path.of(".test-data", "e2e-double-close-" + System.currentTimeMillis());
        Files.createDirectories(testDataDir);

        try {
            SpectorMemory diskMemory = DefaultSpectorMemory.builder()
                    .dimensions(embeddingProvider.dimensions())
                    .embeddingProvider(embeddingProvider)
                    .persistenceMode(MemoryPersistenceMode.DISK)
                    .persistence(testDataDir)
                    .workingCapacity(10)
                    .episodicPartitionCapacity(50)
                    .semanticCapacity(20)
                    .proceduralCapacity(10)
                    .hebbianGraphCapacity(50)
                    .temporalChainCapacity(50)
                    .build();

            diskMemory.close();

            // HebbianGraph uses Panama MemorySegment which throws on double close
            assertThatThrownBy(diskMemory::close)
                    .as("Double close should throw due to MemorySegment already closed")
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            deleteRecursively(testDataDir);
        }
    }

    // ── Utility ──

    private static void deleteRecursively(Path dir) throws Exception {
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                        });
            }
        }
    }
}
