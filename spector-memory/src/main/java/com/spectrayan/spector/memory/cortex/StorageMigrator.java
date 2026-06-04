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

import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout.CognitiveHeader;
import com.spectrayan.spector.memory.synapse.SynapticHeaderConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Migrates legacy single-file semantic storage to the partitioned format.
 *
 * <h3>Problem</h3>
 * <p>Existing Spector installations use a single {@code semantic.mem} file
 * for all semantic memories. The new {@link PartitionedSemanticStore} uses
 * rolling {@code semantic/semantic-NNN.mem} files. This migrator handles
 * the format upgrade transparently.</p>
 *
 * <h3>Migration Strategy</h3>
 * <ol>
 *   <li>Detect if legacy {@code semantic.mem} exists at the base path</li>
 *   <li>Create the {@code semantic/} directory</li>
 *   <li>Read all live records from the legacy file</li>
 *   <li>Write them into partitioned files (respecting {@code nodesPerPartition})</li>
 *   <li>Rename the legacy file to {@code semantic.mem.migrated} as backup</li>
 * </ol>
 *
 * <h3>Safety</h3>
 * <ul>
 *   <li>The legacy file is never deleted — only renamed</li>
 *   <li>Migration is idempotent — if partitioned files already exist, skip</li>
 *   <li>Tombstoned records are not migrated (free compaction)</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   StorageMigrator migrator = new StorageMigrator(384, 10_000);
 *   if (migrator.needsMigration(basePath)) {
 *       StorageMigrator.MigrationResult result = migrator.migrate(basePath);
 *       log.info("Migrated {} records", result.migratedRecords());
 *   }
 * }</pre>
 *
 * @deprecated Since V4. Migrates to the now-deprecated {@link PartitionedSemanticStore}
 * format ({@code semantic/semantic-NNN.mem}). The current architecture uses
 * directory-level partition rolling with a single {@code semantic.mem} per
 * colocated partition directory.
 */
@Deprecated(since = "4.0", forRemoval = true)
public final class StorageMigrator {

    private static final Logger log = LoggerFactory.getLogger(StorageMigrator.class);

    /** Legacy file name for single-file semantic storage. */
    private static final String LEGACY_FILE = "semantic.mem";

    /** Backup suffix for migrated legacy files. */
    private static final String MIGRATED_SUFFIX = ".migrated";

    /** Subdirectory for partitioned semantic storage. */
    private static final String PARTITION_DIR = "semantic";

    private final int quantizedVecBytes;
    private final int nodesPerPartition;

    /**
     * Creates a storage migrator.
     *
     * @param quantizedVecBytes bytes per quantized vector (for layout calculation)
     * @param nodesPerPartition max records per partition in the new format
     */
    public StorageMigrator(int quantizedVecBytes, int nodesPerPartition) {
        this.quantizedVecBytes = quantizedVecBytes;
        this.nodesPerPartition = nodesPerPartition;
    }

    /**
     * Checks whether migration is needed at the given base path.
     *
     * <p>Migration is needed when the legacy {@code semantic.mem} file exists
     * but no partitioned {@code semantic/} directory with partition files exists.</p>
     *
     * @param basePath the memory persistence base path
     * @return true if migration is needed
     */
    public boolean needsMigration(Path basePath) {
        Path legacyFile = basePath.resolve(LEGACY_FILE);
        Path partitionDir = basePath.resolve(PARTITION_DIR);

        // Need migration if legacy file exists but partition dir does not (or is empty)
        if (!Files.exists(legacyFile)) {
            return false;
        }

        if (!Files.isDirectory(partitionDir)) {
            return true;
        }

        // Check if partition dir has any .mem files
        try (var stream = Files.newDirectoryStream(partitionDir, "semantic-*.mem")) {
            return !stream.iterator().hasNext();
        } catch (IOException e) {
            log.warn("Error checking partition directory: {}", e.getMessage());
            return true;
        }
    }

    /**
     * Migrates from single-file to partitioned semantic storage.
     *
     * @param basePath the memory persistence base path
     * @return migration result with statistics
     * @throws com.spectrayan.spector.commons.error.SpectorStorageException if file operations fail
     */
    public MigrationResult migrate(Path basePath) {
        Path legacyFile = basePath.resolve(LEGACY_FILE);
        Path partitionDir = basePath.resolve(PARTITION_DIR);

        if (!Files.exists(legacyFile)) {
            log.info("No legacy semantic.mem found at {} — skipping migration", basePath);
            return MigrationResult.NONE;
        }

        log.info("Starting migration from {} to partitioned format", legacyFile);

        // Open legacy store (read-only effectively — we just read headers)
        SemanticMemoryStore legacyStore = new SemanticMemoryStore(
                quantizedVecBytes, 1_000_000, legacyFile);  // large capacity to read all

        int totalRecords = legacyStore.size();
        log.info("Legacy store contains {} records", totalRecords);

        // Create partitioned store
        PartitionedSemanticStore partitionedStore = new PartitionedSemanticStore(
                quantizedVecBytes, nodesPerPartition, partitionDir);

        // Copy live records (skip tombstoned = free compaction)
        int migrated = 0;
        int skipped = 0;
        for (int i = 0; i < totalRecords; i++) {
            CognitiveHeader header = legacyStore.readHeader(i);
            if (SynapticHeaderConstants.isTombstoned(header.flags())) {
                skipped++;
                continue;
            }
            partitionedStore.store(header);
            migrated++;
        }

        // Close both stores
        partitionedStore.close();
        legacyStore.close();

        // Rename legacy file as backup
        Path backupFile = basePath.resolve(LEGACY_FILE + MIGRATED_SUFFIX);
        try {
            Files.move(legacyFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
            log.info("Legacy file backed up to {}", backupFile);
        } catch (IOException e) {
            log.warn("Could not rename legacy file to backup: {}", e.getMessage());
        }

        MigrationResult result = new MigrationResult(
                totalRecords, migrated, skipped,
                partitionedStore.partitionCount());

        log.info("Migration complete: {} total → {} migrated, {} skipped (tombstoned), {} partitions created",
                totalRecords, migrated, skipped, result.partitionsCreated());

        return result;
    }

    /**
     * Result of a storage migration operation.
     *
     * @param totalRecords      total records in the legacy store
     * @param migratedRecords   records successfully migrated
     * @param skippedTombstoned records skipped (tombstoned = free compaction)
     * @param partitionsCreated number of partition files created
     */
    public record MigrationResult(
            int totalRecords,
            int migratedRecords,
            int skippedTombstoned,
            int partitionsCreated
    ) {
        /** No migration was performed. */
        public static final MigrationResult NONE = new MigrationResult(0, 0, 0, 0);
    }
}
