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

/**
 * Serializes an in-memory {@link HnswIndex} to the Spector disk format.
 *
 * <p>Writes a self-describing binary file that can be memory-mapped by
 * {@link DiskHnswIndex} for zero-deserialization startup.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   HnswIndex inMemory = buildIndex(...);
 *   DiskHnswWriter.write(inMemory, Path.of("index.spct"));
 *   // Later:
 *   DiskHnswIndex disk = DiskHnswIndex.open(Path.of("index.spct"));
 * }</pre>
 *
 * @see IndexFileFormat
 * @see DiskHnswIndex
 */
public final class DiskHnswWriter {

    private static final Logger log = LoggerFactory.getLogger(DiskHnswWriter.class);

    private DiskHnswWriter() {}

    /**
     * Writes an HNSW index to disk.
     *
     * @param index      the in-memory HNSW index
     * @param outputPath path to the output file (created or overwritten)
     * @throws IOException if writing fails
     */
    public static void write(AbstractHnswIndex index, Path outputPath) throws IOException {
        int nodeCount = index.size();
        int dimensions = index.dimensions();
        SimilarityFunction simFunc = index.similarityFunction();
        HnswParams params = index.params();

        // Compute layout sizes
        int maxPossibleLevels = 10; // supports up to 10 upper layers
        int graphBlockSize = IndexFileFormat.computeGraphBlockSize(
                params.maxLevel0Connections(), params.m(), maxPossibleLevels);

        long vectorDataOffset = IndexFileFormat.HEADER_SIZE; // header is 4KB
        long vectorRegionSize = (long) nodeCount * dimensions * Float.BYTES;
        long graphDataOffset = IndexFileFormat.alignToPage(vectorDataOffset + vectorRegionSize);
        long graphRegionSize = (long) nodeCount * graphBlockSize;
        long idTableOffset = IndexFileFormat.alignToPage(graphDataOffset + graphRegionSize);

        // Compute ID table size
        byte[][] idBytes = new byte[nodeCount][];
        long idRegionSize = 0;
        for (int i = 0; i < nodeCount; i++) {
            idBytes[i] = index.getId(i).getBytes(StandardCharsets.UTF_8);
            idRegionSize += 4 + idBytes[i].length; // 4-byte length prefix + bytes
        }
        long totalFileSize = IndexFileFormat.alignToPage(idTableOffset + idRegionSize);

        // Create header
        var header = new IndexFileFormat.Header(
                IndexFileFormat.MAGIC, IndexFileFormat.VERSION,
                dimensions, nodeCount,
                params.m(), params.maxLevel0Connections(),
                index.entryPoint(), index.maxLevel(),
                simFunc.ordinal(), QuantizationType.NONE.ordinal(),
                vectorDataOffset, graphDataOffset, idTableOffset,
                graphBlockSize, totalFileSize
        );

        // Ensure parent directory exists
        Path parent = outputPath.getParent();
        if (parent != null) Files.createDirectories(parent);

        // Write the file
        try (var raf = new RandomAccessFile(outputPath.toFile(), "rw");
             var channel = raf.getChannel()) {

            raf.setLength(totalFileSize);
            var arena = Arena.ofConfined();
            var segment = channel.map(FileChannel.MapMode.READ_WRITE, 0, totalFileSize, arena);

            // 1. Write header
            IndexFileFormat.writeHeader(segment, header);

            // 2. Write vectors
            for (int i = 0; i < nodeCount; i++) {
                float[] vector = index.getVector(i);
                long offset = vectorDataOffset + (long) i * dimensions * Float.BYTES;
                MemorySegment.copy(vector, 0, segment, IndexFileFormat.FLOAT_U, offset, dimensions);
            }

            // 3. Write graph blocks
            for (int i = 0; i < nodeCount; i++) {
                long blockOffset = graphDataOffset + (long) i * graphBlockSize;
                int level = index.getLevel(i);
                segment.set(IndexFileFormat.INT_U, blockOffset, level);
                long pos = blockOffset + 4;

                // Layer 0 neighbors
                int[] layer0 = index.getNeighborsAtLayer(i, 0);
                segment.set(IndexFileFormat.INT_U, pos, layer0.length);
                pos += 4;
                for (int j = 0; j < layer0.length; j++) {
                    segment.set(IndexFileFormat.INT_U, pos + (long) j * 4, layer0[j]);
                }
                pos += (long) params.maxLevel0Connections() * 4; // fixed size

                // Upper layer neighbors
                for (int l = 1; l <= maxPossibleLevels; l++) {
                    int[] layerN = l <= level ? index.getNeighborsAtLayer(i, l) : new int[0];
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
            for (int i = 0; i < nodeCount; i++) {
                segment.set(IndexFileFormat.INT_U, idPos, idBytes[i].length);
                idPos += 4;
                MemorySegment.copy(idBytes[i], 0, segment, ValueLayout.JAVA_BYTE, idPos, idBytes[i].length);
                idPos += idBytes[i].length;
            }

            // Force to disk
            segment.force();
            arena.close();
        }

        log.info("DiskHnswWriter: wrote {} nodes ({} dims) to {} ({} bytes)",
                nodeCount, dimensions, outputPath, totalFileSize);
    }
}
