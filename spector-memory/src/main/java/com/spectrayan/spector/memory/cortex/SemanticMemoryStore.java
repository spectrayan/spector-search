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
import com.spectrayan.spector.memory.synapse.HeaderLayout;
import com.spectrayan.spector.memory.synapse.SynapticHeaderConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;
import com.spectrayan.spector.commons.error.SpectorServerException;
import com.spectrayan.spector.memory.error.SpectorMemoryTierFullException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Permanent factual knowledge store — stores full cognitive records (header + quantized vector).
 *
 * <h3>Biological Analog: Neocortex</h3>
 * <p>The neocortex stores permanent, deduplicated facts — consolidated from episodic
 * memories during sleep. It's the "long-term" memory that survives across sessions.</p>
 *
 * <h3>Self-Contained Storage</h3>
 * <p>Each record is a complete cognitive record: 64-byte synaptic header followed
 * by the INT8 quantized vector payload. The memory system is self-contained —
 * vectors live alongside their metadata in the same tier store file.</p>
 *
 * <h3>Design</h3>
 * <ul>
 *   <li>Extends {@link AbstractTierStore} for common Arena/layout/segment lifecycle</li>
 *   <li>Full cognitive records — header + quantized vector in one slab</li>
 *   <li>Directory-level partitioning: each partition dir has its own {@code semantic.mem}</li>
 *   <li>Flat scan with {@code CognitiveScorer} for distance computation</li>
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
                (long) new CognitiveRecordLayout(quantizedVecBytes).stride() * capacity);

        log.info("SemanticMemoryStore initialized: capacity={}, stride={}B, persistent=false, headerVersion=V{}",
                capacity, layout.stride(), layout.headerLayout().version());
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
                (long) new CognitiveRecordLayout(quantizedVecBytes).stride() * capacity,
                filePath);

        log.info("SemanticMemoryStore initialized: capacity={}, stride={}B, persistent=true, count={}, headerVersion=V{}",
                capacity, layout.stride(), count, layout.headerLayout().version());
    }

    @Override
    public MemoryType type() {
        return MemoryType.SEMANTIC;
    }

    @Override
    public long write(CognitiveHeader header, byte[] quantizedVec) {
        long offset = dataOffset() + (long) count * layout.stride();
        append(header, quantizedVec);
        return offset;
    }

    private final ReentrantLock writeLock = new ReentrantLock();

    /**
     * Appends a full semantic memory (header + quantized vector).
     *
     * <p>Stores the complete cognitive record: synaptic header followed by the
     * INT8 quantized vector payload. This keeps the memory system self-contained —
     * vectors live alongside their headers in the same tier store file.</p>
     *
     * @param header       cognitive header
     * @param quantizedVec quantized vector bytes
     */
    public void append(CognitiveHeader header, byte[] quantizedVec) {
        writeLock.lock();
        try {
            if (count >= capacity) {
                throw new SpectorMemoryTierFullException("SEMANTIC", capacity);
            }

            long offset = dataOffset() + (long) count * layout.stride();
            layout.writeHeader(segment, offset, header);

            // Write vector payload (if available — ReflectDaemon promotes with null vec)
            if (quantizedVec != null) {
                MemorySegment.copy(
                        MemorySegment.ofArray(quantizedVec), 0,
                        segment, layout.vectorOffset(offset),
                        quantizedVec.length
                );
            }
            // else: vector region stays zeroed (header-only consolidation)

            count++;
            persistCount();
            publishVisible(); // SWMR: make record visible to scanners
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Stores a new semantic memory header (header-only, for backward compatibility).
     *
     * @param header cognitive header
     * @return the record index
     */
    public int store(CognitiveHeader header) {
        writeLock.lock();
        try {
            if (count >= capacity) {
                throw new SpectorMemoryTierFullException("SEMANTIC", capacity);
            }

            long offset = dataOffset() + (long) count * layout.stride();
            layout.writeHeader(segment, offset, header);
            int index = count++;
            persistCount();
            publishVisible(); // SWMR: make record visible to scanners
            return index;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Reads the cognitive header at the given index.
     */
    public CognitiveHeader readHeader(int index) {
        long offset = dataOffset() + (long) index * layout.stride();
        return layout.readHeader(segment, offset);
    }

    /**
     * Returns the header slab segment for direct scorer access.
     * This is the same as {@link #primarySegment()} for semantic stores.
     */
    public MemorySegment headerSlab() {
        return segment;
    }

}
