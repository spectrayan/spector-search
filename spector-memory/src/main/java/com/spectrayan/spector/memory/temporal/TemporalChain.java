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
package com.spectrayan.spector.memory.temporal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import com.spectrayan.spector.memory.error.SpectorGraphPersistenceException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Off-heap temporal causal chain linking memories within a session.
 *
 * <h3>Biological Analog: Episodic Sequence Memory</h3>
 * <p>In the hippocampus, episodic memories are linked in temporal order.
 * When you recall one event from a day, you naturally remember what happened
 * next ("what happened after the meeting?"). This chain stores explicit
 * prev/next pointers between memories ingested within the same session.</p>
 *
 * <h3>Layout Per Node (16 bytes)</h3>
 * <pre>
 *   [prevIdx:4B] [nextIdx:4B] [sessionId:4B] [pad:4B]
 * </pre>
 *
 * <p>-1 is used as sentinel for "no link" (beginning or end of chain).</p>
 *
 * <h3>Persistence</h3>
 * <p>Supports save/load via raw segment serialization with "TPCH" magic header.</p>
 */
public final class TemporalChain implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TemporalChain.class);

    /** File magic: "TPCH" in ASCII. */
    private static final int FILE_MAGIC = 0x54504348;
    private static final int FILE_VERSION = 1;
    private static final int FILE_HEADER_BYTES = 16;

    /** Bytes per node: prevIdx(4) + nextIdx(4) + sessionId(4) + pad(4). */
    static final int NODE_BYTES = 16;

    /** Sentinel value for "no link". */
    private static final int NO_LINK = -1;

    // Offsets within each node
    private static final long OFF_PREV = 0;
    private static final long OFF_NEXT = 4;
    private static final long OFF_SESSION = 8;

    private final Arena arena;
    private final MemorySegment segment;
    private final int capacity;

    /**
     * Creates a new temporal chain.
     *
     * @param capacity maximum number of nodes (memories)
     */
    public TemporalChain(int capacity) {
        this.capacity = capacity;
        this.arena = Arena.ofShared();
        this.segment = arena.allocate((long) NODE_BYTES * capacity);
        // Initialize all prev/next to NO_LINK (-1)
        for (int i = 0; i < capacity; i++) {
            long offset = (long) i * NODE_BYTES;
            segment.set(ValueLayout.JAVA_INT, offset + OFF_PREV, NO_LINK);
            segment.set(ValueLayout.JAVA_INT, offset + OFF_NEXT, NO_LINK);
            segment.set(ValueLayout.JAVA_INT, offset + OFF_SESSION, 0);
        }

        log.info("TemporalChain initialized: capacity={}, memory={}KB",
                capacity, (long) NODE_BYTES * capacity / 1024);
    }

    /**
     * Private constructor for loading from a pre-existing segment.
     */
    private TemporalChain(int capacity, Arena arena, MemorySegment segment) {
        this.capacity = capacity;
        this.arena = arena;
        this.segment = segment;
    }

    /**
     * Links two memories in temporal order within the same session.
     *
     * <p>After this call, {@code previousIdx.next = currentIdx} and
     * {@code currentIdx.prev = previousIdx}.</p>
     *
     * @param currentIdx  index of the memory just ingested
     * @param previousIdx index of the memory ingested immediately before
     * @param sessionId   session identifier (e.g., hash of session start time)
     */
    public void link(int currentIdx, int previousIdx, int sessionId) {
        if (currentIdx < 0 || currentIdx >= capacity) return;
        if (previousIdx < 0 || previousIdx >= capacity) return;
        if (currentIdx == previousIdx) return;

        long currentOffset = (long) currentIdx * NODE_BYTES;
        long previousOffset = (long) previousIdx * NODE_BYTES;

        // currentIdx.prev = previousIdx
        segment.set(ValueLayout.JAVA_INT, currentOffset + OFF_PREV, previousIdx);
        segment.set(ValueLayout.JAVA_INT, currentOffset + OFF_SESSION, sessionId);

        // previousIdx.next = currentIdx
        segment.set(ValueLayout.JAVA_INT, previousOffset + OFF_NEXT, currentIdx);
    }

    /**
     * Follows the chain forward from a starting memory.
     *
     * @param startIdx the starting memory index
     * @param maxHops  maximum number of hops to follow
     * @return list of memory indices in temporal order (excludes startIdx)
     */
    public List<Integer> followForward(int startIdx, int maxHops) {
        if (startIdx < 0 || startIdx >= capacity) return List.of();
        List<Integer> chain = new ArrayList<>();
        int current = startIdx;
        for (int hop = 0; hop < maxHops; hop++) {
            long offset = (long) current * NODE_BYTES;
            int next = segment.get(ValueLayout.JAVA_INT, offset + OFF_NEXT);
            if (next == NO_LINK || next < 0 || next >= capacity) break;
            chain.add(next);
            current = next;
        }
        return chain;
    }

    /**
     * Follows the chain backward from a starting memory.
     *
     * @param startIdx the starting memory index
     * @param maxHops  maximum number of hops to follow
     * @return list of memory indices in reverse temporal order (excludes startIdx)
     */
    public List<Integer> followBackward(int startIdx, int maxHops) {
        if (startIdx < 0 || startIdx >= capacity) return List.of();
        List<Integer> chain = new ArrayList<>();
        int current = startIdx;
        for (int hop = 0; hop < maxHops; hop++) {
            long offset = (long) current * NODE_BYTES;
            int prev = segment.get(ValueLayout.JAVA_INT, offset + OFF_PREV);
            if (prev == NO_LINK || prev < 0 || prev >= capacity) break;
            chain.add(prev);
            current = prev;
        }
        return chain;
    }

    /**
     * Returns the session ID for a memory.
     */
    public int sessionOf(int idx) {
        if (idx < 0 || idx >= capacity) return 0;
        return segment.get(ValueLayout.JAVA_INT, (long) idx * NODE_BYTES + OFF_SESSION);
    }

    /**
     * Returns whether a memory has any temporal links.
     */
    public boolean isLinked(int idx) {
        if (idx < 0 || idx >= capacity) return false;
        long offset = (long) idx * NODE_BYTES;
        int prev = segment.get(ValueLayout.JAVA_INT, offset + OFF_PREV);
        int next = segment.get(ValueLayout.JAVA_INT, offset + OFF_NEXT);
        return prev != NO_LINK || next != NO_LINK;
    }

    /**
     * Returns the capacity.
     */
    public int capacity() {
        return capacity;
    }

    // ══════════════════════════════════════════════════════════════
    // PERSISTENCE: save / load
    // ══════════════════════════════════════════════════════════════

    /**
     * Saves the chain to a binary file.
     *
     * @param filePath path to write
     */
    public void save(Path filePath) {
        Path parent = filePath.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new SpectorGraphPersistenceException("TemporalChain", parent, e);
            }
        }

        try (FileChannel ch = FileChannel.open(filePath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {

            ByteBuffer header = ByteBuffer.allocate(FILE_HEADER_BYTES);
            header.putInt(FILE_MAGIC);
            header.putInt(FILE_VERSION);
            header.putInt(capacity);
            header.putInt(0);
            header.flip();
            ch.write(header);

            long totalBytes = (long) NODE_BYTES * capacity;
            long written = 0;
            int chunkSize = 64 * 1024;
            while (written < totalBytes) {
                int toWrite = (int) Math.min(chunkSize, totalBytes - written);
                ByteBuffer buf = segment.asSlice(written, toWrite)
                        .asByteBuffer().asReadOnlyBuffer();
                ch.write(buf);
                written += toWrite;
            }

            ch.force(true);
            log.info("TemporalChain saved: capacity={} → {}", capacity, filePath);

        } catch (IOException e) {
            throw new SpectorGraphPersistenceException("TemporalChain", filePath, e);
        }
    }

    /**
     * Loads a chain from a binary file, or returns a new empty chain.
     *
     * @param filePath        path to the chain file
     * @param defaultCapacity capacity to use if file doesn't exist
     * @return a TemporalChain (loaded or new)
     */
    public static TemporalChain load(Path filePath, int defaultCapacity) {
        if (filePath == null || !Files.exists(filePath)) {
            log.info("TemporalChain file not found, creating fresh: {}", filePath);
            return new TemporalChain(defaultCapacity);
        }

        try (FileChannel ch = FileChannel.open(filePath, StandardOpenOption.READ)) {
            long fileSize = ch.size();
            if (fileSize < FILE_HEADER_BYTES) {
                log.warn("TemporalChain file too small, creating fresh");
                return new TemporalChain(defaultCapacity);
            }

            ByteBuffer header = ByteBuffer.allocate(FILE_HEADER_BYTES);
            ch.read(header);
            header.flip();

            int magic = header.getInt();
            int version = header.getInt();
            int capacity = header.getInt();
            header.getInt();

            if (magic != FILE_MAGIC || version != FILE_VERSION) {
                log.warn("Invalid TemporalChain file, creating fresh");
                return new TemporalChain(defaultCapacity);
            }

            long expectedBytes = (long) NODE_BYTES * capacity;
            if (fileSize < FILE_HEADER_BYTES + expectedBytes) {
                log.warn("TemporalChain file truncated, creating fresh");
                return new TemporalChain(defaultCapacity);
            }

            Arena arena = Arena.ofShared();
            MemorySegment seg = arena.allocate(expectedBytes);
            long read = 0;
            int chunkSize = 64 * 1024;
            while (read < expectedBytes) {
                int toRead = (int) Math.min(chunkSize, expectedBytes - read);
                ByteBuffer buf = ByteBuffer.allocate(toRead);
                ch.read(buf);
                buf.flip();
                MemorySegment.copy(MemorySegment.ofBuffer(buf), 0, seg, read, toRead);
                read += toRead;
            }

            TemporalChain chain = new TemporalChain(capacity, arena, seg);
            log.info("TemporalChain loaded: capacity={} from {}", capacity, filePath);
            return chain;

        } catch (IOException e) {
            log.error("Failed to load TemporalChain, creating fresh: {}", e.getMessage());
            return new TemporalChain(defaultCapacity);
        }
    }

    @Override
    public void close() {
        log.info("TemporalChain closing (capacity={})", capacity);
        arena.close();
    }
}
