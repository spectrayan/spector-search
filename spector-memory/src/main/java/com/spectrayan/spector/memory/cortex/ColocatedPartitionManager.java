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

import com.spectrayan.spector.memory.StorageLayout;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;

/**
 * Manages the lifecycle of colocated partitions under a namespace.
 *
 * <h3>Partition Lifecycle</h3>
 * <ol>
 *   <li><b>Discovery</b>: On startup, scans the partitions directory for existing
 *       partition directories matching {@code NNN_EPOCH} pattern</li>
 *   <li><b>Active Partition</b>: The newest partition (highest seqNo) is the active
 *       write target. All others are frozen (read-only).</li>
 *   <li><b>Rolling</b>: When any tier in the active partition hits its capacity,
 *       the partition freezes and a new one is created.</li>
 * </ol>
 *
 * <h3>Naming Convention</h3>
 * <p>Partition directories follow the format {@code NNN_EPOCH} where:
 * <ul>
 *   <li>{@code NNN} — zero-padded 3-digit sequence number (000–999)</li>
 *   <li>{@code EPOCH} — Unix epoch seconds at creation time</li>
 * </ul>
 * This enables lexicographic sorting for SSD readahead while preserving
 * temporal ordering.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>Uses {@link CopyOnWriteArrayList} for the partition list — safe for concurrent
 * recall reads while allowing partition additions from the ingestion thread.</p>
 *
 * @see ColocatedPartition
 * @see StorageLayout
 */
public final class ColocatedPartitionManager {

    private static final Logger log = LoggerFactory.getLogger(ColocatedPartitionManager.class);

    private final Path partitionsRoot;
    private final CopyOnWriteArrayList<ColocatedPartition> partitions;
    private volatile ColocatedPartition activePartition;

    // Per-tier capacity thresholds for partition rolling
    private final int semanticCapPerPartition;
    private final int episodicCapPerPartition;
    private final int proceduralCapPerPartition;

    /**
     * Creates or opens a partition manager rooted at the given path.
     *
     * @param partitionsRoot          root directory containing partition subdirs
     * @param semanticCapPerPartition max semantic memories per partition before roll
     * @param episodicCapPerPartition max episodic memories per partition before roll
     * @param proceduralCapPerPartition max procedural memories per partition before roll
     */
    public ColocatedPartitionManager(Path partitionsRoot,
                                      int semanticCapPerPartition,
                                      int episodicCapPerPartition,
                                      int proceduralCapPerPartition) {
        this.partitionsRoot = partitionsRoot;
        this.semanticCapPerPartition = semanticCapPerPartition;
        this.episodicCapPerPartition = episodicCapPerPartition;
        this.proceduralCapPerPartition = proceduralCapPerPartition;

        // Create root directory if needed
        try {
            Files.createDirectories(partitionsRoot);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create partitions root: " + partitionsRoot, e);
        }

        // Discover existing partitions
        this.partitions = new CopyOnWriteArrayList<>(discoverPartitions());

        // Set active partition (newest = last in sorted list)
        if (!partitions.isEmpty()) {
            this.activePartition = partitions.getLast();
            // All except the last are frozen
            for (int i = 0; i < partitions.size() - 1; i++) {
                partitions.get(i).freeze();
            }
            log.info("Opened {} existing partitions, active: {}",
                    partitions.size(), activePartition.dirName());
        } else {
            // Create first partition
            this.activePartition = createNewPartition();
            log.info("Created initial partition: {}", activePartition.dirName());
        }
    }

    /**
     * Returns the current active (writable) partition.
     */
    public ColocatedPartition active() {
        return activePartition;
    }

    /**
     * Returns all partitions (including active), ordered by sequence number.
     */
    public List<ColocatedPartition> all() {
        return List.copyOf(partitions);
    }

    /**
     * Returns all frozen (read-only) partitions.
     */
    public List<ColocatedPartition> frozen() {
        List<ColocatedPartition> result = new ArrayList<>();
        for (ColocatedPartition p : partitions) {
            if (p.isFrozen()) result.add(p);
        }
        return result;
    }

    /**
     * Returns the total number of partitions.
     */
    public int count() {
        return partitions.size();
    }

    /**
     * Returns the partition at the given index.
     *
     * @param index zero-based partition index
     * @return the partition at that index
     */
    public ColocatedPartition get(int index) {
        return partitions.get(index);
    }

    /**
     * Checks if the active partition needs to roll and performs the roll if so.
     *
     * @return the (possibly new) active partition
     */
    public ColocatedPartition checkAndRoll() {
        if (activePartition.shouldRoll(semanticCapPerPartition,
                episodicCapPerPartition, proceduralCapPerPartition)) {
            return roll();
        }
        return activePartition;
    }

    /**
     * Forces a partition roll — freezes the current active and creates a new one.
     *
     * @return the new active partition
     */
    public ColocatedPartition roll() {
        activePartition.freeze();
        ColocatedPartition newActive = createNewPartition();
        this.activePartition = newActive;
        log.info("Partition rolled: {} → {}", partitions.get(partitions.size() - 2).dirName(),
                newActive.dirName());
        return newActive;
    }

    /**
     * Returns the root directory for all partitions.
     */
    public Path root() {
        return partitionsRoot;
    }

    // ── Internal helpers ──

    private List<ColocatedPartition> discoverPartitions() {
        List<ColocatedPartition> discovered = new ArrayList<>();

        if (!Files.isDirectory(partitionsRoot)) {
            return discovered;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(partitionsRoot)) {
            for (Path entry : stream) {
                if (!Files.isDirectory(entry)) continue;

                String dirName = entry.getFileName().toString();
                Matcher matcher = StorageLayout.PARTITION_DIR_PATTERN.matcher(dirName);
                if (matcher.matches()) {
                    int seqNo = Integer.parseInt(matcher.group(1));
                    long epochSecs = Long.parseLong(matcher.group(2));
                    discovered.add(new ColocatedPartition(seqNo, epochSecs, entry, false));
                } else {
                    log.debug("Skipping non-partition directory: {}", dirName);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan partitions directory: " + partitionsRoot, e);
        }

        // Sort by sequence number ascending
        discovered.sort(Comparator.comparingInt(ColocatedPartition::seqNo));
        return discovered;
    }

    private ColocatedPartition createNewPartition() {
        int nextSeq = partitions.isEmpty() ? 0 : partitions.getLast().seqNo() + 1;
        long epochSecs = System.currentTimeMillis() / 1000;

        String dirName = StorageLayout.partitionDirName(nextSeq, epochSecs);
        Path partitionDir = partitionsRoot.resolve(dirName);

        ColocatedPartition partition = new ColocatedPartition(nextSeq, epochSecs, partitionDir, false);
        partitions.add(partition);
        return partition;
    }
}
