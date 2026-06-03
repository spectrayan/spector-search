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
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout.CognitiveHeader;

import com.spectrayan.spector.memory.synapse.SynapticHeaderConstants;
import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.commons.error.SpectorMemoryException;
import com.spectrayan.spector.commons.error.SpectorStorageException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Partitioned semantic memory store using rolling {@code .mem} files.
 *
 * <h3>Design</h3>
 * <p>Instead of a single monolithic mmap'd file, semantic memories are
 * distributed across fixed-capacity partitions. When the active (write)
 * partition fills up, a new partition is created automatically — similar
 * to a rolling file appender.</p>
 *
 * <h3>Partition Layout</h3>
 * <p>Each partition is a {@link SemanticMemoryStore} backed by a file named
 * {@code semantic-NNN.mem} where NNN is a zero-padded sequence number.
 * Partitions are immutable once full (read-only), with only the newest
 * partition accepting writes.</p>
 *
 * <h3>Concurrency</h3>
 * <ul>
 *   <li>Reads (recall) acquire a shared read lock and scan all partitions
 *       in parallel using virtual threads</li>
 *   <li>Writes (remember) acquire an exclusive write lock only when rolling
 *       to a new partition; normal appends are synchronized on the active
 *       partition</li>
 *   <li>The partition list uses {@link CopyOnWriteArrayList} for lock-free
 *       read iteration during recall</li>
 * </ul>
 *
 * <h3>Compaction</h3>
 * <p>When tombstoned records accumulate, individual partitions can be
 * compacted (rebuilt) without blocking reads on other partitions.</p>
 *
 * @see SemanticMemoryStore for individual partition implementation
 */
public final class PartitionedSemanticStore implements TierStore {

    private static final Logger log = LoggerFactory.getLogger(PartitionedSemanticStore.class);

    /** File name pattern for partition files. */
    private static final String PARTITION_PREFIX = "semantic-";
    private static final String PARTITION_SUFFIX = ".mem";

    private final int quantizedVecBytes;
    private final int nodesPerPartition;
    private final Path partitionDir;
    private final CognitiveRecordLayout layout;

    /** All partitions (oldest first). Thread-safe for concurrent reads. */
    private final CopyOnWriteArrayList<SemanticPartition> partitions = new CopyOnWriteArrayList<>();

    /** Lock for partition rolling (write lock) vs. parallel reads (read lock). */
    private final ReadWriteLock rollLock = new ReentrantReadWriteLock();

    /** The current active (writable) partition. */
    private volatile SemanticPartition activePartition;

    /**
     * Creates or loads a partitioned semantic store from disk.
     *
     * @param quantizedVecBytes bytes per quantized vector
     * @param nodesPerPartition max records per partition before rolling
     * @param partitionDir      directory containing partition files
     */
    public PartitionedSemanticStore(int quantizedVecBytes, int nodesPerPartition, Path partitionDir) {
        this.quantizedVecBytes = quantizedVecBytes;
        this.nodesPerPartition = nodesPerPartition;
        this.partitionDir = partitionDir;
        this.layout = new CognitiveRecordLayout(quantizedVecBytes);

        try {
            Files.createDirectories(partitionDir);
        } catch (IOException e) {
            throw new SpectorStorageException(ErrorCode.PARTITION_DIR_FAILED, e, partitionDir);
        }

        // Load existing partitions
        loadPartitions();

        // If no partitions exist, create the first one
        if (partitions.isEmpty()) {
            rollNewPartition();
        } else {
            // The last partition is the active one
            activePartition = partitions.getLast();
        }

        log.info("PartitionedSemanticStore initialized: partitions={}, nodesPerPartition={}, totalRecords={}, dir={}",
                partitions.size(), nodesPerPartition, size(), partitionDir);
    }

    // ─────────────── Writes ───────────────

    /**
     * Stores a semantic memory header in the active partition.
     *
     * <p>If the active partition is full, rolls to a new one automatically.</p>
     *
     * @param header the cognitive header to store
     * @return the global record index (partition index * nodesPerPartition + local index)
     */
    public int store(CognitiveHeader header) {
        rollLock.readLock().lock();
        try {
            SemanticPartition active = activePartition;
            if (active.isFull()) {
                // Upgrade to write lock for rolling
                rollLock.readLock().unlock();
                rollLock.writeLock().lock();
                try {
                    // Double-check after acquiring write lock
                    if (activePartition.isFull()) {
                        rollNewPartition();
                    }
                    active = activePartition;
                } finally {
                    // Downgrade to read lock
                    rollLock.readLock().lock();
                    rollLock.writeLock().unlock();
                }
            }

            int localIndex = active.store().store(header);
            return active.globalOffset() + localIndex;
        } finally {
            rollLock.readLock().unlock();
        }
    }

    @Override
    public long write(CognitiveHeader header, byte[] quantizedVec) {
        int globalIndex = store(header);
        return globalIndex;
    }

    // ─────────────── Reads ───────────────

    /**
     * Reads a cognitive header by global index.
     *
     * @param globalIndex the global record index
     * @return the cognitive header at that index
     * @throws IndexOutOfBoundsException if the index is invalid
     */
    public CognitiveHeader readHeader(int globalIndex) {
        int partitionIdx = globalIndex / nodesPerPartition;
        int localIdx = globalIndex % nodesPerPartition;

        if (partitionIdx < 0 || partitionIdx >= partitions.size()) {
            throw new SpectorMemoryException(ErrorCode.PARTITION_INDEX_INVALID, partitionIdx, partitions.size());
        }

        return partitions.get(partitionIdx).store().readHeader(localIdx);
    }

    /**
     * Returns a snapshot of all partitions for parallel recall.
     *
     * <p>The returned list is safe to iterate without synchronization.
     * Each partition can be searched independently on a virtual thread.</p>
     */
    public List<SemanticPartition> partitions() {
        return List.copyOf(partitions);
    }

    /**
     * Returns the number of partitions.
     */
    public int partitionCount() {
        return partitions.size();
    }

    // ─────────────── Compaction ───────────────

    /**
     * Compacts a specific partition by rebuilding it without tombstoned records.
     *
     * <p>During compaction, reads on other partitions continue unaffected.
     * The old partition remains readable until the swap is complete.</p>
     *
     * <p>Algorithm:</p>
     * <ol>
     *   <li>Count tombstoned records. If zero, skip.</li>
     *   <li>Create a new partition file ({@code .compact} suffix)</li>
     *   <li>Copy all live (non-tombstoned) records to the new store</li>
     *   <li>Close the old partition store</li>
     *   <li>Atomically rename the compacted file to the original name</li>
     *   <li>Swap the partition in the list</li>
     * </ol>
     *
     * @param partitionIndex the index of the partition to compact
     * @return the number of records reclaimed
     */
    public int compact(int partitionIndex) {
        if (partitionIndex < 0 || partitionIndex >= partitions.size()) {
            throw new SpectorMemoryException(ErrorCode.PARTITION_INDEX_INVALID, partitionIndex, partitions.size());
        }

        SemanticPartition partition = partitions.get(partitionIndex);
        SemanticMemoryStore oldStore = partition.store();

        int before = oldStore.size();
        int tombstoned = 0;

        // Count tombstoned records
        for (int i = 0; i < before; i++) {
            CognitiveHeader header = oldStore.readHeader(i);
            if (SynapticHeaderConstants.isTombstoned(header.flags())) {
                tombstoned++;
            }
        }

        if (tombstoned == 0) {
            log.debug("Partition {} has no tombstoned records, skipping compaction", partitionIndex);
            return 0;
        }

        log.info("Compacting partition {}: {} tombstoned of {} total records",
                partitionIndex, tombstoned, before);

        // Create compacted partition file
        Path compactPath = partition.filePath().resolveSibling(
                partition.filePath().getFileName() + ".compact");
        SemanticMemoryStore newStore = new SemanticMemoryStore(
                quantizedVecBytes, nodesPerPartition, compactPath);

        // Copy live records
        int copied = 0;
        for (int i = 0; i < before; i++) {
            CognitiveHeader header = oldStore.readHeader(i);
            if (!SynapticHeaderConstants.isTombstoned(header.flags())) {
                newStore.store(header);
                copied++;
            }
        }

        // Close old store and swap
        oldStore.close();

        // Rename: .compact → original name
        try {
            Path originalPath = partition.filePath();
            Files.deleteIfExists(originalPath);
            Files.move(compactPath, originalPath);
        } catch (IOException e) {
            log.error("Failed to rename compacted partition {}: {}", partitionIndex, e.getMessage());
            newStore.close();
            return 0;
        }

        // Reload from the renamed file
        SemanticMemoryStore reloadedStore = new SemanticMemoryStore(
                quantizedVecBytes, nodesPerPartition, partition.filePath());
        newStore.close();

        // Atomic swap in the partition list
        SemanticPartition compacted = new SemanticPartition(
                partition.index(), partition.globalOffset(), reloadedStore, partition.filePath());
        partitions.set(partitionIndex, compacted);

        int reclaimed = before - copied;
        log.info("Compaction complete for partition {}: {} → {} records ({} reclaimed)",
                partitionIndex, before, copied, reclaimed);
        return reclaimed;
    }

    // ─────────────── TierStore Interface ───────────────

    @Override
    public MemoryType type() {
        return MemoryType.SEMANTIC;
    }

    @Override
    public int size() {
        int total = 0;
        for (SemanticPartition p : partitions) {
            total += p.store().size();
        }
        return total;
    }

    @Override
    public CognitiveRecordLayout layout() {
        return layout;
    }

    @Override
    public MemorySegment primarySegment() {
        // Return the active partition's segment for backward compatibility
        return activePartition != null ? activePartition.store().primarySegment() : null;
    }

    @Override
    public void close() {
        log.info("Closing PartitionedSemanticStore ({} partitions, {} total records)",
                partitions.size(), size());
        for (SemanticPartition partition : partitions) {
            partition.store().close();
        }
        partitions.clear();
    }

    // ─────────────── Internal ───────────────

    /**
     * Loads existing partition files from the partition directory.
     */
    private void loadPartitions() {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(partitionDir,
                PARTITION_PREFIX + "*" + PARTITION_SUFFIX)) {

            List<Path> files = new ArrayList<>();
            for (Path path : stream) {
                files.add(path);
            }
            files.sort(Comparator.comparing(Path::getFileName));

            for (int i = 0; i < files.size(); i++) {
                Path file = files.get(i);
                SemanticMemoryStore store = new SemanticMemoryStore(quantizedVecBytes, nodesPerPartition, file);
                int globalOffset = i * nodesPerPartition;
                partitions.add(new SemanticPartition(i, globalOffset, store, file));
                log.debug("Loaded partition {}: {} records from {}", i, store.size(), file.getFileName());
            }
        } catch (IOException e) {
            log.warn("Error loading partitions from {}: {}", partitionDir, e.getMessage());
        }
    }

    /**
     * Creates a new partition and makes it the active write target.
     */
    private void rollNewPartition() {
        int seqNo = partitions.size();
        String fileName = String.format("%s%03d%s", PARTITION_PREFIX, seqNo, PARTITION_SUFFIX);
        Path file = partitionDir.resolve(fileName);

        SemanticMemoryStore store = new SemanticMemoryStore(quantizedVecBytes, nodesPerPartition, file);
        int globalOffset = seqNo * nodesPerPartition;
        SemanticPartition partition = new SemanticPartition(seqNo, globalOffset, store, file);

        partitions.add(partition);
        activePartition = partition;

        log.info("Rolled new partition {}: {} (capacity={})", seqNo, file.getFileName(), nodesPerPartition);
    }

    // ─────────────── Partition Record ───────────────

    /**
     * Represents a single partition within the partitioned store.
     *
     * @param index        the partition sequence number (0-based)
     * @param globalOffset the global index offset for records in this partition
     * @param store        the underlying memory store
     * @param filePath     the backing file path
     */
    public record SemanticPartition(
            int index,
            int globalOffset,
            SemanticMemoryStore store,
            Path filePath
    ) {
        /** Returns true if this partition is at capacity. */
        public boolean isFull() {
            return store.size() >= store.capacity();
        }

        /** Returns the partition's header slab for direct scorer access. */
        public MemorySegment headerSlab() {
            return store.headerSlab();
        }
    }
}
