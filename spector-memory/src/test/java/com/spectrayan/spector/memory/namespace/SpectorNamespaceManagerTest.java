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
package com.spectrayan.spector.memory.namespace;

import com.spectrayan.spector.memory.StorageLayout;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link SpectorNamespaceManager}.
 */
class SpectorNamespaceManagerTest {

    @TempDir
    Path tempDir;

    private SpectorNamespaceManager manager;

    @BeforeEach
    void setUp() {
        manager = new SpectorNamespaceManager(tempDir);
    }

    @Test
    void empty_init_has_zero_namespaces() {
        assertThat(manager.count()).isZero();
    }

    @Test
    void createNamespace_creates_directory_structure() {
        var config = NamespaceConfig.unlimited("agent-alpha");
        var ctx = manager.createNamespace(config);

        assertThat(Files.isDirectory(ctx.directory())).isTrue();
        assertThat(Files.isDirectory(ctx.globalDir())).isTrue();
        assertThat(Files.isDirectory(ctx.partitionsDir())).isTrue();
        assertThat(Files.isDirectory(ctx.crossDir())).isTrue();
        assertThat(Files.exists(ctx.directory().resolve(StorageLayout.FILE_NAMESPACE))).isTrue();
    }

    @Test
    void createNamespace_writes_config_json() throws IOException {
        var config = NamespaceConfig.withQuotas("test-ns", 1000, 10, 1024 * 1024);
        manager.createNamespace(config);

        Path configPath = StorageLayout.namespaceDir(tempDir, "test-ns")
                .resolve(StorageLayout.FILE_NAMESPACE);
        String json = Files.readString(configPath);

        assertThat(json).contains("\"id\": \"test-ns\"");
        assertThat(json).contains("\"max_memories\": 1000");
        assertThat(json).contains("\"max_partitions\": 10");
    }

    @Test
    void createNamespace_rejects_duplicate() {
        manager.createNamespace(NamespaceConfig.unlimited("agent-alpha"));

        assertThatThrownBy(() -> manager.createNamespace(NamespaceConfig.unlimited("agent-alpha")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("agent-alpha");
    }

    @Test
    void createNamespace_rejects_invalid_id() {
        var config = new NamespaceConfig("has spaces", "Test", -1, -1, -1, false);

        assertThatThrownBy(() -> manager.createNamespace(config))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getNamespace_returns_existing() {
        var config = NamespaceConfig.unlimited("agent-alpha");
        manager.createNamespace(config);

        var ctx = manager.getNamespace("agent-alpha");
        assertThat(ctx).isNotNull();
        assertThat(ctx.config().id()).isEqualTo("agent-alpha");
    }

    @Test
    void getNamespace_returns_null_for_missing() {
        assertThat(manager.getNamespace("nonexistent")).isNull();
    }

    @Test
    void exists_checks_presence() {
        manager.createNamespace(NamespaceConfig.unlimited("test"));

        assertThat(manager.exists("test")).isTrue();
        assertThat(manager.exists("missing")).isFalse();
    }

    @Test
    void discovers_existing_namespaces_on_restart() throws IOException {
        // Create namespace directories manually
        Path nsDir = StorageLayout.namespacesDir(tempDir);
        Files.createDirectories(nsDir.resolve("agent-alpha"));
        Files.createDirectories(nsDir.resolve("agent-beta"));

        // Re-create manager to trigger discovery
        var freshManager = new SpectorNamespaceManager(tempDir);

        assertThat(freshManager.count()).isEqualTo(2);
        assertThat(freshManager.exists("agent-alpha")).isTrue();
        assertThat(freshManager.exists("agent-beta")).isTrue();
    }

    @Test
    void namespaceIds_returns_all_ids() {
        manager.createNamespace(NamespaceConfig.unlimited("ns-1"));
        manager.createNamespace(NamespaceConfig.unlimited("ns-2"));
        manager.createNamespace(NamespaceConfig.unlimited("ns-3"));

        assertThat(manager.namespaceIds())
                .containsExactlyInAnyOrder("ns-1", "ns-2", "ns-3");
    }

    @Test
    void getOrCreateNamespace_creates_missing() {
        var ctx = manager.getOrCreateNamespace("auto-created");

        assertThat(ctx).isNotNull();
        assertThat(manager.exists("auto-created")).isTrue();
    }

    @Test
    void getOrCreateNamespace_returns_existing() {
        var original = manager.createNamespace(NamespaceConfig.unlimited("existing"));
        var retrieved = manager.getOrCreateNamespace("existing");

        assertThat(retrieved.config().id()).isEqualTo(original.config().id());
    }

    @Test
    void multiple_namespaces_isolated_directories() {
        manager.createNamespace(NamespaceConfig.unlimited("ns-a"));
        manager.createNamespace(NamespaceConfig.unlimited("ns-b"));

        var ctxA = manager.getNamespace("ns-a");
        var ctxB = manager.getNamespace("ns-b");

        assertThat(ctxA.directory()).isNotEqualTo(ctxB.directory());
        assertThat(ctxA.partitionsDir()).isNotEqualTo(ctxB.partitionsDir());
    }
}
