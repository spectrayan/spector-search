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

/**
 * Built-in ID generation strategies for cognitive memories.
 *
 * <h3>Performance Comparison</h3>
 * <table>
 *   <tr><th>Strategy</th><th>Gen Speed</th><th>String Len</th><th>Time-Sorted</th><th>Distributed</th></tr>
 *   <tr><td><b>TSID</b></td><td>~20ns</td><td>13 chars</td><td>✅</td><td>✅</td></tr>
 *   <tr><td>UUID</td><td>~200ns</td><td>36 chars</td><td>❌</td><td>✅</td></tr>
 *   <tr><td>SEQUENCE</td><td>~5ns</td><td>1-19 chars</td><td>❌</td><td>❌</td></tr>
 * </table>
 *
 * <p><b>TSID is the recommended default.</b> It provides the best balance of performance
 * (compact 13-char strings for fast HashMap lookups), time-ordering (natural chronological
 * sort), and distributed safety (10-bit node ID prevents cross-node collisions).</p>
 *
 * @see MemoryIdGenerator
 * @see TsidGenerator
 */
public enum IdStrategy {

    /**
     * Time-Sorted ID (default).
     *
     * <p>64-bit Snowflake-style layout encoded as 13-character Crockford Base32 string.
     * Time-ordered, distributed-safe (10-bit node ID), supports 4096 IDs/ms/node.</p>
     *
     * <p>Recommended for all production use cases.</p>
     */
    TSID,

    /**
     * Standard UUID v4 (random).
     *
     * <p>36-character string with maximum uniqueness guarantee via {@code SecureRandom}.
     * Not time-sorted. Slowest generation (~200ns) and longest string (worst HashMap
     * performance). Use only when UUID compatibility is required.</p>
     */
    UUID,

    /**
     * Monotonic sequence counter.
     *
     * <p>Fastest generation (~5ns via AtomicLong), shortest strings, but not
     * distributed-safe — two Spector nodes will generate colliding IDs.
     * Use only for single-node, in-memory deployments.</p>
     */
    SEQUENCE;

    /**
     * Creates a {@link MemoryIdGenerator} for this strategy.
     *
     * @return a thread-safe generator instance
     */
    public MemoryIdGenerator createGenerator() {
        return switch (this) {
            case TSID -> new TsidGenerator();
            case UUID -> new UuidGenerator();
            case SEQUENCE -> new SequenceGenerator();
        };
    }
}
