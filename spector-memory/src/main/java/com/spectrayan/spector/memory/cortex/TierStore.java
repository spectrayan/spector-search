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
package com.spectrayan.spector.memory.cortex;

import com.spectrayan.spector.memory.model.MemoryType;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout.CognitiveHeader;

import java.lang.foreign.MemorySegment;

/**
 * Common interface for all cognitive tier stores.
 *
 * <h3>Design Pattern: Strategy (Interface Segregation)</h3>
 * <p>Defines the contract that every memory tier store must implement.
 * The {@link TierRouter} holds a {@code Map<MemoryType, TierStore>}
 * and dispatches operations polymorphically — zero switch statements.</p>
 *
 * <h3>Implementations</h3>
 * <ul>
 *   <li>{@link WorkingMemoryStore} — volatile circular buffer (Prefrontal Cortex)</li>
 *   <li>{@link EpisodicMemoryStore} — time-partitioned mmap (Hippocampus)</li>
 *   <li>{@link SemanticMemoryStore} — permanent header slab (Neocortex)</li>
 *   <li>{@link ProceduralMemoryStore} — small append-only store (Basal Ganglia)</li>
 * </ul>
 *
 * @see AbstractTierStore for common implementation
 * @see TierRouter for polymorphic dispatch
 */
public interface TierStore extends AutoCloseable {

    /**
     * Returns the cognitive memory tier this store belongs to.
     */
    MemoryType type();

    /**
     * Returns the number of live records in this store.
     */
    int size();

    /**
     * Returns the number of records visible to concurrent readers (SWMR barrier).
     *
     * <p>This count is published with {@code VarHandle.setRelease()} after each
     * complete record write, ensuring readers never see partially-written records.
     * Scanners (e.g., {@code CognitiveScorer}) should use this instead of
     * {@link #size()} to determine how many records to process.</p>
     *
     * @return the acquire-fenced record count safe for scanning
     */
    int visibleCount();


    /**
     * Returns the record layout for this store.
     */
    CognitiveRecordLayout layout();

    /**
     * Returns the primary memory segment for this store.
     *
     * <p>For single-segment stores (Working, Procedural), this is the backing segment.
     * For Semantic, this is the header slab.
     * For Episodic, this returns the latest partition's segment (or null if empty).</p>
     */
    MemorySegment primarySegment();

    /**
     * Writes a memory record to this store and returns the byte offset
     * where the record was written.
     *
     * <p>Each implementation handles its own write semantics:
     * Working uses circular buffer FIFO, Episodic uses partitioned append,
     * Semantic writes header-only, Procedural uses linear append.</p>
     *
     * @param header      cognitive header
     * @param quantizedVec quantized vector bytes (may be ignored by header-only stores)
     * @return byte offset where the record was written
     */
    long write(CognitiveHeader header, byte[] quantizedVec);
}
