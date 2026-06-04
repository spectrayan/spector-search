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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link PartitionLayoutMigrator} — legacy to colocated migration.
 */
class PartitionLayoutMigratorTest {

    @TempDir
    Path tempDir;

    private Path basePath;

    @BeforeEach
    void setUp() {
        basePath = tempDir.resolve("spector-data");
    }

    @Test
    void needsMigration_returns_false_for_empty_dir() {
        assertThat(PartitionLayoutMigrator.needsMigration(basePath)).isFalse();
    }

    @Test
    void needsMigration_returns_false_for_null() {
        assertThat(PartitionLayoutMigrator.needsMigration(null)).isFalse();
    }

    @Test
    void needsMigration_returns_true_for_legacy_index() throws IOException {
        Files.createDirectories(basePath);
        Files.writeString(basePath.resolve(StorageLayout.LEGACY_FILE_INDEX), "test");

        assertThat(PartitionLayoutMigrator.needsMigration(basePath)).isTrue();
    }

    @Test
    void needsMigration_returns_true_for_legacy_semantic() throws IOException {
        Files.createDirectories(basePath);
        Files.writeString(basePath.resolve(StorageLayout.LEGACY_FILE_SEMANTIC), "test");

        assertThat(PartitionLayoutMigrator.needsMigration(basePath)).isTrue();
    }

    @Test
    void needsMigration_returns_false_when_already_migrated() throws IOException {
        Files.createDirectories(StorageLayout.globalDir(basePath));
        Files.writeString(basePath.resolve(StorageLayout.LEGACY_FILE_INDEX), "test");

        assertThat(PartitionLayoutMigrator.needsMigration(basePath)).isFalse();
    }

    @Test
    void migrate_creates_directory_structure() throws IOException {
        createLegacyLayout();

        PartitionLayoutMigrator.migrate(basePath);

        assertThat(Files.isDirectory(StorageLayout.globalDir(basePath))).isTrue();
        assertThat(Files.isDirectory(StorageLayout.partitionsDir(basePath))).isTrue();
        assertThat(Files.isDirectory(StorageLayout.crossDir(basePath))).isTrue();
    }

    @Test
    void migrate_moves_global_files() throws IOException {
        createLegacyLayout();

        PartitionLayoutMigrator.migrate(basePath);

        assertThat(Files.exists(
                StorageLayout.globalDir(basePath).resolve(StorageLayout.FILE_WORKING))).isTrue();
        assertThat(Files.exists(
                StorageLayout.globalDir(basePath).resolve(StorageLayout.FILE_COACTIVATION))).isTrue();

        // Legacy locations should be gone
        assertThat(Files.exists(basePath.resolve(StorageLayout.FILE_WORKING))).isFalse();
        assertThat(Files.exists(basePath.resolve(StorageLayout.FILE_COACTIVATION))).isFalse();
    }

    @Test
    void migrate_moves_tier_files_to_partition() throws IOException {
        createLegacyLayout();

        PartitionLayoutMigrator.migrate(basePath);

        // Find the partition directory
        Path partitionsDir = StorageLayout.partitionsDir(basePath);
        Path[] partitions = Files.list(partitionsDir).toArray(Path[]::new);
        assertThat(partitions).hasSizeGreaterThanOrEqualTo(1);

        Path partition = partitions[0];
        assertThat(Files.exists(partition.resolve(StorageLayout.FILE_SEMANTIC))).isTrue();
        assertThat(Files.exists(partition.resolve(StorageLayout.FILE_PROCEDURAL))).isTrue();
        assertThat(Files.exists(partition.resolve(StorageLayout.FILE_INDEX))).isTrue();

        // Legacy locations should be gone
        assertThat(Files.exists(basePath.resolve(StorageLayout.LEGACY_FILE_SEMANTIC))).isFalse();
        assertThat(Files.exists(basePath.resolve(StorageLayout.LEGACY_FILE_PROCEDURAL))).isFalse();
        assertThat(Files.exists(basePath.resolve(StorageLayout.LEGACY_FILE_INDEX))).isFalse();
    }

    @Test
    void migrate_moves_graph_files_to_partition() throws IOException {
        createLegacyLayout();

        PartitionLayoutMigrator.migrate(basePath);

        Path partition = findFirstPartition();
        assertThat(Files.exists(partition.resolve(StorageLayout.FILE_HEBBIAN))).isTrue();
        assertThat(Files.exists(partition.resolve(StorageLayout.FILE_TEMPORAL))).isTrue();
        assertThat(Files.exists(partition.resolve(StorageLayout.FILE_ENTITY))).isTrue();
    }

    @Test
    void migrate_writes_manifest() throws IOException {
        createLegacyLayout();

        PartitionLayoutMigrator.migrate(basePath);

        Path manifest = StorageLayout.manifest(basePath);
        assertThat(Files.exists(manifest)).isTrue();
        String content = Files.readString(manifest);
        assertThat(content).contains("\"version\": 2");
        assertThat(content).contains("\"format\": \"colocated-partitions\"");
    }

    @Test
    void migrate_moves_wal_directory() throws IOException {
        createLegacyLayout();

        PartitionLayoutMigrator.migrate(basePath);

        Path newWalDir = StorageLayout.walDir(basePath);
        assertThat(Files.isDirectory(newWalDir)).isTrue();
        assertThat(Files.exists(newWalDir.resolve("wal-000001.bin"))).isTrue();

        // Legacy WAL dir should be removed
        assertThat(Files.exists(basePath.resolve(StorageLayout.DIR_WAL))).isFalse();
    }

    @Test
    void migrate_is_idempotent() throws IOException {
        createLegacyLayout();

        // First migration
        PartitionLayoutMigrator.migrate(basePath);

        // Should not need migration anymore
        assertThat(PartitionLayoutMigrator.needsMigration(basePath)).isFalse();

        // Second call should be a no-op
        PartitionLayoutMigrator.migrate(basePath);

        // Verify structure is still intact
        assertThat(Files.isDirectory(StorageLayout.globalDir(basePath))).isTrue();
    }

    @Test
    void migrate_handles_episodic_directory() throws IOException {
        createLegacyLayout();

        // Add episodic directory with partition files
        Path episodicDir = basePath.resolve(StorageLayout.LEGACY_DIR_EPISODIC);
        Files.createDirectories(episodicDir);
        Files.writeString(episodicDir.resolve("partition-0.mem"), "episodic-data-0");
        Files.writeString(episodicDir.resolve("partition-1.mem"), "episodic-data-1");

        PartitionLayoutMigrator.migrate(basePath);

        Path partition = findFirstPartition();
        assertThat(Files.exists(partition.resolve(StorageLayout.FILE_EPISODIC))).isTrue();
    }

    @Test
    void migrate_preserves_file_contents() throws IOException {
        Files.createDirectories(basePath);
        String semanticContent = "semantic-tier-data-12345";
        Files.writeString(basePath.resolve(StorageLayout.LEGACY_FILE_SEMANTIC), semanticContent);
        Files.writeString(basePath.resolve(StorageLayout.LEGACY_FILE_INDEX), "index-data");

        PartitionLayoutMigrator.migrate(basePath);

        Path partition = findFirstPartition();
        assertThat(Files.readString(partition.resolve(StorageLayout.FILE_SEMANTIC)))
                .isEqualTo(semanticContent);
    }

    @Test
    void migrate_handles_text_dat() throws IOException {
        createLegacyLayout();

        PartitionLayoutMigrator.migrate(basePath);

        Path partition = findFirstPartition();
        assertThat(Files.exists(partition.resolve(StorageLayout.FILE_TEXT))).isTrue();
    }

    // ── Helpers ──

    private void createLegacyLayout() throws IOException {
        Files.createDirectories(basePath);
        Files.writeString(basePath.resolve(StorageLayout.LEGACY_FILE_INDEX), "index-data");
        Files.writeString(basePath.resolve(StorageLayout.LEGACY_FILE_SEMANTIC), "semantic-data");
        Files.writeString(basePath.resolve(StorageLayout.LEGACY_FILE_PROCEDURAL), "procedural-data");
        Files.writeString(basePath.resolve(StorageLayout.FILE_WORKING), "working-data");
        Files.writeString(basePath.resolve(StorageLayout.FILE_COACTIVATION), "tracker-data");
        Files.writeString(basePath.resolve(StorageLayout.FILE_TEXT), "text-data");
        Files.writeString(basePath.resolve(StorageLayout.FILE_HEBBIAN), "hebbian-data");
        Files.writeString(basePath.resolve(StorageLayout.FILE_TEMPORAL), "temporal-data");
        Files.writeString(basePath.resolve(StorageLayout.FILE_ENTITY), "entity-data");

        // WAL directory with a segment
        Path walDir = basePath.resolve(StorageLayout.DIR_WAL);
        Files.createDirectories(walDir);
        Files.writeString(walDir.resolve("wal-000001.bin"), "wal-data");
    }

    private Path findFirstPartition() throws IOException {
        Path partitionsDir = StorageLayout.partitionsDir(basePath);
        try (var stream = Files.list(partitionsDir)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(p -> StorageLayout.isPartitionDir(p.getFileName().toString()))
                    .sorted()
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No partition directory found"));
        }
    }
}
