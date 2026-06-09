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

import java.util.concurrent.atomic.AtomicLong;

/**
 * Monotonic sequence counter generator.
 *
 * <p>Uses an {@link AtomicLong} for lock-free, ~5ns ID generation.
 * Produces the shortest possible string representation (1–19 digits).</p>
 *
 * <p><b>Not distributed-safe</b> — two Spector nodes using separate
 * SequenceGenerator instances will produce colliding IDs. Use only for
 * single-node, in-memory deployments or testing.</p>
 *
 * @see IdStrategy#SEQUENCE
 */
public final class SequenceGenerator implements MemoryIdGenerator {

    private final AtomicLong counter;

    /**
     * Creates a sequence generator starting from 1.
     */
    public SequenceGenerator() {
        this(0L);
    }

    /**
     * Creates a sequence generator starting from a specific value.
     *
     * @param startFrom the initial counter value (IDs will be startFrom + 1, startFrom + 2, ...)
     */
    public SequenceGenerator(long startFrom) {
        this.counter = new AtomicLong(startFrom);
    }

    @Override
    public String generate() {
        return Long.toString(counter.incrementAndGet());
    }

    /**
     * Returns the current counter value (for diagnostics).
     */
    public long current() {
        return counter.get();
    }
}
