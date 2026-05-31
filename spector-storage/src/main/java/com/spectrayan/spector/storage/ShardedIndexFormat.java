/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.spectrayan.spector.storage;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Binary format for the sharded HNSW index manifest.
 *
 * <p>Defines a self-describing manifest that catalogs a collection of
 * {@link IndexFileFormat}-compatible shard files. Each shard contains a
 * subset of nodes by index range, but all neighbor indices remain
 * <b>global</b> — the search layer resolves cross-shard references
 * transparently via {@code globalNodeIdx / nodesPerShard}.</p>
 *
 * <h3>File Layout</h3>
 * <pre>
 *   Manifest: index.spct.manifest
 *     [4B magic: "SPSI"]
 *     [4B version: 1]
 *     [4B shard_count]
 *     [4B dimensions]
 *     [4B total_node_count]
 *     [4B nodes_per_shard]
 *     [4B M]
 *     [4B maxLevel0Connections]
 *     [4B global_entry_point]
 *     [4B global_max_level]
 *     [4B similarity_function ordinal]
 *     [4B quantization_type ordinal]
 *     Per-shard entry (repeated shard_count times):
 *       [4B shard_node_count]
 *       [8B shard_file_size]
 *
 *   Shard files: index-000000.spct, index-000001.spct, ...
 *     Each uses the standard IndexFileFormat layout.
 *     Neighbor indices are GLOBAL (not shard-local).
 * </pre>
 *
 * @see IndexFileFormat
 */
public final class ShardedIndexFormat {

    /** Magic bytes: "SPSI" — Sharded SPector Index. */
    public static final int MAGIC = 0x53505349;

    /** Current manifest format version. */
    public static final int VERSION = 1;

    /** Fixed header size in the manifest (before per-shard entries). */
    public static final int MANIFEST_HEADER_SIZE = 48; // 12 × 4 bytes

    /** Size of each per-shard entry in the manifest. */
    public static final int SHARD_ENTRY_SIZE = 12; // 4 + 8 bytes

    /** Default manifest file name. */
    public static final String MANIFEST_NAME = "index.spct.manifest";

    /** Shard file name format: index-000000.spct */
    public static final String SHARD_NAME_FORMAT = "index-%06d.spct";

    /** Default shard directory name. */
    public static final String SHARD_DIR_NAME = "index_shards";

    private ShardedIndexFormat() {}

    /**
     * Immutable manifest describing the sharded index structure.
     *
     * @param magic              must be {@link #MAGIC}
     * @param version            format version
     * @param shardCount         total number of shard files
     * @param dimensions         vector dimensionality
     * @param totalNodeCount     total nodes across all shards
     * @param nodesPerShard      max nodes per shard (last shard may have fewer)
     * @param m                  HNSW M parameter
     * @param maxLevel0Connections HNSW max layer-0 connections
     * @param globalEntryPoint   HNSW entry point (global node index)
     * @param globalMaxLevel     HNSW maximum level
     * @param similarity         SimilarityFunction ordinal
     * @param quantization       QuantizationType ordinal
     * @param shardEntries       per-shard metadata
     */
    public record Manifest(
            int magic,
            int version,
            int shardCount,
            int dimensions,
            int totalNodeCount,
            int nodesPerShard,
            int m,
            int maxLevel0Connections,
            int globalEntryPoint,
            int globalMaxLevel,
            int similarity,
            int quantization,
            ShardEntry[] shardEntries
    ) {
        /** Validates manifest integrity. */
        public void validate() {
            if (magic != MAGIC) {
                throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "Invalid sharded index magic: expected 0x" + Integer.toHexString(MAGIC) + ", got 0x" + Integer.toHexString(magic));
            }
            if (version != VERSION) {
                throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "Unsupported sharded index version: " + version + " (expected " + VERSION + ")");
            }
            if (shardCount <= 0 || shardCount != shardEntries.length) {
                throw new SpectorValidationException(ErrorCode.LENGTH_MISMATCH, "header", shardCount, "entries", shardEntries.length);
            }
        }

        /**
         * Returns the shard index for a given global node index.
         *
         * @param globalNodeIdx the global node index
         * @return shard index (0-based)
         */
        public int shardFor(int globalNodeIdx) {
            return globalNodeIdx / nodesPerShard;
        }

        /**
         * Returns the local node index within a shard.
         *
         * @param globalNodeIdx the global node index
         * @return local node index within the shard
         */
        public int localIndex(int globalNodeIdx) {
            return globalNodeIdx % nodesPerShard;
        }

        /**
         * Returns the number of nodes in the given shard.
         *
         * @param shardIdx the shard index
         * @return node count for that shard
         */
        public int shardNodeCount(int shardIdx) {
            return shardEntries[shardIdx].nodeCount();
        }
    }

    /**
     * Per-shard metadata entry in the manifest.
     *
     * @param nodeCount number of nodes in this shard
     * @param fileSize  byte size of the shard file on disk
     */
    public record ShardEntry(int nodeCount, long fileSize) {}

    // ─────────────── File naming ───────────────

    /**
     * Returns the shard file name for the given shard index.
     *
     * @param shardIdx zero-based shard index
     * @return file name like "index-000000.spct"
     */
    public static String shardFileName(int shardIdx) {
        return String.format(SHARD_NAME_FORMAT, shardIdx);
    }

    /**
     * Resolves the shard directory within a data directory.
     *
     * @param dataDir the engine data directory
     * @return path to the shard directory
     */
    public static Path resolveShardDir(Path dataDir) {
        return dataDir.resolve(SHARD_DIR_NAME);
    }

    /**
     * Resolves the manifest file path within the shard directory.
     *
     * @param shardDir the shard directory
     * @return path to the manifest file
     */
    public static Path resolveManifest(Path shardDir) {
        return shardDir.resolve(MANIFEST_NAME);
    }

    // ─────────────── I/O ───────────────

    /**
     * Writes a manifest to disk.
     *
     * @param manifest the manifest to write
     * @param shardDir the shard directory (created if absent)
     * @throws IOException if writing fails
     */
    public static void writeManifest(Manifest manifest, Path shardDir) throws IOException {
        Files.createDirectories(shardDir);
        Path manifestPath = resolveManifest(shardDir);

        int totalSize = MANIFEST_HEADER_SIZE + manifest.shardCount() * SHARD_ENTRY_SIZE;
        ByteBuffer buf = ByteBuffer.allocate(totalSize);

        // Header
        buf.putInt(manifest.magic());
        buf.putInt(manifest.version());
        buf.putInt(manifest.shardCount());
        buf.putInt(manifest.dimensions());
        buf.putInt(manifest.totalNodeCount());
        buf.putInt(manifest.nodesPerShard());
        buf.putInt(manifest.m());
        buf.putInt(manifest.maxLevel0Connections());
        buf.putInt(manifest.globalEntryPoint());
        buf.putInt(manifest.globalMaxLevel());
        buf.putInt(manifest.similarity());
        buf.putInt(manifest.quantization());

        // Per-shard entries
        for (ShardEntry entry : manifest.shardEntries()) {
            buf.putInt(entry.nodeCount());
            buf.putLong(entry.fileSize());
        }

        buf.flip();
        try (var ch = FileChannel.open(manifestPath,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
            ch.write(buf);
            ch.force(true);
        }
    }

    /**
     * Reads a manifest from disk.
     *
     * @param shardDir the shard directory containing the manifest
     * @return the parsed manifest
     * @throws IOException if reading fails or the file is invalid
     */
    public static Manifest readManifest(Path shardDir) throws IOException {
        Path manifestPath = resolveManifest(shardDir);

        try (var raf = new RandomAccessFile(manifestPath.toFile(), "r");
             var ch = raf.getChannel()) {

            // Read header
            ByteBuffer headerBuf = ByteBuffer.allocate(MANIFEST_HEADER_SIZE);
            ch.read(headerBuf);
            headerBuf.flip();

            int magic = headerBuf.getInt();
            int version = headerBuf.getInt();
            int shardCount = headerBuf.getInt();
            int dimensions = headerBuf.getInt();
            int totalNodeCount = headerBuf.getInt();
            int nodesPerShard = headerBuf.getInt();
            int m = headerBuf.getInt();
            int maxLevel0 = headerBuf.getInt();
            int entryPoint = headerBuf.getInt();
            int maxLevel = headerBuf.getInt();
            int similarity = headerBuf.getInt();
            int quantization = headerBuf.getInt();

            // Read per-shard entries
            ByteBuffer entryBuf = ByteBuffer.allocate(shardCount * SHARD_ENTRY_SIZE);
            ch.read(entryBuf);
            entryBuf.flip();

            ShardEntry[] entries = new ShardEntry[shardCount];
            for (int i = 0; i < shardCount; i++) {
                int nodeCount = entryBuf.getInt();
                long fileSize = entryBuf.getLong();
                entries[i] = new ShardEntry(nodeCount, fileSize);
            }

            return new Manifest(magic, version, shardCount, dimensions,
                    totalNodeCount, nodesPerShard, m, maxLevel0,
                    entryPoint, maxLevel, similarity, quantization, entries);
        }
    }
}