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

/**
 * Pluggable ID generation for cognitive memories.
 *
 * <h3>Design</h3>
 * <p>Provides a {@link com.spectrayan.spector.memory.id.MemoryIdGenerator} strategy
 * interface with built-in implementations:</p>
 * <ul>
 *   <li>{@link com.spectrayan.spector.memory.id.TsidGenerator} — time-sorted, 13-char, distributed-safe (default)</li>
 *   <li>{@link com.spectrayan.spector.memory.id.UuidGenerator} — standard UUID v4, 36-char</li>
 *   <li>{@link com.spectrayan.spector.memory.id.SequenceGenerator} — monotonic counter, fastest, single-node only</li>
 * </ul>
 *
 * <h3>Configuration</h3>
 * <pre>{@code
 *   SpectorMemory memory = SpectorMemory.builder()
 *       .idStrategy(IdStrategy.TSID)    // built-in
 *       .idGenerator(myCustomGen)       // or custom
 *       .build();
 * }</pre>
 */
package com.spectrayan.spector.memory.id;
