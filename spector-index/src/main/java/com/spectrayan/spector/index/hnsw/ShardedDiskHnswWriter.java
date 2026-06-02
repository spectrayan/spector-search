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
package com.spectrayan.spector.index;

import com.spectrayan.spector.config.HnswParams;
import com.spectrayan.spector.core.quantization.QuantizationType;
import com.spectrayan.spector.core.similarity.SimilarityFunction;
import com.spectrayan.spector.storage.IndexFileFormat;
import com.spectrayan.spector.storage.ShardedIndexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Serializes an in-memory {@link AbstractHnswIndex} into multiple shard files.
 *
 * <p>
 * Each shard file uses the standard {@link IndexFileFormat} layout and contains
 * a range of nodes. Neighbor indices remain <b>global</b> (not shard-local),
 * preserving the HNSW graph structure unchanged. A manifest file catalogs all
 * shards.
 * </p>
 *
 * <h3>Usage</h3>
 * 
 * <pre>{@code
 *   HnswIndex inMemory = buildIndex(...);
 *   ShardedDiskHnswWriter.write(inMemory, Path.of("index_shards"), 50_000);
 *   // Creates:
 *   //   index_shards/index.spct.manifest
 *   //   index_shards/index-000000.spct  (nodes 0–49999)
 *   //   index_shards/index-000001.spct  (nodes 50000–99999)
 *   //   ...
 * }</pre>
 *
 * @see ShardedIndexFormat
 * @see ShardedDiskHnswIndex
 * @see DiskHnswWriter
 */
public final class ShardedDiskHnswWriter {

    private static final Logger log = LoggerFactory.getLogger(ShardedDiskHnswWriter.class);

    /** Maximum upper layers supported per node in the graph block layout. */
    private static final int MAX_POSSIBLE_LEVELS = 10;

    private ShardedDiskHnswWriter() {
    }

    /**
     * Writes an HNSW index as multiple sharded files plus a manifest.
     *
     * @param index         the in-memory HNSW index
     * @param shardDir      directory for shard files and manifest (created if
     *                      absent)
     * @param nodesPerShard maximum nodes per shard (last shard may have fewer)
     * @throws IOException                if writing fails
     * @throws SpectorValidationException if nodesPerShard <= 0
     */
    public static void write(AbstractHnswIndex index, Path shardDir, int nodesPerShard)
            throws IOException {

        if (nodesPerShard <= 0) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "nodesPerShard", 1, Integer.MAX_VALUE,
                    nodesPerShard);
        }

        int totalNodes = index.size();
        if (totalNodes == 0) {
            log.info("ShardedDiskHnswWriter: nothing to write (0 nodes)");
            return;
        }

        int dimensions = index.dimensions();
        SimilarityFunction simFunc = index.similarityFunction();
        HnswParams params = index.params();

        int shardCount = (totalNodes + nodesPerShard - 1) / nodesPerShard;
        int graphBlockSize = IndexFileFormat.computeGraphBlockSize(
                params.maxLevel0Connections(), params.m(), MAX_POSSIBLE_LEVELS);

        Files.createDirectories(shardDir);

        ShardedIndexFormat.ShardEntry[] shardEntries = new ShardedIndexFormat.ShardEntry[shardCount];

        log.info("ShardedDiskHnswWriter: writing {} nodes across {} shards ({}/ shard) to {}",
                totalNodes, shardCount, nodesPerShard, shardDir);

        // Write each shard file
        for (int s = 0; s < shardCount; s++) {
            int startNode = s * nodesPerShard;
            int endNode = Math.min(startNode + nodesPerShard, totalNodes);
            int shardNodeCount = endNode - startNode;

            Path shardPath = shardDir.resolve(ShardedIndexFormat.shardFileName(s));
            long shardFileSize = writeShard(index, shardPath, startNode, endNode,
                    dimensions, params, graphBlockSize, simFunc);

            shardEntries[s] = new ShardedIndexFormat.ShardEntry(shardNodeCount, shardFileSize);

            log.debug("  Shard {}: nodes [{}, {}), {} bytes → {}",
                    s, startNode, endNode, shardFileSize, shardPath);
        }

        // Write manifest
        var manifest = new ShardedIndexFormat.Manifest(
                ShardedIndexFormat.MAGIC, ShardedIndexFormat.VERSION,
                shardCount, dimensions, totalNodes, nodesPerShard,
                params.m(), params.maxLevel0Connections(),
                index.entryPoint(), index.maxLevel(),
                simFunc.ordinal(), QuantizationType.NONE.ordinal(),
                shardEntries);
        ShardedIndexFormat.writeManifest(manifest, shardDir);

        long totalBytes = 0;
        for (var e : shardEntries)
            totalBytes += e.fileSize();
        log.info("ShardedDiskHnswWriter: done — {} shards, {} total bytes, manifest at {}",
                shardCount, totalBytes, shardDir.resolve(ShardedIndexFormat.MANIFEST_NAME));
    }

    // ─────────────── Single shard write ───────────────

    /**
     * Writes a single shard file containing nodes [startNode, endNode).
     * Returns the total file size in bytes.
     *
     * <p>
     * The file uses the standard {@link IndexFileFormat} layout. The header's
     * nodeCount is the shard's local count, but all neighbor indices in the graph
     * region are <b>global</b>.
     * </p>
     */
    private static long writeShard(AbstractHnswIndex index, Path shardPath,
            int startNode, int endNode,
            int dimensions, HnswParams params,
            int graphBlockSize, SimilarityFunction simFunc)
            throws IOException {

        int shardNodeCount = endNode - startNode;

        // Compute layout
        long vectorDataOffset = IndexFileFormat.HEADER_SIZE;
        long vectorRegionSize = (long) shardNodeCount * dimensions * Float.BYTES;
        long graphDataOffset = IndexFileFormat.alignToPage(vectorDataOffset + vectorRegionSize);
        long graphRegionSize = (long) shardNodeCount * graphBlockSize;
        long idTableOffset = IndexFileFormat.alignToPage(graphDataOffset + graphRegionSize);

        // Compute ID table size
        byte[][] idBytes = new byte[shardNodeCount][];
        long idRegionSize = 0;
        for (int i = 0; i < shardNodeCount; i++) {
            idBytes[i] = index.getId(startNode + i).getBytes(StandardCharsets.UTF_8);
            idRegionSize += 4 + idBytes[i].length;
        }
        long totalFileSize = IndexFileFormat.alignToPage(idTableOffset + idRegionSize);

        // Create header (nodeCount = shard-local, entryPoint/maxLevel are shard-local
        // too for format compat)
        // Note: entryPoint and maxLevel in the shard header are set to 0 since the
        // global values
        // are stored in the manifest. The shard is not independently searchable.
        var header = new IndexFileFormat.Header(
                IndexFileFormat.MAGIC, IndexFileFormat.VERSION,
                dimensions, shardNodeCount,
                params.m(), params.maxLevel0Connections(),
                0, 0, // shard-local entryPoint/maxLevel unused
                simFunc.ordinal(), QuantizationType.NONE.ordinal(),
                vectorDataOffset, graphDataOffset, idTableOffset,
                graphBlockSize, totalFileSize);

        Path parent = shardPath.getParent();
        if (parent != null)
            Files.createDirectories(parent);

        try (var raf = new RandomAccessFile(shardPath.toFile(), "rw");
                var channel = raf.getChannel()) {

            raf.setLength(totalFileSize);
            var arena = Arena.ofConfined();
            var segment = channel.map(FileChannel.MapMode.READ_WRITE, 0, totalFileSize, arena);

            // 1. Write header
            IndexFileFormat.writeHeader(segment, header);

            // 2. Write vectors (sequential within shard)
            for (int i = 0; i < shardNodeCount; i++) {
                float[] vector = index.getVector(startNode + i);
                long offset = vectorDataOffset + (long) i * dimensions * Float.BYTES;
                MemorySegment.copy(vector, 0, segment, IndexFileFormat.FLOAT_U, offset, dimensions);
            }

            // 3. Write graph blocks — neighbor indices remain GLOBAL
            for (int i = 0; i < shardNodeCount; i++) {
                int globalIdx = startNode + i;
                long blockOffset = graphDataOffset + (long) i * graphBlockSize;
                int level = index.getLevel(globalIdx);
                segment.set(IndexFileFormat.INT_U, blockOffset, level);
                long pos = blockOffset + 4;

                // Layer 0 neighbors (global indices)
                int[] layer0 = index.getNeighborsAtLayer(globalIdx, 0);
                segment.set(IndexFileFormat.INT_U, pos, layer0.length);
                pos += 4;
                for (int j = 0; j < layer0.length; j++) {
                    segment.set(IndexFileFormat.INT_U, pos + (long) j * 4, layer0[j]);
                }
                pos += (long) params.maxLevel0Connections() * 4;

                // Upper layer neighbors (global indices)
                for (int l = 1; l <= MAX_POSSIBLE_LEVELS; l++) {
                    int[] layerN = l <= level
                            ? index.getNeighborsAtLayer(globalIdx, l)
                            : new int[0];
                    segment.set(IndexFileFormat.INT_U, pos, layerN.length);
                    pos += 4;
                    for (int j = 0; j < layerN.length; j++) {
                        segment.set(IndexFileFormat.INT_U, pos + (long) j * 4, layerN[j]);
                    }
                    pos += (long) params.m() * 4;
                }
            }

            // 4. Write ID table
            long idPos = idTableOffset;
            for (int i = 0; i < shardNodeCount; i++) {
                segment.set(IndexFileFormat.INT_U, idPos, idBytes[i].length);
                idPos += 4;
                MemorySegment.copy(idBytes[i], 0, segment, ValueLayout.JAVA_BYTE, idPos, idBytes[i].length);
                idPos += idBytes[i].length;
            }

            segment.force();
            arena.close();
        }

        return totalFileSize;
    }
}
