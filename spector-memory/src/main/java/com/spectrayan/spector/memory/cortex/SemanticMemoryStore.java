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

import com.spectrayan.spector.memory.MemoryType;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout.CognitiveHeader;
import com.spectrayan.spector.memory.synapse.HeaderLayout;
import com.spectrayan.spector.memory.synapse.SynapticHeaderConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import com.spectrayan.spector.commons.error.SpectorServerException;
import com.spectrayan.spector.memory.error.SpectorMemoryTierFullException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Permanent factual knowledge store delegating to existing HNSW/SVASQ index infrastructure.
 *
 * <h3>Biological Analog: Neocortex</h3>
 * <p>The neocortex stores permanent, deduplicated facts — consolidated from episodic
 * memories during sleep. It's the "long-term" memory that survives across sessions.</p>
 *
 * <h3>Persistence</h3>
 * <p>When file-backed ({@code filePath} constructor), the header-only slab is
 * stored in a persistent mmap file. On restart, the {@code count} is restored
 * from the metadata header and all existing headers are immediately accessible.</p>
 *
 * <h3>Design</h3>
 * <ul>
 *   <li>Extends {@link AbstractTierStore} for common Arena/layout/segment lifecycle</li>
 *   <li>Header-only store — vectors go through SpectorIndex's HNSW/SVASQ pipeline</li>
 *   <li>Maintains a parallel off-heap slab for synaptic headers (size depends on layout version)</li>
 *   <li>On search: reads header for scoring, delegates vector distance to SVASQ kernel</li>
 *   <li>Deduplication check before insert (via {@code SemanticDeduplicator})</li>
 * </ul>
 */
public final class SemanticMemoryStore extends AbstractTierStore {

    private static final Logger log = LoggerFactory.getLogger(SemanticMemoryStore.class);

    /**
     * Creates a volatile Semantic Memory store (in-memory only).
     *
     * <p>Allocates a header-only slab (no vector payload) since vectors are stored
     * in SpectorIndex.</p>
     *
     * @param quantizedVecBytes bytes per quantized vector (for layout calculation)
     * @param capacity          maximum number of semantic memories (default: 100_000)
     */
    public SemanticMemoryStore(int quantizedVecBytes, int capacity) {
        super(quantizedVecBytes, capacity,
                (long) layout(quantizedVecBytes).headerLayout().headerBytes() * capacity);

        log.info("SemanticMemoryStore initialized: capacity={}, headerSlab={}KB, persistent=false, headerVersion=V{}",
                capacity,
                (long) layout.headerLayout().headerBytes() * capacity / 1024,
                layout.headerLayout().version());
    }

    /**
     * Creates a persistent Semantic Memory store backed by an mmap file.
     *
     * @param quantizedVecBytes bytes per quantized vector (for layout calculation)
     * @param capacity          maximum number of semantic memories
     * @param filePath          path to the backing mmap file
     */
    public SemanticMemoryStore(int quantizedVecBytes, int capacity, Path filePath) {
        super(quantizedVecBytes, capacity,
                (long) layout(quantizedVecBytes).headerLayout().headerBytes() * capacity,
                filePath);

        log.info("SemanticMemoryStore initialized: capacity={}, headerSlab={}KB, persistent=true, count={}, headerVersion=V{}",
                capacity,
                (long) layout.headerLayout().headerBytes() * capacity / 1024,
                count,
                layout.headerLayout().version());
    }

    @Override
    public MemoryType type() {
        return MemoryType.SEMANTIC;
    }

    @Override
    public long write(CognitiveHeader header, byte[] quantizedVec) {
        // Semantic store is header-only — quantizedVec is ignored
        int index = store(header);
        return dataOffset() + (long) index * layout.headerLayout().headerBytes();
    }

    /**
     * Stores a new semantic memory header.
     *
     * <p>The actual vector is stored via SpectorIndex's existing HNSW/SVASQ pipeline.
     * This method only writes the cognitive header to the parallel slab.</p>
     *
     * @param header cognitive header
     * @return the record index (used to correlate with SpectorIndex vector)
     */
    public synchronized int store(CognitiveHeader header) {
        if (count >= capacity) {
            throw new SpectorMemoryTierFullException("SEMANTIC", capacity);
        }

        long offset = dataOffset() + (long) count * layout.headerLayout().headerBytes();
        layout.writeHeader(segment, offset, header);
        int index = count++;
        persistCount();
        return index;
    }

    /**
     * Reads the cognitive header at the given index.
     */
    public CognitiveHeader readHeader(int index) {
        long offset = dataOffset() + (long) index * layout.headerLayout().headerBytes();
        return layout.readHeader(segment, offset);
    }

    /**
     * Returns the header slab segment for direct scorer access.
     * This is the same as {@link #primarySegment()} for semantic stores.
     */
    public MemorySegment headerSlab() {
        return segment;
    }

    /**
     * Helper to create a layout for slab size calculation in super() calls.
     */
    private static com.spectrayan.spector.memory.synapse.CognitiveRecordLayout layout(int quantizedVecBytes) {
        return new com.spectrayan.spector.memory.synapse.CognitiveRecordLayout(quantizedVecBytes);
    }
}
