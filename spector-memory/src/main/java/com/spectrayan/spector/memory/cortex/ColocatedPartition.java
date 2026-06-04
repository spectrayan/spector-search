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
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Represents a single colocated partition directory containing all tier data.
 *
 * <h3>Directory Layout</h3>
 * <pre>
 *   NNN_EPOCH/
 *   ├── semantic.mem
 *   ├── episodic.mem
 *   ├── procedural.mem
 *   ├── text.dat
 *   ├── index.midx
 *   ├── hebbian.graph
 *   ├── temporal.chain
 *   └── entity.graph
 * </pre>
 *
 * <h3>Lifecycle</h3>
 * <p>Partitions start as <b>active</b> (writable). When any tier hits its
 * configured capacity, the partition <b>rolls</b> — becoming frozen (read-only)
 * and a new active partition is created. Frozen partitions are scanned in parallel
 * during recall but never written to.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>Individual stores within the partition handle their own concurrency.
 * The partition itself is a value holder — immutable after construction.</p>
 *
 * @see ColocatedPartitionManager
 * @see StorageLayout
 */
public final class ColocatedPartition {

    private static final Logger log = LoggerFactory.getLogger(ColocatedPartition.class);

    private final int seqNo;
    private final long createdEpochSecs;
    private final Path directory;
    private final TextDataStore textStore;
    private volatile boolean frozen;

    // Counts per tier (tracked by the partition manager or loaded from index)
    private volatile int semanticCount;
    private volatile int episodicCount;
    private volatile int proceduralCount;

    /**
     * Creates or opens a colocated partition at the given directory.
     *
     * @param seqNo            zero-based partition sequence number
     * @param createdEpochSecs creation time as Unix epoch seconds
     * @param directory        path to the partition directory
     * @param frozen           true if this partition is read-only
     */
    public ColocatedPartition(int seqNo, long createdEpochSecs, Path directory, boolean frozen) {
        this.seqNo = seqNo;
        this.createdEpochSecs = createdEpochSecs;
        this.directory = directory;
        this.frozen = frozen;
        this.textStore = TextDataStore.forPartition(directory);

        // Ensure directory exists
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create partition directory: " + directory, e);
        }
    }

    /** Zero-based partition sequence number. */
    public int seqNo() { return seqNo; }

    /** Creation time as Unix epoch seconds. */
    public long createdEpochSecs() { return createdEpochSecs; }

    /** Path to the partition directory. */
    public Path directory() { return directory; }

    /** Directory name (e.g., "000_1717430400"). */
    public String dirName() { return StorageLayout.partitionDirName(seqNo, createdEpochSecs); }

    /** Whether this partition is read-only (frozen). */
    public boolean isFrozen() { return frozen; }

    /** Marks this partition as frozen (read-only). */
    public void freeze() {
        this.frozen = true;
        log.info("Partition {} frozen: {}", seqNo, directory);
    }

    /** The text.dat store for this partition. */
    public TextDataStore textStore() { return textStore; }

    // ── Tier file paths ──

    /** Path to semantic.mem within this partition. */
    public Path semanticPath() { return StorageLayout.semanticMem(directory); }

    /** Path to episodic.mem within this partition. */
    public Path episodicPath() { return StorageLayout.episodicMem(directory); }

    /** Path to procedural.mem within this partition. */
    public Path proceduralPath() { return StorageLayout.proceduralMem(directory); }

    /** Path to text.dat within this partition. */
    public Path textPath() { return StorageLayout.textDat(directory); }

    /** Path to index.midx within this partition. */
    public Path indexPath() { return StorageLayout.indexMidx(directory); }

    /** Path to hebbian.graph within this partition. */
    public Path hebbianPath() { return StorageLayout.hebbianGraph(directory); }

    /** Path to temporal.chain within this partition. */
    public Path temporalPath() { return StorageLayout.temporalChain(directory); }

    /** Path to entity.graph within this partition. */
    public Path entityPath() { return StorageLayout.entityGraph(directory); }

    // ── Count tracking ──

    public int semanticCount() { return semanticCount; }
    public int episodicCount() { return episodicCount; }
    public int proceduralCount() { return proceduralCount; }

    public void incrementSemantic() { semanticCount++; }
    public void incrementEpisodic() { episodicCount++; }
    public void incrementProcedural() { proceduralCount++; }

    public void setCounts(int semantic, int episodic, int procedural) {
        this.semanticCount = semantic;
        this.episodicCount = episodic;
        this.proceduralCount = procedural;
    }

    /** Total memories across all tiers in this partition. */
    public int totalCount() {
        return semanticCount + episodicCount + proceduralCount;
    }

    /**
     * Checks if this partition should roll based on per-tier capacities.
     *
     * @param semanticCap   maximum semantic memories per partition
     * @param episodicCap   maximum episodic memories per partition
     * @param proceduralCap maximum procedural memories per partition
     * @return true if any tier has reached its capacity
     */
    public boolean shouldRoll(int semanticCap, int episodicCap, int proceduralCap) {
        return semanticCount >= semanticCap
                || episodicCount >= episodicCap
                || proceduralCount >= proceduralCap;
    }

    @Override
    public String toString() {
        return String.format("ColocatedPartition[%s, frozen=%s, sem=%d, epi=%d, proc=%d]",
                dirName(), frozen, semanticCount, episodicCount, proceduralCount);
    }
}
