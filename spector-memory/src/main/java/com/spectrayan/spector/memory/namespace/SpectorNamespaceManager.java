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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages namespace lifecycle — creation, discovery, deletion, and quota enforcement.
 *
 * <h3>Disk Layout</h3>
 * <pre>
 *   persistence-path/
 *   ├── spector.lock
 *   ├── server.json
 *   └── namespaces/
 *       ├── agent-alpha/
 *       │   ├── namespace.json
 *       │   ├── global/
 *       │   ├── partitions/
 *       │   └── cross/
 *       └── agent-beta/
 *           └── ...
 * </pre>
 *
 * <h3>Thread Safety</h3>
 * <p>Uses {@link ConcurrentHashMap} for the namespace registry.
 * Each namespace is isolated — operations on one namespace do not
 * affect others.</p>
 *
 * <h3>Scaling Model</h3>
 * <p>In single-node mode, all namespaces share the same persistence path.
 * In cloud mode (Phase 5), namespaces are distributed across nodes
 * via the {@code NamespaceRouter}.</p>
 *
 * @see NamespaceConfig
 * @see NamespaceQuotas
 * @see StorageLayout
 */
public final class SpectorNamespaceManager {

    private static final Logger log = LoggerFactory.getLogger(SpectorNamespaceManager.class);

    private final Path basePath;
    private final ConcurrentHashMap<String, NamespaceContext> namespaces;

    /**
     * Creates a namespace manager rooted at the given persistence path.
     *
     * <p>On construction, discovers existing namespaces from the
     * {@code namespaces/} directory.</p>
     *
     * @param basePath root persistence path
     */
    public SpectorNamespaceManager(Path basePath) {
        this.basePath = basePath;
        this.namespaces = new ConcurrentHashMap<>();

        // Discover existing namespaces
        Path namespacesDir = StorageLayout.namespacesDir(basePath);
        if (Files.isDirectory(namespacesDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(namespacesDir)) {
                for (Path entry : stream) {
                    if (Files.isDirectory(entry)) {
                        String nsId = entry.getFileName().toString();
                        Path nsJsonPath = entry.resolve(StorageLayout.FILE_NAMESPACE);

                        NamespaceConfig config;
                        if (Files.exists(nsJsonPath)) {
                            config = loadConfig(nsId, nsJsonPath);
                        } else {
                            config = NamespaceConfig.unlimited(nsId);
                        }

                        namespaces.put(nsId, new NamespaceContext(config, entry));
                        log.info("Discovered namespace: {}", nsId);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to scan namespaces directory", e);
            }
        }

        log.info("SpectorNamespaceManager initialized: {} namespaces at {}",
                namespaces.size(), basePath);
    }

    /**
     * Creates a new namespace with the given configuration.
     *
     * @param config namespace configuration
     * @return the namespace context
     * @throws IllegalArgumentException if the namespace ID is invalid
     * @throws IllegalStateException    if the namespace already exists
     */
    public NamespaceContext createNamespace(NamespaceConfig config) {
        if (!config.isValidId()) {
            throw new IllegalArgumentException("Invalid namespace ID: " + config.id());
        }
        if (namespaces.containsKey(config.id())) {
            throw new IllegalStateException("Namespace already exists: " + config.id());
        }

        Path nsDir = StorageLayout.namespaceDir(basePath, config.id());
        try {
            Files.createDirectories(nsDir);
            Files.createDirectories(nsDir.resolve(StorageLayout.DIR_GLOBAL));
            Files.createDirectories(nsDir.resolve(StorageLayout.DIR_PARTITIONS));
            Files.createDirectories(nsDir.resolve(StorageLayout.DIR_CROSS));

            // Write namespace.json
            writeConfig(config, nsDir.resolve(StorageLayout.FILE_NAMESPACE));

        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create namespace: " + config.id(), e);
        }

        NamespaceContext ctx = new NamespaceContext(config, nsDir);
        namespaces.put(config.id(), ctx);
        log.info("Created namespace: {} at {}", config.id(), nsDir);
        return ctx;
    }

    /**
     * Returns the context for a namespace, or null if not found.
     *
     * @param namespaceId the namespace ID
     * @return the namespace context, or null
     */
    public NamespaceContext getNamespace(String namespaceId) {
        return namespaces.get(namespaceId);
    }

    /**
     * Returns the context for a namespace, creating it with defaults if missing.
     *
     * @param namespaceId the namespace ID
     * @return the namespace context (never null)
     */
    public NamespaceContext getOrCreateNamespace(String namespaceId) {
        NamespaceContext existing = namespaces.get(namespaceId);
        if (existing != null) return existing;

        // Create outside of ConcurrentHashMap to avoid recursive update
        NamespaceConfig config = NamespaceConfig.unlimited(namespaceId);
        Path nsDir = StorageLayout.namespaceDir(basePath, config.id());
        try {
            Files.createDirectories(nsDir);
            Files.createDirectories(nsDir.resolve(StorageLayout.DIR_GLOBAL));
            Files.createDirectories(nsDir.resolve(StorageLayout.DIR_PARTITIONS));
            Files.createDirectories(nsDir.resolve(StorageLayout.DIR_CROSS));
            writeConfig(config, nsDir.resolve(StorageLayout.FILE_NAMESPACE));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Failed to create namespace: " + namespaceId, e);
        }

        NamespaceContext newCtx = new NamespaceContext(config, nsDir);
        NamespaceContext winner = namespaces.putIfAbsent(namespaceId, newCtx);
        if (winner != null) return winner; // another thread created it first

        log.info("Auto-created namespace: {}", namespaceId);
        return newCtx;
    }

    /**
     * Returns true if the namespace exists.
     */
    public boolean exists(String namespaceId) {
        return namespaces.containsKey(namespaceId);
    }

    /**
     * Returns the number of registered namespaces.
     */
    public int count() {
        return namespaces.size();
    }

    /**
     * Returns all namespace IDs.
     */
    public Collection<String> namespaceIds() {
        return namespaces.keySet();
    }

    /**
     * Returns all namespace contexts.
     */
    public Collection<NamespaceContext> allNamespaces() {
        return namespaces.values();
    }

    /**
     * Returns the base persistence path.
     */
    public Path basePath() {
        return basePath;
    }

    // ── Internal helpers ──

    private NamespaceConfig loadConfig(String nsId, Path nsJsonPath) {
        // Minimal JSON parsing — could use Jackson/Gson in future
        // For now, return unlimited config with the discovered ID
        log.debug("Loading namespace config: {}", nsJsonPath);
        return NamespaceConfig.unlimited(nsId);
    }

    private void writeConfig(NamespaceConfig config, Path path) throws IOException {
        String json = """
                {
                  "id": "%s",
                  "display_name": "%s",
                  "max_memories": %d,
                  "max_partitions": %d,
                  "max_storage_bytes": %d,
                  "read_only": %s,
                  "created_at": "%s"
                }
                """.formatted(
                config.id(),
                config.displayName(),
                config.maxMemories(),
                config.maxPartitions(),
                config.maxStorageBytes(),
                config.readOnly(),
                java.time.Instant.now().toString()
        );
        Files.writeString(path, json);
    }

    // ═══════════════════════════════════════════════════════════════
    // Namespace Context — groups config + quotas + path
    // ═══════════════════════════════════════════════════════════════

    /**
     * Context for a namespace: configuration, quotas, and path.
     */
    public static final class NamespaceContext {

        private final NamespaceConfig config;
        private final NamespaceQuotas quotas;
        private final Path directory;

        NamespaceContext(NamespaceConfig config, Path directory) {
            this.config = config;
            this.quotas = new NamespaceQuotas(config);
            this.directory = directory;
        }

        /** Namespace configuration. */
        public NamespaceConfig config() { return config; }

        /** Quota tracker. */
        public NamespaceQuotas quotas() { return quotas; }

        /** Root directory for this namespace's data. */
        public Path directory() { return directory; }

        /** Path to global/ within this namespace. */
        public Path globalDir() { return directory.resolve(StorageLayout.DIR_GLOBAL); }

        /** Path to partitions/ within this namespace. */
        public Path partitionsDir() { return directory.resolve(StorageLayout.DIR_PARTITIONS); }

        /** Path to cross/ within this namespace. */
        public Path crossDir() { return directory.resolve(StorageLayout.DIR_CROSS); }
    }
}
