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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ColocatedPartitionManager} — partition lifecycle management.
 */
class ColocatedPartitionManagerTest {

    @TempDir
    Path tempDir;

    private Path partitionsRoot;

    @BeforeEach
    void setUp() {
        partitionsRoot = tempDir.resolve(StorageLayout.DIR_PARTITIONS);
    }

    @Test
    void first_init_creates_initial_partition() {
        var mgr = new ColocatedPartitionManager(partitionsRoot, 1000, 500, 200);

        assertThat(mgr.count()).isEqualTo(1);
        assertThat(mgr.active()).isNotNull();
        assertThat(mgr.active().isFrozen()).isFalse();
        assertThat(mgr.active().seqNo()).isZero();
        assertThat(Files.isDirectory(mgr.active().directory())).isTrue();
    }

    @Test
    void roll_freezes_active_and_creates_new() {
        var mgr = new ColocatedPartitionManager(partitionsRoot, 1000, 500, 200);

        ColocatedPartition first = mgr.active();
        assertThat(first.isFrozen()).isFalse();

        ColocatedPartition second = mgr.roll();

        assertThat(first.isFrozen()).isTrue();
        assertThat(second.isFrozen()).isFalse();
        assertThat(second.seqNo()).isEqualTo(1);
        assertThat(mgr.count()).isEqualTo(2);
        assertThat(mgr.active()).isSameAs(second);
    }

    @Test
    void checkAndRoll_triggers_when_capacity_reached() {
        var mgr = new ColocatedPartitionManager(partitionsRoot, 3, 500, 200);

        // Fill semantic to capacity
        mgr.active().incrementSemantic();
        mgr.active().incrementSemantic();
        mgr.active().incrementSemantic(); // now at cap

        ColocatedPartition afterRoll = mgr.checkAndRoll();

        assertThat(afterRoll.seqNo()).isEqualTo(1);
        assertThat(mgr.count()).isEqualTo(2);
    }

    @Test
    void checkAndRoll_noOp_when_under_capacity() {
        var mgr = new ColocatedPartitionManager(partitionsRoot, 1000, 500, 200);

        ColocatedPartition before = mgr.active();
        ColocatedPartition after = mgr.checkAndRoll();

        assertThat(after).isSameAs(before);
        assertThat(mgr.count()).isEqualTo(1);
    }

    @Test
    void discovers_existing_partitions_on_restart() throws IOException {
        // Create partition directories manually
        Files.createDirectories(partitionsRoot.resolve("000_1717430400"));
        Files.createDirectories(partitionsRoot.resolve("001_1717516800"));
        Files.createDirectories(partitionsRoot.resolve("002_1717603200"));

        var mgr = new ColocatedPartitionManager(partitionsRoot, 1000, 500, 200);

        assertThat(mgr.count()).isEqualTo(3);
        assertThat(mgr.active().seqNo()).isEqualTo(2);

        // First two should be frozen
        assertThat(mgr.get(0).isFrozen()).isTrue();
        assertThat(mgr.get(1).isFrozen()).isTrue();
        assertThat(mgr.get(2).isFrozen()).isFalse();
    }

    @Test
    void frozen_list_excludes_active() {
        var mgr = new ColocatedPartitionManager(partitionsRoot, 1000, 500, 200);
        mgr.roll();
        mgr.roll();

        List<ColocatedPartition> frozen = mgr.frozen();

        assertThat(frozen).hasSize(2);
        assertThat(frozen.stream().allMatch(ColocatedPartition::isFrozen)).isTrue();
    }

    @Test
    void partition_directory_follows_naming_convention() {
        var mgr = new ColocatedPartitionManager(partitionsRoot, 1000, 500, 200);

        String dirName = mgr.active().directory().getFileName().toString();

        // Should match NNN_EPOCH pattern
        assertThat(StorageLayout.isPartitionDir(dirName)).isTrue();
    }

    @Test
    void multiple_rolls_produce_sequential_numbers() {
        var mgr = new ColocatedPartitionManager(partitionsRoot, 1000, 500, 200);

        for (int i = 0; i < 5; i++) {
            mgr.roll();
        }

        assertThat(mgr.count()).isEqualTo(6);
        for (int i = 0; i < 6; i++) {
            assertThat(mgr.get(i).seqNo()).isEqualTo(i);
        }
    }

    @Test
    void ignores_non_partition_directories() throws IOException {
        Files.createDirectories(partitionsRoot.resolve("000_1717430400"));
        Files.createDirectories(partitionsRoot.resolve("invalid_dir"));
        Files.createDirectories(partitionsRoot.resolve(".hidden"));
        Files.createFile(partitionsRoot.resolve("random.txt"));

        var mgr = new ColocatedPartitionManager(partitionsRoot, 1000, 500, 200);

        // Should only pick up the valid partition
        assertThat(mgr.count()).isEqualTo(1);
        assertThat(mgr.active().seqNo()).isZero();
    }

    @Test
    void all_returns_immutable_snapshot() {
        var mgr = new ColocatedPartitionManager(partitionsRoot, 1000, 500, 200);
        mgr.roll();

        List<ColocatedPartition> snapshot = mgr.all();

        assertThat(snapshot).hasSize(2);
        // Snapshot should not be affected by subsequent rolls
        mgr.roll();
        assertThat(snapshot).hasSize(2); // unchanged
        assertThat(mgr.all()).hasSize(3); // fresh snapshot
    }

    @Test
    void episodic_capacity_triggers_roll() {
        var mgr = new ColocatedPartitionManager(partitionsRoot, 1000, 2, 200);

        mgr.active().incrementEpisodic();
        assertThat(mgr.checkAndRoll().seqNo()).isZero(); // not yet

        mgr.active().incrementEpisodic();
        assertThat(mgr.checkAndRoll().seqNo()).isEqualTo(1); // rolled
    }

    @Test
    void procedural_capacity_triggers_roll() {
        var mgr = new ColocatedPartitionManager(partitionsRoot, 1000, 500, 1);

        mgr.active().incrementProcedural();
        assertThat(mgr.checkAndRoll().seqNo()).isEqualTo(1); // rolled
    }

    @Test
    void totalCount_tracks_across_tiers() {
        var mgr = new ColocatedPartitionManager(partitionsRoot, 1000, 500, 200);

        mgr.active().incrementSemantic();
        mgr.active().incrementEpisodic();
        mgr.active().incrementProcedural();

        assertThat(mgr.active().totalCount()).isEqualTo(3);
    }

    @Test
    void setCounts_bulk_update() {
        var mgr = new ColocatedPartitionManager(partitionsRoot, 1000, 500, 200);

        mgr.active().setCounts(100, 200, 50);

        assertThat(mgr.active().semanticCount()).isEqualTo(100);
        assertThat(mgr.active().episodicCount()).isEqualTo(200);
        assertThat(mgr.active().proceduralCount()).isEqualTo(50);
        assertThat(mgr.active().totalCount()).isEqualTo(350);
    }
}
