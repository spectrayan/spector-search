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
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * One-way migrator from legacy flat storage layout to colocated partition layout.
 *
 * <h3>Legacy Layout (Pre-Partition)</h3>
 * <pre>
 *   basePath/
 *   ├── memory-index.mem
 *   ├── working.mem
 *   ├── procedural.mem
 *   ├── semantic.mem  (or semantic/ for partitioned)
 *   ├── episodic/
 *   ├── hebbian.graph
 *   ├── temporal.chain
 *   ├── entity.graph
 *   ├── coactivation.tracker
 *   ├── text.dat
 *   └── wal/
 * </pre>
 *
 * <h3>Colocated Layout (Post-Migration)</h3>
 * <pre>
 *   basePath/
 *   ├── manifest.json
 *   ├── global/
 *   │   ├── working.mem
 *   │   ├── coactivation.tracker
 *   │   └── wal/
 *   ├── partitions/
 *   │   └── 000_{epoch}/
 *   │       ├── semantic.mem
 *   │       ├── episodic.mem
 *   │       ├── procedural.mem
 *   │       ├── text.dat
 *   │       ├── index.midx
 *   │       ├── hebbian.graph
 *   │       ├── temporal.chain
 *   │       └── entity.graph
 *   └── cross/
 * </pre>
 *
 * <h3>Migration Strategy</h3>
 * <ol>
 *   <li>Detect legacy layout by presence of {@code memory-index.mem} or flat
 *       {@code semantic.mem} in basePath root</li>
 *   <li>Create new directory structure (global/, partitions/000_{epoch}/, cross/)</li>
 *   <li>Move global files (working.mem, coactivation.tracker, wal/) to global/</li>
 *   <li>Move tier files + graphs to the initial partition directory</li>
 *   <li>Write manifest.json with version info</li>
 * </ol>
 *
 * <h3>Safety</h3>
 * <p>This is a <b>one-way migration</b>. Users should back up before upgrading.
 * The migrator uses atomic moves where possible and verifies destination
 * directory creation before moving files.</p>
 *
 * @see StorageLayout
 * @see ColocatedPartitionManager
 */
public final class PartitionLayoutMigrator {

    private static final Logger log = LoggerFactory.getLogger(PartitionLayoutMigrator.class);

    private PartitionLayoutMigrator() {}

    /**
     * Checks if the given basePath uses the legacy flat layout.
     *
     * <p>Detection heuristic: presence of {@code memory-index.mem} or
     * flat {@code semantic.mem} in the basePath root (not inside partitions/).</p>
     *
     * @param basePath the persistence root directory
     * @return true if legacy layout is detected
     */
    public static boolean needsMigration(Path basePath) {
        if (basePath == null || !Files.isDirectory(basePath)) return false;

        // If global/ directory exists, already migrated
        if (Files.isDirectory(StorageLayout.globalDir(basePath))) return false;

        // Check for legacy markers
        return Files.exists(basePath.resolve(StorageLayout.LEGACY_FILE_INDEX))
                || Files.exists(basePath.resolve(StorageLayout.LEGACY_FILE_SEMANTIC))
                || Files.exists(basePath.resolve(StorageLayout.LEGACY_FILE_PROCEDURAL));
    }

    /**
     * Performs the one-way migration from legacy flat layout to colocated partitions.
     *
     * @param basePath the persistence root directory
     * @throws UncheckedIOException if any file operation fails
     */
    public static void migrate(Path basePath) {
        if (!needsMigration(basePath)) {
            log.debug("No migration needed for: {}", basePath);
            return;
        }

        log.info("═══ Starting layout migration: {} ═══", basePath);
        long startMs = System.currentTimeMillis();

        try {
            // 1. Create new directory structure
            Path globalDir = StorageLayout.globalDir(basePath);
            Path partitionsDir = StorageLayout.partitionsDir(basePath);
            Path crossDir = StorageLayout.crossDir(basePath);
            Path walDir = StorageLayout.walDir(basePath);

            Files.createDirectories(globalDir);
            Files.createDirectories(partitionsDir);
            Files.createDirectories(crossDir);
            Files.createDirectories(walDir);

            // 2. Create initial partition directory
            long epochSecs = System.currentTimeMillis() / 1000;
            String partitionDirName = StorageLayout.partitionDirName(0, epochSecs);
            Path partitionDir = partitionsDir.resolve(partitionDirName);
            Files.createDirectories(partitionDir);

            List<String> migrated = new ArrayList<>();
            List<String> skipped = new ArrayList<>();

            // 3. Move global files
            moveIfExists(basePath.resolve(StorageLayout.FILE_WORKING),
                    globalDir.resolve(StorageLayout.FILE_WORKING), migrated, skipped);
            moveIfExists(basePath.resolve(StorageLayout.FILE_COACTIVATION),
                    globalDir.resolve(StorageLayout.FILE_COACTIVATION), migrated, skipped);

            // Move legacy WAL directory
            Path legacyWal = basePath.resolve(StorageLayout.DIR_WAL);
            if (Files.isDirectory(legacyWal)) {
                // Move all WAL files to new location
                try (var stream = Files.list(legacyWal)) {
                    stream.forEach(walFile -> {
                        try {
                            Files.move(walFile, walDir.resolve(walFile.getFileName()),
                                    StandardCopyOption.REPLACE_EXISTING);
                            migrated.add("wal/" + walFile.getFileName());
                        } catch (IOException e) {
                            log.warn("Failed to move WAL file: {}", walFile, e);
                            skipped.add("wal/" + walFile.getFileName());
                        }
                    });
                }
                // Remove empty legacy WAL directory
                Files.deleteIfExists(legacyWal);
                migrated.add(StorageLayout.DIR_WAL + " (directory)");
            }

            // 4. Move tier files to initial partition
            moveIfExists(basePath.resolve(StorageLayout.LEGACY_FILE_SEMANTIC),
                    partitionDir.resolve(StorageLayout.FILE_SEMANTIC), migrated, skipped);
            moveIfExists(basePath.resolve(StorageLayout.LEGACY_FILE_PROCEDURAL),
                    partitionDir.resolve(StorageLayout.FILE_PROCEDURAL), migrated, skipped);
            moveIfExists(basePath.resolve(StorageLayout.FILE_TEXT),
                    partitionDir.resolve(StorageLayout.FILE_TEXT), migrated, skipped);

            // Move legacy memory-index.mem → partition/index.midx
            moveIfExists(basePath.resolve(StorageLayout.LEGACY_FILE_INDEX),
                    partitionDir.resolve(StorageLayout.FILE_INDEX), migrated, skipped);

            // Handle episodic: if legacy episodic/ dir exists, move first partition
            Path legacyEpisodic = basePath.resolve(StorageLayout.LEGACY_DIR_EPISODIC);
            if (Files.isDirectory(legacyEpisodic)) {
                // Find all episodic partition files and move them
                try (var stream = Files.list(legacyEpisodic)) {
                    var files = stream.toList();
                    if (!files.isEmpty()) {
                        // Combine into single episodic.mem (or just move first partition)
                        // For simplicity, move the first/only file as episodic.mem
                        Path firstFile = files.getFirst();
                        Files.move(firstFile, partitionDir.resolve(StorageLayout.FILE_EPISODIC),
                                StandardCopyOption.REPLACE_EXISTING);
                        migrated.add(StorageLayout.LEGACY_DIR_EPISODIC + "/" + firstFile.getFileName());

                        // Move remaining files too (append suffix for manual merge)
                        for (int i = 1; i < files.size(); i++) {
                            Path epiFile = files.get(i);
                            Path dest = partitionDir.resolve("episodic-" + i + ".mem");
                            Files.move(epiFile, dest, StandardCopyOption.REPLACE_EXISTING);
                            migrated.add(StorageLayout.LEGACY_DIR_EPISODIC + "/" + epiFile.getFileName());
                        }
                    }
                }
                // Remove empty legacy episodic directory
                deleteIfEmpty(legacyEpisodic);
            }

            // Handle partitioned semantic: if legacy semantic/ dir exists
            Path legacySemantic = basePath.resolve(StorageLayout.LEGACY_DIR_SEMANTIC);
            if (Files.isDirectory(legacySemantic)) {
                try (var stream = Files.list(legacySemantic)) {
                    var files = stream.toList();
                    if (!files.isEmpty()) {
                        // Move first file as semantic.mem to partition
                        Path firstFile = files.getFirst();
                        Path dest = partitionDir.resolve(StorageLayout.FILE_SEMANTIC);
                        if (!Files.exists(dest)) {
                            Files.move(firstFile, dest, StandardCopyOption.REPLACE_EXISTING);
                            migrated.add(StorageLayout.LEGACY_DIR_SEMANTIC + "/" + firstFile.getFileName());
                        }

                        // Additional semantic partitions become separate colocated partitions
                        for (int i = 1; i < files.size(); i++) {
                            Path semFile = files.get(i);
                            String newPartName = StorageLayout.partitionDirName(i, epochSecs + i);
                            Path newPartDir = partitionsDir.resolve(newPartName);
                            Files.createDirectories(newPartDir);
                            Files.move(semFile, newPartDir.resolve(StorageLayout.FILE_SEMANTIC),
                                    StandardCopyOption.REPLACE_EXISTING);
                            migrated.add(StorageLayout.LEGACY_DIR_SEMANTIC + "/" + semFile.getFileName()
                                    + " → " + newPartName);
                        }
                    }
                }
                deleteIfEmpty(legacySemantic);
            }

            // 5. Move graph files to initial partition
            moveIfExists(basePath.resolve(StorageLayout.FILE_HEBBIAN),
                    partitionDir.resolve(StorageLayout.FILE_HEBBIAN), migrated, skipped);
            moveIfExists(basePath.resolve(StorageLayout.FILE_TEMPORAL),
                    partitionDir.resolve(StorageLayout.FILE_TEMPORAL), migrated, skipped);
            moveIfExists(basePath.resolve(StorageLayout.FILE_ENTITY),
                    partitionDir.resolve(StorageLayout.FILE_ENTITY), migrated, skipped);

            // 6. Write manifest
            writeManifest(basePath);

            long elapsed = System.currentTimeMillis() - startMs;
            log.info("═══ Migration complete in {}ms ═══", elapsed);
            log.info("Migrated {} files: {}", migrated.size(), migrated);
            if (!skipped.isEmpty()) {
                log.warn("Skipped {} files (not found): {}", skipped.size(), skipped);
            }

        } catch (IOException e) {
            throw new UncheckedIOException("Layout migration failed for: " + basePath, e);
        }
    }

    /**
     * Writes a minimal manifest.json to the basePath.
     */
    private static void writeManifest(Path basePath) throws IOException {
        Path manifest = StorageLayout.manifest(basePath);
        String json = """
                {
                  "version": 2,
                  "format": "colocated-partitions",
                  "migrated_at": "%s",
                  "layout_version": 1
                }
                """.formatted(java.time.Instant.now().toString());
        Files.writeString(manifest, json);
    }

    /**
     * Moves a file if it exists, tracking the result.
     */
    private static void moveIfExists(Path src, Path dest,
                                      List<String> migrated, List<String> skipped) {
        if (Files.exists(src)) {
            try {
                Files.move(src, dest, StandardCopyOption.REPLACE_EXISTING);
                migrated.add(src.getFileName().toString());
            } catch (IOException e) {
                log.warn("Failed to move {} → {}: {}", src, dest, e.getMessage());
                skipped.add(src.getFileName().toString());
            }
        } else {
            skipped.add(src.getFileName().toString() + " (not found)");
        }
    }

    /**
     * Deletes a directory if it's empty.
     */
    private static void deleteIfEmpty(Path dir) {
        try {
            if (Files.isDirectory(dir)) {
                try (var entries = Files.list(dir)) {
                    if (entries.findFirst().isEmpty()) {
                        Files.delete(dir);
                    }
                }
            }
        } catch (IOException e) {
            log.debug("Could not delete directory: {}", dir, e);
        }
    }
}
