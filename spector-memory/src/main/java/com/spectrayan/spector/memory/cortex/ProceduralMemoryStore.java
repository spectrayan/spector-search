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
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout.CognitiveHeader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;
import com.spectrayan.spector.commons.error.SpectorServerException;
import com.spectrayan.spector.memory.error.SpectorMemoryTierFullException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Small persistent store for procedural memory — prompt templates and tool-usage rules.
 *
 * <h3>Biological Analog: Basal Ganglia</h3>
 * <p>The basal ganglia stores procedural / motor memory — "how to do things" rather
 * than "what happened." These are habits, skills, and automatic routines that
 * don't require conscious recall.</p>
 *
 * <h3>Persistence</h3>
 * <p>When file-backed ({@code filePath} constructor), records are stored in a
 * persistent mmap file. On restart, the {@code count} is restored and all
 * records are immediately accessible for microsecond lookups.</p>
 *
 * <h3>Design</h3>
 * <ul>
 *   <li>Extends {@link AbstractTierStore} for common Arena/layout/segment lifecycle</li>
 *   <li>Small store (typically &lt;1000 records)</li>
 *   <li>High importance, low TTL — designed for microsecond lookups</li>
 *   <li>Linear append (no eviction — throws when full)</li>
 *   <li>Flat scan with {@code CognitiveScorer}</li>
 * </ul>
 */
public final class ProceduralMemoryStore extends AbstractTierStore {

    private static final Logger log = LoggerFactory.getLogger(ProceduralMemoryStore.class);

    /**
     * Creates a volatile Procedural Memory store (in-memory only).
     *
     * @param quantizedVecBytes bytes per quantized vector
     * @param capacity          maximum number of procedural memories (default: 1000)
     */
    public ProceduralMemoryStore(int quantizedVecBytes, int capacity) {
        super(quantizedVecBytes, capacity,
                (long) new com.spectrayan.spector.memory.synapse.CognitiveRecordLayout(quantizedVecBytes).stride() * capacity);

        log.info("ProceduralMemoryStore initialized: capacity={}, stride={}B, persistent=false",
                capacity, layout.stride());
    }

    /**
     * Creates a persistent Procedural Memory store backed by an mmap file.
     *
     * @param quantizedVecBytes bytes per quantized vector
     * @param capacity          maximum number of procedural memories
     * @param filePath          path to the backing mmap file
     */
    public ProceduralMemoryStore(int quantizedVecBytes, int capacity, Path filePath) {
        super(quantizedVecBytes, capacity,
                (long) new com.spectrayan.spector.memory.synapse.CognitiveRecordLayout(quantizedVecBytes).stride() * capacity,
                filePath);

        log.info("ProceduralMemoryStore initialized: capacity={}, stride={}B, persistent=true, count={}",
                capacity, layout.stride(), count);
    }

    /**
     * Creates a volatile Procedural Memory store with default capacity (1000).
     */
    public ProceduralMemoryStore(int quantizedVecBytes) {
        this(quantizedVecBytes, 1000);
    }

    @Override
    public MemoryType type() {
        return MemoryType.PROCEDURAL;
    }

    @Override
    public long write(CognitiveHeader header, byte[] quantizedVec) {
        long offset = dataOffset() + (long) count * layout.stride();
        append(header, quantizedVec);
        return offset;
    }

    private final ReentrantLock writeLock = new ReentrantLock();

    /**
     * Appends a procedural memory.
     *
     * @param header       cognitive header
     * @param quantizedVec quantized vector bytes
     */
    public void append(CognitiveHeader header, byte[] quantizedVec) {
        writeLock.lock();
        try {
            if (count >= capacity) {
                throw new SpectorMemoryTierFullException("PROCEDURAL", capacity);
            }

            long offset = dataOffset() + (long) count * layout.stride();
            layout.writeHeader(segment, offset, header);
            MemorySegment.copy(
                    MemorySegment.ofArray(quantizedVec), 0,
                    segment, layout.vectorOffset(offset),
                    quantizedVec.length
            );
            count++;
            persistCount();
            publishVisible(); // SWMR: make record visible to scanners
        } finally {
            writeLock.unlock();
        }
    }
}
