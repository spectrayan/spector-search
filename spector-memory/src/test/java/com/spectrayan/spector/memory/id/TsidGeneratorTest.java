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
package com.spectrayan.spector.memory.id;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TSID (Time-Sorted ID) generator.
 */
class TsidGeneratorTest {

    @Test
    void generateProduces13CharString() {
        var gen = new TsidGenerator(0);
        String id = gen.generate();

        assertNotNull(id);
        assertEquals(TsidGenerator.TSID_STRING_LENGTH, id.length(),
                "TSID should be exactly 13 characters");
    }

    @Test
    void generateProducesUniqueIds() {
        var gen = new TsidGenerator(0);
        Set<String> ids = new HashSet<>();

        for (int i = 0; i < 10_000; i++) {
            String id = gen.generate();
            assertTrue(ids.add(id), "Duplicate TSID detected: " + id);
        }

        assertEquals(10_000, ids.size());
    }

    @Test
    void idsAreLexicographicallySorted() {
        var gen = new TsidGenerator(0);
        String prev = gen.generate();

        for (int i = 0; i < 1_000; i++) {
            String current = gen.generate();
            assertTrue(current.compareTo(prev) > 0,
                    "TSIDs must be lexicographically increasing: " + prev + " >= " + current);
            prev = current;
        }
    }

    @Test
    void encodeDecode_roundTrip() {
        var gen = new TsidGenerator(42);

        for (int i = 0; i < 100; i++) {
            long raw = gen.generateLong();
            String encoded = TsidGenerator.encodeCrockford(raw);
            long decoded = TsidGenerator.decodeCrockford(encoded);

            assertEquals(raw, decoded,
                    "Round-trip failed for value: " + raw + " → " + encoded + " → " + decoded);
        }
    }

    @Test
    void crockfordAlphabetDoesNotContainAmbiguousChars() {
        var gen = new TsidGenerator(0);
        for (int i = 0; i < 100; i++) {
            String id = gen.generate();
            assertFalse(id.contains("I"), "Should not contain 'I'");
            assertFalse(id.contains("L"), "Should not contain 'L'");
            assertFalse(id.contains("O"), "Should not contain 'O'");
            assertFalse(id.contains("U"), "Should not contain 'U'");
        }
    }

    @Test
    void differentNodesProduceDifferentIds() {
        var gen1 = new TsidGenerator(0);
        var gen2 = new TsidGenerator(1);

        String id1 = gen1.generate();
        String id2 = gen2.generate();

        assertNotEquals(id1, id2, "Different nodes should produce different IDs");
    }

    @Test
    void nodeIdValidation() {
        assertDoesNotThrow(() -> new TsidGenerator(0));
        assertDoesNotThrow(() -> new TsidGenerator(TsidGenerator.MAX_NODE_ID));

        assertThrows(IllegalArgumentException.class, () -> new TsidGenerator(-1));
        assertThrows(IllegalArgumentException.class, () -> new TsidGenerator(TsidGenerator.MAX_NODE_ID + 1));
    }

    @Test
    void autoNodeIdDoesNotThrow() {
        assertDoesNotThrow(() -> {
            var gen = new TsidGenerator(); // auto node ID
            gen.generate();
        });
    }

    @Test
    void concurrentGenerationProducesUniqueIds() throws Exception {
        var gen = new TsidGenerator(0);
        int threadCount = 8;
        int idsPerThread = 1_000;
        Set<String> allIds = ConcurrentHashMap.newKeySet();
        CountDownLatch latch = new CountDownLatch(threadCount);

        try (ExecutorService executor = Executors.newFixedThreadPool(threadCount)) {
            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < idsPerThread; i++) {
                            String id = gen.generate();
                            assertTrue(allIds.add(id),
                                    "Concurrent duplicate detected: " + id);
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
        }

        assertEquals(threadCount * idsPerThread, allIds.size(),
                "Expected all IDs to be unique across threads");
    }

    @Test
    void decodeCrockford_invalidLength() {
        assertThrows(IllegalArgumentException.class,
                () -> TsidGenerator.decodeCrockford("SHORT"));
        assertThrows(IllegalArgumentException.class,
                () -> TsidGenerator.decodeCrockford("TOOLONGSTRING1234"));
        assertThrows(IllegalArgumentException.class,
                () -> TsidGenerator.decodeCrockford(null));
    }

    // ── IdStrategy factory tests ──

    @Test
    void idStrategy_tsid_createsGenerator() {
        MemoryIdGenerator gen = IdStrategy.TSID.createGenerator();
        assertInstanceOf(TsidGenerator.class, gen);
        String id = gen.generate();
        assertEquals(13, id.length());
    }

    @Test
    void idStrategy_uuid_createsGenerator() {
        MemoryIdGenerator gen = IdStrategy.UUID.createGenerator();
        assertInstanceOf(UuidGenerator.class, gen);
        String id = gen.generate();
        assertEquals(36, id.length()); // UUID format: 8-4-4-4-12
        assertTrue(id.contains("-"));
    }

    @Test
    void idStrategy_sequence_createsGenerator() {
        MemoryIdGenerator gen = IdStrategy.SEQUENCE.createGenerator();
        assertInstanceOf(SequenceGenerator.class, gen);
        assertEquals("1", gen.generate());
        assertEquals("2", gen.generate());
        assertEquals("3", gen.generate());
    }

    @RepeatedTest(3)
    void sequenceGenerator_monotonic() {
        var gen = new SequenceGenerator();
        long prev = Long.parseLong(gen.generate());
        for (int i = 0; i < 100; i++) {
            long curr = Long.parseLong(gen.generate());
            assertTrue(curr > prev, "Sequence must be strictly monotonic");
            prev = curr;
        }
    }
}
