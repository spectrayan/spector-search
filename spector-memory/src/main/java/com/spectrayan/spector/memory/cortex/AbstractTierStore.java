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

import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout.CognitiveHeader;
import com.spectrayan.spector.memory.synapse.SynapticHeaderConstants;
import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.commons.error.SpectorStorageException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Abstract base class for single-segment tier stores.
 *
 * <h3>Template Method Pattern</h3>
 * <p>Provides common infrastructure shared by {@link WorkingMemoryStore},
 * {@link SemanticMemoryStore}, and {@link ProceduralMemoryStore}:</p>
 * <ul>
 *   <li>Arena lifecycle (shared Arena for thread-safe access)</li>
 *   <li>Layout creation from vector byte count</li>
 *   <li>Capacity tracking and size reporting</li>
 *   <li>Segment allocation with 64-byte alignment</li>
 *   <li>Close/cleanup lifecycle</li>
 * </ul>
 *
 * <h3>Dual Mode: Volatile vs. File-Backed</h3>
 * <ul>
 *   <li><b>Volatile</b> (in-memory): {@code Arena.ofShared()} allocates off-heap RAM.
 *       Data is lost on JVM shutdown.</li>
 *   <li><b>File-backed</b> (persistent): {@code FileChannel.map()} creates a persistent
 *       mmap'd file with a 64-byte metadata header. Data survives JVM restarts.</li>
 * </ul>
 *
 * <h3>Metadata Header Layout (64 bytes)</h3>
 * <pre>
 *   [4B magic]     Offset 0  — 0x54494552 ("TIER")
 *   [4B version]   Offset 4  — format version (1)
 *   [4B count]     Offset 8  — number of live records
 *   [4B capacity]  Offset 12 — max records
 *   [4B stride]    Offset 16 — record stride in bytes
 *   [4B tierOrd]   Offset 20 — MemoryType ordinal
 *   [4B extra1]    Offset 24 — subclass-specific (e.g., writeIndex for Working)
 *   [4B extra2]    Offset 28 — reserved for subclass use
 *   [32B reserved] Offset 32 — future use
 * </pre>
 *
 * <p>{@link EpisodicMemoryStore} implements {@link TierStore} directly because
 * it uses mmap-backed partitions rather than a single Arena-allocated segment.</p>
 *
 * @see TierStore for the common interface
 */
public abstract class AbstractTierStore implements TierStore {

    private static final Logger log = LoggerFactory.getLogger(AbstractTierStore.class);

    /** Metadata header magic: "TIER" in ASCII. */
    static final int TIER_MAGIC = 0x54494552;

    /** Metadata header format version. */
    static final int TIER_VERSION = 1;

    /** Size of the metadata header in bytes. */
    public static final int METADATA_HEADER_BYTES = 64;

    // Metadata field offsets
    static final int META_MAGIC    = 0;
    static final int META_VERSION  = 4;
    static final int META_COUNT    = 8;
    static final int META_CAPACITY = 12;
    static final int META_STRIDE   = 16;
    static final int META_TIER_ORD = 20;
    static final int META_EXTRA1   = 24;
    static final int META_EXTRA2   = 28;

    // ── SWMR Visibility Barrier ──
    // VarHandle for release/acquire access to maxVisibleRecord.
    // Writers call publishVisible() after completing a record write;
    // readers call visibleCount() to get the acquire-fenced count.
    private static final VarHandle VISIBLE_COUNT_HANDLE;
    static {
        try {
            VISIBLE_COUNT_HANDLE = MethodHandles.lookup()
                    .findVarHandle(AbstractTierStore.class, "maxVisibleRecord", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    protected final CognitiveRecordLayout layout;
    protected final int capacity;
    protected final Arena arena;
    protected final MemorySegment segment;
    protected int count = 0;

    /**
     * The number of records visible to concurrent readers.
     * Published with release semantics after each complete record write.
     * Read with acquire semantics by scanners before entering scoring loops.
     */
    @SuppressWarnings("unused") // accessed via VarHandle
    private volatile int maxVisibleRecord = 0;

    /** True if this store is backed by a file (persistent). */
    protected final boolean persistent;

    /** File channel for persistent stores (null for volatile). */
    private FileChannel fileChannel;

    /** File path for persistent stores (null for volatile). */
    private final Path filePath;

    /**
     * Volatile constructor — allocates a single contiguous off-heap segment (no file).
     *
     * @param quantizedVecBytes bytes per quantized vector
     * @param capacity          maximum number of records
     * @param segmentBytes      total bytes to allocate (caller decides header-only vs full)
     */
    protected AbstractTierStore(int quantizedVecBytes, int capacity, long segmentBytes) {
        this.layout = new CognitiveRecordLayout(quantizedVecBytes);
        this.capacity = capacity;
        this.arena = Arena.ofShared();
        this.segment = arena.allocate(segmentBytes, SynapticHeaderConstants.HEADER_BYTES);
        this.persistent = false;
        this.filePath = null;
    }

    /**
     * File-backed constructor — creates or opens a persistent mmap'd file.
     *
     * <p>If the file already exists and contains a valid metadata header, the
     * store's state ({@code count}) is restored from it. Otherwise, a new
     * file is created with a fresh metadata header.</p>
     *
     * @param quantizedVecBytes bytes per quantized vector
     * @param capacity          maximum number of records
     * @param segmentBytes      total data bytes (excluding metadata header)
     * @param filePath          path to the backing file
     */
    protected AbstractTierStore(int quantizedVecBytes, int capacity, long segmentBytes, Path filePath) {
        this.layout = new CognitiveRecordLayout(quantizedVecBytes);
        this.capacity = capacity;
        this.persistent = true;
        this.filePath = filePath;
        this.arena = Arena.ofShared();

        try {
            // Ensure parent directories exist
            Path parent = filePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            long totalBytes = METADATA_HEADER_BYTES + segmentBytes;
            boolean isNew = !Files.exists(filePath) || Files.size(filePath) < METADATA_HEADER_BYTES;

            fileChannel = FileChannel.open(filePath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE);

            if (isNew) {
                // Extend file to full size
                fileChannel.position(totalBytes - 1);
                fileChannel.write(ByteBuffer.wrap(new byte[]{0}));
            }

            // Map the entire file
            long mapSize = Math.max(totalBytes, fileChannel.size());
            this.segment = fileChannel.map(FileChannel.MapMode.READ_WRITE, 0, mapSize, arena);

            if (isNew) {
                // Write fresh metadata header
                this.count = 0;
                writeMetadata();
                log.info("{} created new persistent file: {} ({}KB)",
                        getClass().getSimpleName(), filePath, totalBytes / 1024);
            } else {
                // Restore state from existing file
                readMetadata();
                publishVisible(); // SWMR: make restored records visible to readers
                log.info("{} loaded from persistent file: {} ({} records)",
                        getClass().getSimpleName(), filePath, count);
            }
        } catch (IOException e) {
            throw new SpectorStorageException(ErrorCode.MMAP_FAILED, e, filePath);
        }
    }

    /**
     * Writes the metadata header to the mapped segment.
     * Called on creation and after count changes.
     */
    protected void writeMetadata() {
        if (!persistent) return;
        segment.set(ValueLayout.JAVA_INT, META_MAGIC, TIER_MAGIC);
        segment.set(ValueLayout.JAVA_INT, META_VERSION, TIER_VERSION);
        segment.set(ValueLayout.JAVA_INT, META_COUNT, count);
        segment.set(ValueLayout.JAVA_INT, META_CAPACITY, capacity);
        segment.set(ValueLayout.JAVA_INT, META_STRIDE, layout.stride());
        segment.set(ValueLayout.JAVA_INT, META_TIER_ORD, type().ordinal());
    }

    /**
     * Reads the metadata header from the mapped segment.
     * Called when loading from an existing file.
     */
    protected void readMetadata() {
        int magic = segment.get(ValueLayout.JAVA_INT, META_MAGIC);
        if (magic != TIER_MAGIC) {
            log.warn("Invalid tier magic in {}: 0x{} (expected 0x{})",
                    filePath, Integer.toHexString(magic), Integer.toHexString(TIER_MAGIC));
            this.count = 0;
            return;
        }
        this.count = segment.get(ValueLayout.JAVA_INT, META_COUNT);
    }

    /**
     * Persists the current count to the metadata header.
     * Subclasses should call this after modifying {@code count}.
     */
    protected void persistCount() {
        if (persistent) {
            segment.set(ValueLayout.JAVA_INT, META_COUNT, count);
        }
    }

    /**
     * Returns the byte offset where data records begin.
     * For persistent stores, records start after the metadata header.
     * For volatile stores, records start at offset 0.
     */
    protected long dataOffset() {
        return persistent ? METADATA_HEADER_BYTES : 0;
    }

    @Override
    public int size() {
        return count;
    }

    @Override
    public int visibleCount() {
        return (int) VISIBLE_COUNT_HANDLE.getAcquire(this);
    }

    /**
     * Publishes the current {@code count} as visible to concurrent readers.
     *
     * <p>Must be called by subclasses after completing a record write
     * (header + vector fully written) and updating {@code count}.
     * Uses release semantics to ensure all prior writes (the record data)
     * are visible before the count update is observed by readers.</p>
     */
    protected void publishVisible() {
        VISIBLE_COUNT_HANDLE.setRelease(this, count);
    }

    @Override
    public CognitiveRecordLayout layout() {
        return layout;
    }

    @Override
    public MemorySegment primarySegment() {
        return segment;
    }

    /**
     * Returns the maximum capacity of this store.
     */
    public int capacity() {
        return capacity;
    }

    /**
     * Returns the backing memory segment for direct scorer access.
     */
    public MemorySegment segment() {
        return segment;
    }

    /**
     * Returns whether this store is file-backed (persistent).
     */
    public boolean isPersistent() {
        return persistent;
    }

    /**
     * Returns the file path for persistent stores, or null for volatile.
     */
    public Path filePath() {
        return filePath;
    }

    /**
     * Forces the mapped segment to be written to the underlying file (persistent only).
     */
    public void force() {
        if (persistent && segment != null) {
            segment.force();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // COMPACTION SUPPORT
    // ══════════════════════════════════════════════════════════════

    /**
     * Counts the number of tombstoned records in this store.
     *
     * <p>Scans all records and checks the tombstone flag in each header.
     * This is O(n) but only called before compaction decisions.</p>
     *
     * @return the number of tombstoned records
     */
    public int tombstoneCount() {
        int tombstones = 0;
        long baseOffset = dataOffset();
        for (int i = 0; i < count; i++) {
            long offset = baseOffset + (long) i * layout.stride();
            CognitiveHeader header = layout.readHeader(segment, offset);
            if (SynapticHeaderConstants.isTombstoned(header.flags())) {
                tombstones++;
            }
        }
        return tombstones;
    }

    /**
     * Returns the ratio of tombstoned records to total records.
     *
     * @return tombstone ratio (0.0 = no tombstones, 1.0 = all tombstoned)
     */
    public float tombstoneRatio() {
        if (count == 0) return 0.0f;
        return (float) tombstoneCount() / count;
    }

    @Override
    public void close() {
        log.info("{} closing ({} records, persistent={})", getClass().getSimpleName(), count, persistent);
        if (persistent) {
            try {
                if (segment != null) {
                    segment.force();
                }
            } catch (Exception e) {
                log.debug("Error forcing segment: {}", e.getMessage());
            }
        }
        arena.close();
        if (fileChannel != null) {
            try {
                fileChannel.close();
            } catch (IOException e) {
                log.debug("Error closing file channel: {}", e.getMessage());
            }
        }
    }
}

