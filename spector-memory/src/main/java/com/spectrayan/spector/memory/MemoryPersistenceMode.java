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

/**
 * Persistence mode for cognitive memory tier stores.
 *
 * <h3>Design</h3>
 * <p>Mirrors {@code com.spectrayan.spector.storage.PersistenceMode} from the
 * storage module, but is specific to the memory module's tier stores
 * (Working, Episodic, Semantic, Procedural).</p>
 *
 * <h3>Behavior by Mode</h3>
 * <ul>
 *   <li>{@link #IN_MEMORY} — All tier stores use {@code Arena.ofShared()} for
 *       volatile off-heap RAM. Data is lost on JVM shutdown. Suitable for
 *       ephemeral agents and unit tests.</li>
 *   <li>{@link #DISK} — All tier stores (except optionally Working) use
 *       {@code FileChannel.map()} for persistent mmap files. Data survives
 *       JVM restarts. This is the <b>default</b> mode.</li>
 * </ul>
 *
 * @see com.spectrayan.spector.memory.SpectorMemory.Builder#persistenceMode(MemoryPersistenceMode)
 */
public enum MemoryPersistenceMode {

    /**
     * All tier stores use volatile off-heap RAM ({@code Arena.ofShared()}).
     * Data is lost on JVM shutdown.
     */
    IN_MEMORY,

    /**
     * Tier stores use memory-mapped files ({@code FileChannel.map()}).
     * Data survives JVM restarts. This is the default.
     */
    DISK
}
