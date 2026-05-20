package com.spectrayan.spector.index;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.core.SimilarityFunction;

/**
 * Implementation of HNSW binary persistence format.
 *
 * <h3>Binary Format Layout</h3>
 * <pre>
 *   [Header: 64 bytes]
 *     - magic: 4 bytes ("SPHW" = 0x53504857)
 *     - version: 4 bytes (uint32, currently 1)
 *     - nodeCount: 4 bytes
 *     - dimensions: 4 bytes
 *     - maxLevel: 4 bytes
 *     - entryPoint: 4 bytes
 *     - M: 4 bytes
 *     - maxLevel0Connections: 4 bytes
 *     - vectorRegionOffset: 8 bytes
 *     - graphRegionOffset: 8 bytes
 *     - idTableOffset: 8 bytes
 *     - totalFileSize: 8 bytes
 *
 *   [Vector Region: page-aligned 4KB blocks]
 *     - Contiguous float32 vectors
 *
 *   [Graph Region: page-aligned 4KB blocks]
 *     - Per-node: [level_count: 1 byte][per-level neighbor lists]
 *     - Neighbor list: [count: 2 bytes][neighbor_ids: count × 4 bytes]
 *
 *   [ID Table Region]
 *     - Per-node: [length: 4 bytes][UTF-8 bytes]
 * </pre>
 *
 * <p>All regions are page-aligned to 4KB boundaries for optimal mmap performance.</p>
 */
public final class HnswPersistenceImpl implements HnswPersistence {

    private static final Logger log = LoggerFactory.getLogger(HnswPersistenceImpl.class);

    /** Magic bytes: "SPHW" in ASCII (big-endian). */
    public static final int MAGIC = 0x53504857;

    /** Current format version. */
    public static final int VERSION = 1;

    /** Header size: 64 bytes. */
    public static final int HEADER_SIZE = 64;

    /** Page alignment: 4KB. */
    public static final int PAGE_SIZE = 4096;

    /** Unaligned int layout for memory segment access. */
    private static final ValueLayout.OfInt INT_U = ValueLayout.JAVA_INT_UNALIGNED;

    /** Unaligned long layout for memory segment access. */
    private static final ValueLayout.OfLong LONG_U = ValueLayout.JAVA_LONG_UNALIGNED;

    /** Unaligned float layout for memory segment access. */
    private static final ValueLayout.OfFloat FLOAT_U = ValueLayout.JAVA_FLOAT_UNALIGNED;

    /** Unaligned short layout for memory segment access. */
    private static final ValueLayout.OfShort SHORT_U = ValueLayout.JAVA_SHORT_UNALIGNED;

    /** Maximum upper layers supported in the graph block format. */
    private static final int MAX_UPPER_LAYERS = 10;

    public HnswPersistenceImpl() {}

    @Override
    public void persist(Path file, HnswIndex index) throws IOException {
        int nodeCount = index.size();
        int dimensions = index.dimensions();
        HnswParams params = index.params();

        // Compute layout
        long vectorRegionOffset = alignToPage(HEADER_SIZE);
        long vectorRegionSize = (long) nodeCount * dimensions * Float.BYTES;
        long graphRegionOffset = alignToPage(vectorRegionOffset + vectorRegionSize);
        int graphBlockSize = computeGraphBlockSize(params.maxLevel0Connections(), params.m());
        long graphRegionSize = (long) nodeCount * graphBlockSize;
        long idTableOffset = alignToPage(graphRegionOffset + graphRegionSize);

        // Compute ID table size
        byte[][] idBytes = new byte[nodeCount][];
        long idRegionSize = 0;
        for (int i = 0; i < nodeCount; i++) {
            String id = index.getId(i);
            idBytes[i] = (id != null ? id : "").getBytes(StandardCharsets.UTF_8);
            idRegionSize += 4 + idBytes[i].length;
        }
        long totalFileSize = alignToPage(idTableOffset + idRegionSize);

        // Ensure parent directory exists
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (var raf = new RandomAccessFile(file.toFile(), "rw");
             var channel = raf.getChannel();
             var arena = Arena.ofConfined()) {

            raf.setLength(totalFileSize);
            var segment = channel.map(FileChannel.MapMode.READ_WRITE, 0, totalFileSize, arena);

            // 1. Write header (64 bytes)
            writeHeader(segment, nodeCount, dimensions, index.maxLevel(), index.entryPoint(),
                    params.m(), params.maxLevel0Connections(),
                    vectorRegionOffset, graphRegionOffset, idTableOffset, totalFileSize);

            // 2. Write vector region
            for (int i = 0; i < nodeCount; i++) {
                float[] vector = index.getVector(i);
                long offset = vectorRegionOffset + (long) i * dimensions * Float.BYTES;
                MemorySegment.copy(vector, 0, segment, FLOAT_U, offset, dimensions);
            }

            // 3. Write graph region
            for (int i = 0; i < nodeCount; i++) {
                long blockOffset = graphRegionOffset + (long) i * graphBlockSize;
                writeGraphBlock(segment, blockOffset, index, i, params);
            }

            // 4. Write ID table
            long idPos = idTableOffset;
            for (int i = 0; i < nodeCount; i++) {
                segment.set(INT_U, idPos, idBytes[i].length);
                idPos += 4;
                MemorySegment.copy(idBytes[i], 0, segment, ValueLayout.JAVA_BYTE, idPos, idBytes[i].length);
                idPos += idBytes[i].length;
            }

            segment.force();
        }

        log.info("HnswPersistence: persisted {} nodes ({} dims) to {} ({} bytes)",
                nodeCount, dimensions, file, totalFileSize);
    }

    @Override
    public HnswIndex load(Path file, SimilarityFunction simFn) throws IOException {
        long actualFileSize = Files.size(file);
        if (actualFileSize < HEADER_SIZE) {
            throw new IOException("File is too small to contain a valid header: " + actualFileSize
                    + " bytes (minimum " + HEADER_SIZE + " required)");
        }

        try (var raf = new RandomAccessFile(file.toFile(), "r");
             var channel = raf.getChannel();
             var arena = Arena.ofConfined()) {

            var segment = channel.map(FileChannel.MapMode.READ_ONLY, 0, actualFileSize, arena);

            // Read and validate header
            int magic = segment.get(INT_U, 0);
            if (magic != MAGIC) {
                throw new IOException("Invalid magic: expected 0x" + Integer.toHexString(MAGIC)
                        + " (SPHW), got 0x" + Integer.toHexString(magic));
            }

            int version = segment.get(INT_U, 4);
            if (version != VERSION) {
                throw new IOException("Unsupported version: expected " + VERSION
                        + ", got " + version);
            }

            int nodeCount = segment.get(INT_U, 8);
            int dimensions = segment.get(INT_U, 12);
            int maxLevel = segment.get(INT_U, 16);
            int entryPoint = segment.get(INT_U, 20);
            int m = segment.get(INT_U, 24);
            int maxLevel0Connections = segment.get(INT_U, 28);
            long vectorRegionOffset = segment.get(LONG_U, 32);
            long graphRegionOffset = segment.get(LONG_U, 40);
            long idTableOffset = segment.get(LONG_U, 48);
            long totalFileSize = segment.get(LONG_U, 56);

            // Validate file size / truncation detection
            if (actualFileSize != totalFileSize) {
                throw new IOException("File appears truncated or corrupted: expected "
                        + totalFileSize + " bytes, actual " + actualFileSize + " bytes");
            }

            // Validate region offsets don't exceed file bounds
            if (vectorRegionOffset > actualFileSize || graphRegionOffset > actualFileSize
                    || idTableOffset > actualFileSize) {
                throw new IOException("Region offsets exceed file bounds: vectorRegion="
                        + vectorRegionOffset + ", graphRegion=" + graphRegionOffset
                        + ", idTable=" + idTableOffset + ", fileSize=" + actualFileSize);
            }

            // Reconstruct HnswParams
            HnswParams params = new HnswParams(m, 200, 50, maxLevel0Connections,
                    1.0 / Math.log(m));

            // Create index with capacity = nodeCount (exact fit for loaded data)
            HnswIndex index = new HnswIndex(dimensions, nodeCount, simFn, params);

            // Read ID table
            String[] ids = readIdTable(segment, idTableOffset, nodeCount);

            // Read vectors and graph, add nodes directly
            int graphBlockSize = computeGraphBlockSize(maxLevel0Connections, m);

            for (int i = 0; i < nodeCount; i++) {
                // Read vector
                float[] vector = new float[dimensions];
                long vecOffset = vectorRegionOffset + (long) i * dimensions * Float.BYTES;
                MemorySegment.copy(segment, FLOAT_U, vecOffset, vector, 0, dimensions);

                // Read graph block to get level and neighbors
                long blockOffset = graphRegionOffset + (long) i * graphBlockSize;
                int level = segment.get(ValueLayout.JAVA_BYTE, blockOffset) & 0xFF;

                // We need to manually reconstruct the graph, so we add vectors first
                // then set neighbors. Use reflection-free approach via add() would rebuild
                // the graph - instead we restore directly via internal accessors.
                restoreNode(index, i, ids[i], vector, level, segment, blockOffset, params);
            }

            // Restore entry point and max level
            restoreGraphState(index, entryPoint, maxLevel);

            log.info("HnswPersistence: loaded {} nodes ({} dims) from {} ({} bytes)",
                    nodeCount, dimensions, file, actualFileSize);

            return index;
        }
    }

    @Override
    public void append(Path file, float[] vector, String externalId) throws IOException {
        long actualFileSize = Files.size(file);
        if (actualFileSize < HEADER_SIZE) {
            throw new IOException("File is too small to contain a valid header: " + actualFileSize
                    + " bytes (minimum " + HEADER_SIZE + " required)");
        }

        // 1. Load existing index into memory to compute graph connections
        HnswIndex index;
        HnswParams params;
        int oldNodeCount;
        int dimensions;
        long vectorRegionOffset;
        long graphRegionOffset;
        long oldIdTableOffset;

        try (var raf = new RandomAccessFile(file.toFile(), "r");
             var channel = raf.getChannel();
             var arena = Arena.ofConfined()) {

            var segment = channel.map(FileChannel.MapMode.READ_ONLY, 0, actualFileSize, arena);

            int magic = segment.get(INT_U, 0);
            if (magic != MAGIC) {
                throw new IOException("Invalid magic: expected 0x" + Integer.toHexString(MAGIC)
                        + " (SPHW), got 0x" + Integer.toHexString(magic));
            }
            int version = segment.get(INT_U, 4);
            if (version != VERSION) {
                throw new IOException("Unsupported version: expected " + VERSION + ", got " + version);
            }

            oldNodeCount = segment.get(INT_U, 8);
            dimensions = segment.get(INT_U, 12);
            int maxLevel = segment.get(INT_U, 16);
            int entryPoint = segment.get(INT_U, 20);
            int m = segment.get(INT_U, 24);
            int maxLevel0Connections = segment.get(INT_U, 28);
            vectorRegionOffset = segment.get(LONG_U, 32);
            graphRegionOffset = segment.get(LONG_U, 40);
            oldIdTableOffset = segment.get(LONG_U, 48);
            long totalFileSize = segment.get(LONG_U, 56);

            if (actualFileSize != totalFileSize) {
                throw new IOException("File appears truncated or corrupted: expected "
                        + totalFileSize + " bytes, actual " + actualFileSize + " bytes");
            }

            if (vector.length != dimensions) {
                throw new IOException("Vector dimension mismatch: expected " + dimensions
                        + ", got " + vector.length);
            }

            params = new HnswParams(m, 200, 50, maxLevel0Connections,
                    1.0 / Math.log(m));

            // Load into index with capacity for the new node
            index = new HnswIndex(dimensions, oldNodeCount + 1,
                    SimilarityFunction.DOT_PRODUCT, params);

            String[] ids = readIdTable(segment, oldIdTableOffset, oldNodeCount);
            int graphBlockSize = computeGraphBlockSize(maxLevel0Connections, m);

            for (int i = 0; i < oldNodeCount; i++) {
                float[] vec = new float[dimensions];
                long vecOffset = vectorRegionOffset + (long) i * dimensions * Float.BYTES;
                MemorySegment.copy(segment, FLOAT_U, vecOffset, vec, 0, dimensions);

                long blockOffset = graphRegionOffset + (long) i * graphBlockSize;
                int level = segment.get(ValueLayout.JAVA_BYTE, blockOffset) & 0xFF;
                restoreNode(index, i, ids[i], vec, level, segment, blockOffset, params);
            }
            restoreGraphState(index, entryPoint, maxLevel);
        }

        // 2. Add the new vector to the in-memory graph (builds bidirectional connections)
        index.add(externalId, oldNodeCount, vector);

        // 3. Write incremental changes to the file:
        //    - Append new vector to vector region (at the position for node oldNodeCount)
        //    - Append new graph block to graph region
        //    - Update existing graph blocks in-place (for nodes that gained a connection to the new node)
        //    - Write new ID table (must be rewritten since its offset moves)
        //    - Update header fields only: nodeCount, entryPoint, maxLevel, idTableOffset, totalFileSize
        int newNodeCount = index.size();
        int graphBlockSize = computeGraphBlockSize(params.maxLevel0Connections(), params.m());

        // New vector region: old vectors + 1 new vector (vectors are contiguous)
        long newVectorRegionSize = (long) newNodeCount * dimensions * Float.BYTES;
        long newGraphRegionOffset = alignToPage(vectorRegionOffset + newVectorRegionSize);

        // New graph region: all nodes (we need to update existing nodes' connections too)
        long newGraphRegionSize = (long) newNodeCount * graphBlockSize;
        long newIdTableOffset = alignToPage(newGraphRegionOffset + newGraphRegionSize);

        // Compute new ID table size
        byte[][] idBytes = new byte[newNodeCount][];
        long idRegionSize = 0;
        for (int i = 0; i < newNodeCount; i++) {
            String id = index.getId(i);
            idBytes[i] = (id != null ? id : "").getBytes(StandardCharsets.UTF_8);
            idRegionSize += 4 + idBytes[i].length;
        }
        long newTotalFileSize = alignToPage(newIdTableOffset + idRegionSize);

        try (var raf = new RandomAccessFile(file.toFile(), "rw");
             var channel = raf.getChannel();
             var arena = Arena.ofConfined()) {

            raf.setLength(newTotalFileSize);
            var segment = channel.map(FileChannel.MapMode.READ_WRITE, 0, newTotalFileSize, arena);

            // Update header fields only (nodeCount, entryPoint, maxLevel, idTableOffset, totalFileSize)
            // Keep magic, version, dimensions, M, maxLevel0Connections, vectorRegionOffset unchanged
            segment.set(INT_U, 8, newNodeCount);
            segment.set(INT_U, 16, index.maxLevel());
            segment.set(INT_U, 20, index.entryPoint());
            segment.set(LONG_U, 40, newGraphRegionOffset);
            segment.set(LONG_U, 48, newIdTableOffset);
            segment.set(LONG_U, 56, newTotalFileSize);

            // Append new vector (existing vectors in vector region are untouched)
            long newVecOffset = vectorRegionOffset + (long) oldNodeCount * dimensions * Float.BYTES;
            MemorySegment.copy(vector, 0, segment, FLOAT_U, newVecOffset, dimensions);

            // Write full graph region (existing nodes may have updated connections)
            for (int i = 0; i < newNodeCount; i++) {
                long blockOffset = newGraphRegionOffset + (long) i * graphBlockSize;
                writeGraphBlock(segment, blockOffset, index, i, params);
            }

            // Write new ID table
            long idPos = newIdTableOffset;
            for (int i = 0; i < newNodeCount; i++) {
                segment.set(INT_U, idPos, idBytes[i].length);
                idPos += 4;
                MemorySegment.copy(idBytes[i], 0, segment, ValueLayout.JAVA_BYTE, idPos, idBytes[i].length);
                idPos += idBytes[i].length;
            }

            segment.force();
        }

        log.info("HnswPersistence: appended node '{}' (now {} total nodes) to {}",
                externalId, newNodeCount, file);
    }

    // ─────────────── Header I/O ───────────────

    private void writeHeader(MemorySegment segment, int nodeCount, int dimensions,
                              int maxLevel, int entryPoint, int m, int maxLevel0Connections,
                              long vectorRegionOffset, long graphRegionOffset,
                              long idTableOffset, long totalFileSize) {
        segment.set(INT_U, 0, MAGIC);
        segment.set(INT_U, 4, VERSION);
        segment.set(INT_U, 8, nodeCount);
        segment.set(INT_U, 12, dimensions);
        segment.set(INT_U, 16, maxLevel);
        segment.set(INT_U, 20, entryPoint);
        segment.set(INT_U, 24, m);
        segment.set(INT_U, 28, maxLevel0Connections);
        segment.set(LONG_U, 32, vectorRegionOffset);
        segment.set(LONG_U, 40, graphRegionOffset);
        segment.set(LONG_U, 48, idTableOffset);
        segment.set(LONG_U, 56, totalFileSize);
    }

    // ─────────────── Graph Block I/O ───────────────

    /**
     * Writes a graph block for a single node.
     *
     * <p>Format per block:</p>
     * <pre>
     *   [level: 1 byte]
     *   [layer0_count: 2 bytes][layer0_neighbors: count × 4 bytes]
     *   [padding to maxLevel0Connections × 4 bytes]
     *   [upper_layer_1_count: 2 bytes][upper_neighbors: count × 4 bytes]
     *   [padding to M × 4 bytes]
     *   ... (repeated for MAX_UPPER_LAYERS)
     * </pre>
     */
    private void writeGraphBlock(MemorySegment segment, long blockOffset,
                                  HnswIndex index, int nodeIdx, HnswParams params) {
        long pos = blockOffset;

        int level = index.getLevel(nodeIdx);
        segment.set(ValueLayout.JAVA_BYTE, pos, (byte) level);
        pos += 1;

        // Layer 0 neighbors
        int[] layer0 = index.getNeighborsAtLayer(nodeIdx, 0);
        segment.set(SHORT_U, pos, (short) layer0.length);
        pos += 2;
        for (int j = 0; j < layer0.length; j++) {
            segment.set(INT_U, pos + (long) j * 4, layer0[j]);
        }
        pos += (long) params.maxLevel0Connections() * 4; // fixed size region

        // Upper layer neighbors
        for (int l = 1; l <= MAX_UPPER_LAYERS; l++) {
            int[] layerN = l <= level ? index.getNeighborsAtLayer(nodeIdx, l) : new int[0];
            segment.set(SHORT_U, pos, (short) layerN.length);
            pos += 2;
            for (int j = 0; j < layerN.length; j++) {
                segment.set(INT_U, pos + (long) j * 4, layerN[j]);
            }
            pos += (long) params.m() * 4; // fixed size region
        }
    }

    /**
     * Computes the fixed graph block size per node.
     */
    static int computeGraphBlockSize(int maxLevel0Connections, int m) {
        int size = 1;                                      // level byte
        size += 2 + maxLevel0Connections * 4;              // layer 0: count(2) + neighbors
        size += MAX_UPPER_LAYERS * (2 + m * 4);            // upper layers: count(2) + neighbors each
        // Align to 8 bytes for cache friendliness
        return (size + 7) & ~7;
    }

    // ─────────────── Restore Helpers ───────────────

    /**
     * Restores a single node into the index from persisted data.
     */
    private void restoreNode(HnswIndex index, int nodeIdx, String id,
                              float[] vector, int level,
                              MemorySegment segment, long blockOffset, HnswParams params) {
        // Access internal fields via the abstract base class
        index.ids[nodeIdx] = id;
        index.storeIndices[nodeIdx] = nodeIdx;
        index.nodeLevels[nodeIdx] = level;
        index.storeVector(nodeIdx, vector);

        // Read layer 0 neighbors
        long pos = blockOffset + 1; // skip level byte
        int layer0Count = Short.toUnsignedInt(segment.get(SHORT_U, pos));
        pos += 2;
        int[] layer0Neighbors = new int[layer0Count];
        for (int j = 0; j < layer0Count; j++) {
            layer0Neighbors[j] = segment.get(INT_U, pos + (long) j * 4);
        }
        index.neighbors[nodeIdx] = layer0Neighbors;
        pos += (long) params.maxLevel0Connections() * 4;

        // Read upper layer neighbors
        if (level > 0) {
            index.upperNeighbors[nodeIdx] = new int[level][];
            for (int l = 1; l <= MAX_UPPER_LAYERS; l++) {
                int layerCount = Short.toUnsignedInt(segment.get(SHORT_U, pos));
                pos += 2;
                if (l <= level) {
                    int[] layerNeighbors = new int[layerCount];
                    for (int j = 0; j < layerCount; j++) {
                        layerNeighbors[j] = segment.get(INT_U, pos + (long) j * 4);
                    }
                    index.upperNeighbors[nodeIdx][l - 1] = layerNeighbors;
                }
                pos += (long) params.m() * 4;
            }
        }

        // Increment node count
        index.nodeCount = nodeIdx + 1;
    }

    /**
     * Restores the entry point and max level of the graph.
     */
    private void restoreGraphState(HnswIndex index, int entryPoint, int maxLevel) {
        index.entryPoint = entryPoint;
        index.maxLevel = maxLevel;
    }

    // ─────────────── ID Table I/O ───────────────

    private String[] readIdTable(MemorySegment segment, long idTableOffset, int nodeCount) {
        String[] ids = new String[nodeCount];
        long pos = idTableOffset;

        for (int i = 0; i < nodeCount; i++) {
            int len = segment.get(INT_U, pos);
            pos += 4;
            byte[] bytes = new byte[len];
            MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, pos, bytes, 0, len);
            ids[i] = new String(bytes, StandardCharsets.UTF_8);
            pos += len;
        }
        return ids;
    }

    // ─────────────── Alignment ───────────────

    /**
     * Aligns a byte offset up to the next 4KB page boundary.
     */
    static long alignToPage(long offset) {
        return (offset + PAGE_SIZE - 1) & ~(PAGE_SIZE - 1L);
    }
}
