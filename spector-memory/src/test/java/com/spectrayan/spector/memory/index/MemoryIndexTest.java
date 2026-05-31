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
package com.spectrayan.spector.memory.index;

import com.spectrayan.spector.memory.MemoryType;
import com.spectrayan.spector.memory.cortex.MemorySource;
import com.spectrayan.spector.memory.index.MemoryIndex.MemoryLocation;

import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detailed unit tests for {@link MemoryIndex} — specifically the O(1) reverse index
 * (P1 optimization) and concurrent safety.
 */
@DisplayName("MemoryIndex — Reverse Index + Concurrent Safety")
class MemoryIndexTest {

    private MemoryIndex index;

    @BeforeEach
    void setUp() {
        index = new MemoryIndex();
    }

    // ══════════════════════════════════════════════════════════════
    // P1: O(1) Reverse Lookup
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("findIdByOffset returns correct ID for registered memory")
    void findIdByOffset_returnsCorrectId() {
        index.register("mem-1",
                new MemoryLocation(MemoryType.EPISODIC, 1024L, 0),
                "Hello world", MemorySource.OBSERVED, new String[]{"greeting"});

        assertThat(index.findIdByOffset(MemoryType.EPISODIC, 1024L)).isEqualTo("mem-1");
    }

    @Test
    @DisplayName("findIdByOffset returns null for unknown offset")
    void findIdByOffset_returnsNullForUnknown() {
        assertThat(index.findIdByOffset(MemoryType.EPISODIC, 9999L)).isNull();
    }

    @Test
    @DisplayName("findIdByOffset distinguishes memory types at same offset")
    void findIdByOffset_distinguishesTypes() {
        index.register("working-0",
                new MemoryLocation(MemoryType.WORKING, 0L, -1),
                "working", MemorySource.OBSERVED, new String[]{});
        index.register("episodic-0",
                new MemoryLocation(MemoryType.EPISODIC, 0L, 0),
                "episodic", MemorySource.OBSERVED, new String[]{});
        index.register("semantic-0",
                new MemoryLocation(MemoryType.SEMANTIC, 0L, -1),
                "semantic", MemorySource.OBSERVED, new String[]{});
        index.register("procedural-0",
                new MemoryLocation(MemoryType.PROCEDURAL, 0L, -1),
                "procedural", MemorySource.OBSERVED, new String[]{});

        assertThat(index.findIdByOffset(MemoryType.WORKING, 0L)).isEqualTo("working-0");
        assertThat(index.findIdByOffset(MemoryType.EPISODIC, 0L)).isEqualTo("episodic-0");
        assertThat(index.findIdByOffset(MemoryType.SEMANTIC, 0L)).isEqualTo("semantic-0");
        assertThat(index.findIdByOffset(MemoryType.PROCEDURAL, 0L)).isEqualTo("procedural-0");
    }

    @Test
    @DisplayName("findTextByOffset returns correct text via reverse index")
    void findTextByOffset_returnsText() {
        index.register("mem-abc",
                new MemoryLocation(MemoryType.SEMANTIC, 512L, -1),
                "Java is great", MemorySource.OBSERVED, new String[]{"java"});

        assertThat(index.findTextByOffset(MemoryType.SEMANTIC, 512L)).isEqualTo("Java is great");
    }

    @Test
    @DisplayName("findTextByOffset returns null for missing offset")
    void findTextByOffset_nullForMissing() {
        assertThat(index.findTextByOffset(MemoryType.SEMANTIC, 999L)).isNull();
    }

    // ══════════════════════════════════════════════════════════════
    // Remove cleans reverse index
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("remove cleans both forward and reverse index")
    void remove_cleansBothIndexes() {
        index.register("mem-1",
                new MemoryLocation(MemoryType.EPISODIC, 100L, 0),
                "hello", MemorySource.OBSERVED, new String[]{});

        assertThat(index.findIdByOffset(MemoryType.EPISODIC, 100L)).isEqualTo("mem-1");
        assertThat(index.locate("mem-1")).isNotNull();

        index.remove("mem-1");

        assertThat(index.findIdByOffset(MemoryType.EPISODIC, 100L)).isNull();
        assertThat(index.locate("mem-1")).isNull();
        assertThat(index.text("mem-1")).isEmpty();
    }

    @Test
    @DisplayName("remove of non-existent ID is safe")
    void remove_nonExistentIsSafe() {
        assertThatCode(() -> index.remove("does-not-exist")).doesNotThrowAnyException();
    }

    // ══════════════════════════════════════════════════════════════
    // Forward index operations
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("register and lookup all metadata fields")
    void register_allMetadata() {
        String[] tags = {"java", "performance"};
        index.register("mem-x",
                new MemoryLocation(MemoryType.PROCEDURAL, 256L, -1),
                "Always check nulls", MemorySource.PROCEDURAL, tags);

        assertThat(index.locate("mem-x").type()).isEqualTo(MemoryType.PROCEDURAL);
        assertThat(index.locate("mem-x").offset()).isEqualTo(256L);
        assertThat(index.text("mem-x")).isEqualTo("Always check nulls");
        assertThat(index.source("mem-x")).isEqualTo(MemorySource.PROCEDURAL);
        assertThat(index.tags("mem-x")).containsExactly("java", "performance");
    }

    @Test
    @DisplayName("source defaults to OBSERVED for unknown IDs")
    void source_defaultForUnknown() {
        assertThat(index.source("unknown")).isEqualTo(MemorySource.OBSERVED);
    }

    @Test
    @DisplayName("tags default to empty array for unknown IDs")
    void tags_defaultForUnknown() {
        assertThat(index.tags("unknown")).isEmpty();
    }

    @Test
    @DisplayName("size reflects registered entries")
    void size_tracksEntries() {
        assertThat(index.size()).isZero();
        index.register("a", new MemoryLocation(MemoryType.WORKING, 0, -1),
                "a", MemorySource.OBSERVED, new String[]{});
        assertThat(index.size()).isEqualTo(1);
        index.register("b", new MemoryLocation(MemoryType.WORKING, 64, -1),
                "b", MemorySource.OBSERVED, new String[]{});
        assertThat(index.size()).isEqualTo(2);
        index.remove("a");
        assertThat(index.size()).isEqualTo(1);
    }

    // ══════════════════════════════════════════════════════════════
    // Concurrent safety
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("concurrent register + findIdByOffset is thread-safe")
    void concurrentRegisterAndLookup() throws Exception {
        int threads = 8;
        int perThread = 5_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        String id = "t" + threadId + "-m" + i;
                        long offset = (long) (threadId * perThread + i) * 64;
                        index.register(id,
                                new MemoryLocation(MemoryType.EPISODIC, offset, 0),
                                "text-" + id, MemorySource.OBSERVED, new String[]{});
                    }
                    // Verify own entries
                    for (int i = 0; i < perThread; i++) {
                        long offset = (long) (threadId * perThread + i) * 64;
                        String found = index.findIdByOffset(MemoryType.EPISODIC, offset);
                        if (found == null) errors.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(index.size()).isEqualTo(threads * perThread);
        assertThat(errors.get()).as("All lookups should find their entry").isZero();
    }

    @Test
    @DisplayName("concurrent register + remove is thread-safe")
    void concurrentRegisterAndRemove() throws Exception {
        int count = 10_000;
        // Pre-populate
        for (int i = 0; i < count; i++) {
            index.register("mem-" + i,
                    new MemoryLocation(MemoryType.EPISODIC, (long) i * 64, 0),
                    "t-" + i, MemorySource.OBSERVED, new String[]{});
        }

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch latch = new CountDownLatch(2);

        // Thread 1: remove even entries
        pool.submit(() -> {
            try {
                for (int i = 0; i < count; i += 2) {
                    index.remove("mem-" + i);
                }
            } finally { latch.countDown(); }
        });

        // Thread 2: lookup all entries
        pool.submit(() -> {
            try {
                for (int i = 0; i < count; i++) {
                    index.findIdByOffset(MemoryType.EPISODIC, (long) i * 64); // should not throw
                }
            } finally { latch.countDown(); }
        });

        latch.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        // After removal: odd entries should remain, even entries gone
        for (int i = 0; i < count; i += 2) {
            assertThat(index.locate("mem-" + i)).isNull();
        }
        for (int i = 1; i < count; i += 2) {
            assertThat(index.locate("mem-" + i)).isNotNull();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Edge cases
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("large offsets (near Long.MAX_VALUE) handled correctly")
    void largeOffsets() {
        long bigOffset = 0x0000_FFFF_FFFF_FFF0L; // near 48-bit max
        index.register("big",
                new MemoryLocation(MemoryType.SEMANTIC, bigOffset, -1),
                "big offset", MemorySource.OBSERVED, new String[]{});

        assertThat(index.findIdByOffset(MemoryType.SEMANTIC, bigOffset)).isEqualTo("big");
    }

    @Test
    @DisplayName("zero offset is valid and distinguishable across types")
    void zeroOffset() {
        index.register("w0", new MemoryLocation(MemoryType.WORKING, 0, -1),
                "w", MemorySource.OBSERVED, new String[]{});
        index.register("e0", new MemoryLocation(MemoryType.EPISODIC, 0, 0),
                "e", MemorySource.OBSERVED, new String[]{});

        assertThat(index.findIdByOffset(MemoryType.WORKING, 0)).isEqualTo("w0");
        assertThat(index.findIdByOffset(MemoryType.EPISODIC, 0)).isEqualTo("e0");
    }

    @Test
    @DisplayName("re-registering same ID updates reverse index")
    void reRegisterUpdatesReverseIndex() {
        index.register("mem-1",
                new MemoryLocation(MemoryType.EPISODIC, 100L, 0),
                "v1", MemorySource.OBSERVED, new String[]{});
        assertThat(index.findIdByOffset(MemoryType.EPISODIC, 100L)).isEqualTo("mem-1");

        // Re-register at different offset
        index.register("mem-1",
                new MemoryLocation(MemoryType.EPISODIC, 200L, 0),
                "v2", MemorySource.OBSERVED, new String[]{});
        assertThat(index.findIdByOffset(MemoryType.EPISODIC, 200L)).isEqualTo("mem-1");
        assertThat(index.text("mem-1")).isEqualTo("v2");
    }
}
