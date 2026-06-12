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
package com.spectrayan.spector.memory;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Centralized storage layout constants for the Spector Memory system.
 *
 * <h3>Design</h3>
 * <p>Every file name, directory name, and extension used by the memory
 * persistence layer is defined here. No file or directory name is hardcoded
 * anywhere else in the codebase. The user only needs to configure a single
 * {@code persistence-path} — this class resolves everything beneath it.</p>
 *
 * <h3>Directory Structure</h3>
 * <pre>
 * persistence-path/
 * ├── manifest.json
 * ├── global/
 * │   ├── working.mem
 * │   ├── coactivation.tracker
 * │   └── wal/
 * │       └── wal-000001.bin
 * ├── partitions/
 * │   ├── 000_1717430400/
 * │   │   ├── semantic.mem
 * │   │   ├── episodic.mem
 * │   │   ├── procedural.mem
 * │   │   ├── text.dat
 * │   │   ├── index.midx
 * │   │   ├── hebbian.graph
 * │   │   ├── temporal.chain
 * │   │   └── entity.graph
 * │   └── 001_1719849600/
 * │       └── ...
 * └── cross/
 *     ├── hebbian-cross.graph
 *     └── entity-cross.graph
 * </pre>
 *
 * <h3>With Namespaces</h3>
 * <pre>
 * persistence-path/
 * ├── spector.lock
 * ├── server.json
 * └── namespaces/
 *     └── agent-alpha/
 *         ├── namespace.json
 *         ├── global/
 *         ├── partitions/
 *         └── cross/
 * </pre>
 *
 * @see DefaultSpectorMemory.Builder#persistence(Path)
 */
public final class StorageLayout {

    private StorageLayout() {}

    // ═══════════════════════════════════════════════════════════════
    // Top-Level Directories
    // ═══════════════════════════════════════════════════════════════

    /** Directory for global (non-partitioned) data. */
    public static final String DIR_GLOBAL = "global";

    /** Directory containing colocated partition subdirectories. */
    public static final String DIR_PARTITIONS = "partitions";

    /** Directory for cross-partition graph edges. */
    public static final String DIR_CROSS = "cross";

    /** Directory for WAL segments (inside global/). */
    public static final String DIR_WAL = "wal";

    /** Directory for namespace directories (multi-tenant mode). */
    public static final String DIR_NAMESPACES = "namespaces";

    // ═══════════════════════════════════════════════════════════════
    // Top-Level Files
    // ═══════════════════════════════════════════════════════════════

    /** Global manifest with version, dimensions, partition config. */
    public static final String FILE_MANIFEST = "manifest.json";

    /** Process lock file (multi-tenant mode). */
    public static final String FILE_LOCK = "spector.lock";

    /** Server-level configuration (multi-tenant mode). */
    public static final String FILE_SERVER_CONFIG = "server.json";

    // ═══════════════════════════════════════════════════════════════
    // Global Files (inside DIR_GLOBAL)
    // ═══════════════════════════════════════════════════════════════

    /** Working memory tier store (volatile, TTL-based). */
    public static final String FILE_WORKING = "working.mem";

    /** Global co-activation frequency tracker. */
    public static final String FILE_COACTIVATION = "coactivation.tracker";

    /** Checkpoint metadata — WAL high-water mark for crash recovery. */
    public static final String FILE_CHECKPOINT_META = "checkpoint.meta";

    // ═══════════════════════════════════════════════════════════════
    // Partition Files (inside each partition directory)
    // ═══════════════════════════════════════════════════════════════

    /** Semantic tier — cognitive headers + quantized vectors. */
    public static final String FILE_SEMANTIC = "semantic.mem";

    /** Episodic tier — episodic headers + vectors. */
    public static final String FILE_EPISODIC = "episodic.mem";

    /** Procedural tier — procedural skills + patterns. */
    public static final String FILE_PROCEDURAL = "procedural.mem";

    /** Raw text content for all tiers in this partition. */
    public static final String FILE_TEXT = "text.dat";

    /** Partition-local memory index (id → location, source, tags). */
    public static final String FILE_INDEX = "index.midx";

    /** Intra-partition Hebbian co-activation edges. */
    public static final String FILE_HEBBIAN = "hebbian.graph";

    /** Intra-partition temporal sequence links. */
    public static final String FILE_TEMPORAL = "temporal.chain";

    /** Intra-partition entity knowledge graph. */
    public static final String FILE_ENTITY = "entity.graph";

    // ═══════════════════════════════════════════════════════════════
    // Cross-Partition Files (inside DIR_CROSS)
    // ═══════════════════════════════════════════════════════════════

    /** Cross-partition Hebbian edges (memories in different partitions). */
    public static final String FILE_HEBBIAN_CROSS = "hebbian-cross.graph";

    /** Cross-partition entity relations (entities spanning partitions). */
    public static final String FILE_ENTITY_CROSS = "entity-cross.graph";

    // ═══════════════════════════════════════════════════════════════
    // Namespace Files (inside each namespace directory)
    // ═══════════════════════════════════════════════════════════════

    /** Namespace metadata, permissions, and quotas. */
    public static final String FILE_NAMESPACE = "namespace.json";

    // ═══════════════════════════════════════════════════════════════
    // WAL File Pattern
    // ═══════════════════════════════════════════════════════════════

    /** WAL segment file prefix. */
    public static final String WAL_PREFIX = "wal-";

    /** WAL segment file extension. */
    public static final String WAL_SUFFIX = ".bin";

    /** WAL segment format string: {@code String.format(WAL_FORMAT, seqNo)}. */
    public static final String WAL_FORMAT = WAL_PREFIX + "%06d" + WAL_SUFFIX;

    // ═══════════════════════════════════════════════════════════════
    // Partition Directory Naming
    // ═══════════════════════════════════════════════════════════════

    /** Separator between sequence number and epoch in partition dir names. */
    public static final char PARTITION_SEPARATOR = '_';

    /** Format string for partition directory names: {@code 000_1717430400}. */
    public static final String PARTITION_DIR_FORMAT = "%03d" + PARTITION_SEPARATOR + "%d";

    /** Number of digits in the sequence-number prefix (for parsing). */
    public static final int PARTITION_SEQ_DIGITS = 3;

    /**
     * Compiled regex for partition directory names.
     * Group 1: sequence number (digits), Group 2: epoch seconds (digits).
     */
    public static final Pattern PARTITION_DIR_PATTERN =
            Pattern.compile("(\\d{" + PARTITION_SEQ_DIGITS + "})_" + "(\\d+)");

    // ═══════════════════════════════════════════════════════════════
    // Legacy Layout (for migration)
    // ═══════════════════════════════════════════════════════════════

    /** Legacy semantic partition directory name. */
    public static final String LEGACY_DIR_SEMANTIC = "semantic";

    /** Legacy episodic partition directory name. */
    public static final String LEGACY_DIR_EPISODIC = "episodic";

    /** Legacy semantic partition file prefix. */
    public static final String LEGACY_SEMANTIC_PREFIX = "semantic-";

    /** Legacy semantic partition file extension. */
    public static final String LEGACY_SEMANTIC_SUFFIX = ".mem";

    /** Legacy global memory index file. */
    public static final String LEGACY_FILE_INDEX = "memory-index.mem";

    /** Legacy single-file procedural store. */
    public static final String LEGACY_FILE_PROCEDURAL = "procedural.mem";

    /** Legacy single-file semantic store. */
    public static final String LEGACY_FILE_SEMANTIC = "semantic.mem";

    // ═══════════════════════════════════════════════════════════════
    // Binary Format Magic Numbers
    // ═══════════════════════════════════════════════════════════════

    /** Magic bytes for text.dat files: "TXTD" (0x54585444). */
    public static final int TEXT_DAT_MAGIC = 0x54585444;

    /** Current version of the text.dat format (V2: mmap-backed off-heap reads). */
    public static final int TEXT_DAT_VERSION = 2;

    /** Magic bytes for index.midx files: "MIDX" (0x4D494458). */
    public static final int INDEX_MIDX_MAGIC = 0x4D494458;

    // ═══════════════════════════════════════════════════════════════
    // Path Resolvers — single point of path construction
    // ═══════════════════════════════════════════════════════════════

    /** Resolves the global directory from the base persistence path. */
    public static Path globalDir(Path basePath) {
        return basePath.resolve(DIR_GLOBAL);
    }

    /** Resolves the partitions directory from the base persistence path. */
    public static Path partitionsDir(Path basePath) {
        return basePath.resolve(DIR_PARTITIONS);
    }

    /** Resolves the cross-partition directory from the base persistence path. */
    public static Path crossDir(Path basePath) {
        return basePath.resolve(DIR_CROSS);
    }

    /** Resolves the WAL directory from the base persistence path. */
    public static Path walDir(Path basePath) {
        return globalDir(basePath).resolve(DIR_WAL);
    }

    /** Resolves the namespaces directory from the base persistence path. */
    public static Path namespacesDir(Path basePath) {
        return basePath.resolve(DIR_NAMESPACES);
    }

    /** Resolves a specific namespace directory. */
    public static Path namespaceDir(Path basePath, String namespaceId) {
        return namespacesDir(basePath).resolve(namespaceId);
    }

    // ── Global file resolvers ──

    /** Resolves the manifest file path. */
    public static Path manifest(Path basePath) {
        return basePath.resolve(FILE_MANIFEST);
    }

    /** Resolves the working memory file path. */
    public static Path workingMem(Path basePath) {
        return globalDir(basePath).resolve(FILE_WORKING);
    }

    /** Resolves the co-activation tracker file path. */
    public static Path coactivationTracker(Path basePath) {
        return globalDir(basePath).resolve(FILE_COACTIVATION);
    }

    /** Resolves the checkpoint metadata file path. */
    public static Path checkpointMeta(Path basePath) {
        return globalDir(basePath).resolve(FILE_CHECKPOINT_META);
    }

    // ── Partition resolvers ──

    /**
     * Generates a partition directory name from sequence number and creation time.
     *
     * @param seqNo     zero-based partition sequence number
     * @param epochSecs creation time as Unix epoch seconds
     * @return directory name in the format {@code 000_1717430400}
     */
    public static String partitionDirName(int seqNo, long epochSecs) {
        return String.format(PARTITION_DIR_FORMAT, seqNo, epochSecs);
    }

    /**
     * Resolves a partition directory from the base persistence path.
     *
     * @param basePath  the persistence root
     * @param seqNo     zero-based partition sequence number
     * @param epochSecs creation time as Unix epoch seconds
     * @return path to the partition directory
     */
    public static Path partitionDir(Path basePath, int seqNo, long epochSecs) {
        return partitionsDir(basePath).resolve(partitionDirName(seqNo, epochSecs));
    }

    /**
     * Extracts the sequence number from a partition directory name.
     *
     * @param dirName directory name (e.g., {@code "003_1717603200"})
     * @return the sequence number (e.g., 3)
     * @throws NumberFormatException if the name doesn't match the expected format
     */
    public static int parsePartitionSeqNo(String dirName) {
        return Integer.parseInt(dirName.substring(0, PARTITION_SEQ_DIGITS));
    }

    /**
     * Extracts the creation epoch (seconds) from a partition directory name.
     *
     * @param dirName directory name (e.g., {@code "003_1717603200"})
     * @return the Unix epoch seconds (e.g., 1717603200)
     * @throws NumberFormatException if the name doesn't match the expected format
     */
    public static long parsePartitionEpoch(String dirName) {
        return Long.parseLong(dirName.substring(PARTITION_SEQ_DIGITS + 1));
    }

    /**
     * Checks if a directory name matches the partition naming convention.
     *
     * @param dirName directory name to check
     * @return true if it matches {@code NNN_EPOCH} format
     */
    public static boolean isPartitionDir(String dirName) {
        if (dirName == null || dirName.length() <= PARTITION_SEQ_DIGITS + 1) return false;
        if (dirName.charAt(PARTITION_SEQ_DIGITS) != PARTITION_SEPARATOR) return false;
        try {
            parsePartitionSeqNo(dirName);
            parsePartitionEpoch(dirName);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ── Partition file resolvers ──

    /** Resolves a file within a partition directory. */
    public static Path partitionFile(Path partitionDir, String fileName) {
        return partitionDir.resolve(fileName);
    }

    /** Resolves the semantic.mem file within a partition. */
    public static Path semanticMem(Path partitionDir) {
        return partitionDir.resolve(FILE_SEMANTIC);
    }

    /** Resolves the episodic.mem file within a partition. */
    public static Path episodicMem(Path partitionDir) {
        return partitionDir.resolve(FILE_EPISODIC);
    }

    /** Resolves the procedural.mem file within a partition. */
    public static Path proceduralMem(Path partitionDir) {
        return partitionDir.resolve(FILE_PROCEDURAL);
    }

    /** Resolves the text.dat file within a partition. */
    public static Path textDat(Path partitionDir) {
        return partitionDir.resolve(FILE_TEXT);
    }

    /** Resolves the index.midx file within a partition. */
    public static Path indexMidx(Path partitionDir) {
        return partitionDir.resolve(FILE_INDEX);
    }

    /** Resolves the hebbian.graph file within a partition. */
    public static Path hebbianGraph(Path partitionDir) {
        return partitionDir.resolve(FILE_HEBBIAN);
    }

    /** Resolves the temporal.chain file within a partition. */
    public static Path temporalChain(Path partitionDir) {
        return partitionDir.resolve(FILE_TEMPORAL);
    }

    /** Resolves the entity.graph file within a partition. */
    public static Path entityGraph(Path partitionDir) {
        return partitionDir.resolve(FILE_ENTITY);
    }

    // ── Cross-partition file resolvers ──

    /** Resolves the cross-partition Hebbian graph. */
    public static Path hebbianCrossGraph(Path basePath) {
        return crossDir(basePath).resolve(FILE_HEBBIAN_CROSS);
    }

    /** Resolves the cross-partition entity graph. */
    public static Path entityCrossGraph(Path basePath) {
        return crossDir(basePath).resolve(FILE_ENTITY_CROSS);
    }

    // ── WAL resolvers ──

    /** Generates a WAL segment file name. */
    public static String walFileName(int seqNo) {
        return String.format(WAL_FORMAT, seqNo);
    }

    /** Resolves a WAL segment file path. */
    public static Path walFile(Path basePath, int seqNo) {
        return walDir(basePath).resolve(walFileName(seqNo));
    }

    // ═══════════════════════════════════════════════════════════════
    // Legacy Path Resolvers (for migration)
    // ═══════════════════════════════════════════════════════════════

    /** Resolves legacy semantic partition directory. */
    public static Path legacySemanticDir(Path basePath) {
        return basePath.resolve(LEGACY_DIR_SEMANTIC);
    }

    /** Resolves legacy episodic partition directory. */
    public static Path legacyEpisodicDir(Path basePath) {
        return basePath.resolve(LEGACY_DIR_EPISODIC);
    }

    /** Resolves legacy global memory index file. */
    public static Path legacyIndex(Path basePath) {
        return basePath.resolve(LEGACY_FILE_INDEX);
    }

    /** Resolves legacy single-file procedural store. */
    public static Path legacyProcedural(Path basePath) {
        return basePath.resolve(LEGACY_FILE_PROCEDURAL);
    }

    /** Resolves legacy single-file semantic store (pre-partitioned). */
    public static Path legacySemantic(Path basePath) {
        return basePath.resolve(LEGACY_FILE_SEMANTIC);
    }
}
