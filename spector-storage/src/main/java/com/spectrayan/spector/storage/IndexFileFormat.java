package com.spectrayan.spector.storage;

import com.spectrayan.spector.core.quantization.QuantizationType;
import com.spectrayan.spector.core.similarity.SimilarityFunction;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.commons.error.SpectorValidationException;

/**
 * Binary file format for persisting HNSW indexes to disk.
 *
 * <p>Defines a self-describing, page-aligned format with a fixed 4 KB header
 * followed by contiguous vector data and graph adjacency list regions.</p>
 *
 * <h3>File Layout</h3>
 * <pre>
 *   [HEADER: 4 KB]          — metadata, offsets, params
 *   [VECTOR DATA: variable] — contiguous float32 or int8 vectors
 *   [GRAPH DATA: variable]  — fixed-size blocks per node (neighbor lists)
 *   [ID TABLE: variable]    — UTF-8 document IDs
 * </pre>
 *
 * <h3>Alignment</h3>
 * <p>All regions start on 4 KB page boundaries for optimal mmap performance.</p>
 */
public final class IndexFileFormat {

    /** Magic bytes: "SPCT" in ASCII. */
    public static final int MAGIC = 0x53504354;

    /** Current format version. */
    public static final int VERSION = 1;

    /** Header size — aligned to 4 KB page. */
    public static final int HEADER_SIZE = 4096;

    /** Unaligned int layout — works on heap byte[] and arbitrary mmap offsets. */
    public static final ValueLayout.OfInt INT_U = ValueLayout.JAVA_INT_UNALIGNED;

    /** Unaligned long layout. */
    public static final ValueLayout.OfLong LONG_U = ValueLayout.JAVA_LONG_UNALIGNED;

    /** Unaligned float layout. */
    public static final ValueLayout.OfFloat FLOAT_U = ValueLayout.JAVA_FLOAT_UNALIGNED;

    private IndexFileFormat() {}

    /**
     * Immutable header describing the index structure.
     *
     * @param magic                magic bytes (must be {@link #MAGIC})
     * @param version              format version
     * @param dimensions           vector dimensionality
     * @param nodeCount            total number of nodes
     * @param m                    HNSW M parameter
     * @param maxLevel0Connections HNSW max layer-0 connections
     * @param entryPoint           HNSW entry point node index
     * @param maxLevel             HNSW maximum level
     * @param similarity           similarity function ordinal
     * @param quantization         quantization type ordinal
     * @param vectorDataOffset     byte offset to vector data region
     * @param graphDataOffset      byte offset to graph data region
     * @param idTableOffset        byte offset to ID table region
     * @param graphBlockSize       fixed byte size per graph node block
     * @param totalFileSize        total file size in bytes
     */
    public record Header(
            int magic,
            int version,
            int dimensions,
            int nodeCount,
            int m,
            int maxLevel0Connections,
            int entryPoint,
            int maxLevel,
            int similarity,       // SimilarityFunction.ordinal()
            int quantization,     // QuantizationType.ordinal()
            long vectorDataOffset,
            long graphDataOffset,
            long idTableOffset,
            int graphBlockSize,
            long totalFileSize
    ) {
        /** Validates the header. */
        public void validate() {
            if (magic != MAGIC) {
                throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "Invalid magic: expected 0x" + Integer.toHexString(MAGIC) + ", got 0x" + Integer.toHexString(magic));
            }
            if (version != VERSION) {
                throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "Unsupported version: " + version + " (expected " + VERSION + ")");
            }
        }

        /** Returns the SimilarityFunction for this header. */
        public SimilarityFunction similarityFunction() {
            return SimilarityFunction.values()[similarity];
        }

        /** Returns the QuantizationType for this header. */
        public QuantizationType quantizationType() {
            return QuantizationType.values()[quantization];
        }

        /** Returns bytes per vector (float32 or int8). */
        public long vectorByteSize() {
            return quantizationType() == QuantizationType.SCALAR_INT8
                    ? dimensions
                    : (long) dimensions * Float.BYTES;
        }
    }

    /**
     * Writes a header to a memory segment.
     *
     * @param segment the target segment (must be at least {@link #HEADER_SIZE} bytes)
     * @param header  the header to write
     */
    public static void writeHeader(MemorySegment segment, Header header) {
        long offset = 0;
        segment.set(INT_U, offset, header.magic()); offset += 4;
        segment.set(INT_U, offset, header.version()); offset += 4;
        segment.set(INT_U, offset, header.dimensions()); offset += 4;
        segment.set(INT_U, offset, header.nodeCount()); offset += 4;
        segment.set(INT_U, offset, header.m()); offset += 4;
        segment.set(INT_U, offset, header.maxLevel0Connections()); offset += 4;
        segment.set(INT_U, offset, header.entryPoint()); offset += 4;
        segment.set(INT_U, offset, header.maxLevel()); offset += 4;
        segment.set(INT_U, offset, header.similarity()); offset += 4;
        segment.set(INT_U, offset, header.quantization()); offset += 4;
        // Long fields at offset 40
        segment.set(LONG_U, offset, header.vectorDataOffset()); offset += 8;
        segment.set(LONG_U, offset, header.graphDataOffset()); offset += 8;
        segment.set(LONG_U, offset, header.idTableOffset()); offset += 8;
        segment.set(INT_U, offset, header.graphBlockSize()); offset += 4;
        offset += 4; // padding
        segment.set(LONG_U, offset, header.totalFileSize());
    }

    /**
     * Reads a header from a memory segment.
     *
     * @param segment the source segment
     * @return the parsed header
     */
    public static Header readHeader(MemorySegment segment) {
        long offset = 0;
        int magic = segment.get(INT_U, offset); offset += 4;
        int version = segment.get(INT_U, offset); offset += 4;
        int dimensions = segment.get(INT_U, offset); offset += 4;
        int nodeCount = segment.get(INT_U, offset); offset += 4;
        int m = segment.get(INT_U, offset); offset += 4;
        int maxLevel0 = segment.get(INT_U, offset); offset += 4;
        int entryPoint = segment.get(INT_U, offset); offset += 4;
        int maxLevel = segment.get(INT_U, offset); offset += 4;
        int similarity = segment.get(INT_U, offset); offset += 4;
        int quantization = segment.get(INT_U, offset); offset += 4;
        // Long fields at offset 40
        long vectorDataOffset = segment.get(LONG_U, offset); offset += 8;
        long graphDataOffset = segment.get(LONG_U, offset); offset += 8;
        long idTableOffset = segment.get(LONG_U, offset); offset += 8;
        int graphBlockSize = segment.get(INT_U, offset); offset += 4;
        offset += 4;
        long totalFileSize = segment.get(LONG_U, offset);

        return new Header(magic, version, dimensions, nodeCount, m, maxLevel0,
                entryPoint, maxLevel, similarity, quantization,
                vectorDataOffset, graphDataOffset, idTableOffset,
                graphBlockSize, totalFileSize);
    }

    /**
     * Computes the fixed graph block size per node.
     *
     * <p>Layout per block:</p>
     * <pre>
     *   [level: 4 bytes]
     *   [layer0_count: 4 bytes] [layer0_neighbors: maxLevel0 × 4 bytes]
     *   [upper_layer_count_1: 4 bytes] [upper_neighbors_1: M × 4 bytes]
     *   ... (repeated for max possible levels)
     * </pre>
     *
     * @param maxLevel0  max layer-0 connections
     * @param m          HNSW M parameter
     * @param maxLevels  maximum number of upper layers to support
     * @return block size in bytes
     */
    public static int computeGraphBlockSize(int maxLevel0, int m, int maxLevels) {
        int size = 4;                             // level
        size += 4 + maxLevel0 * 4;                // layer 0: count + neighbors
        size += maxLevels * (4 + m * 4);          // upper layers: count + neighbors each
        // Align to 8 bytes
        return (size + 7) & ~7;
    }

    /**
     * Aligns a byte offset to the next page boundary (4 KB).
     *
     * @param offset current offset
     * @return aligned offset
     */
    public static long alignToPage(long offset) {
        return (offset + HEADER_SIZE - 1) & ~(HEADER_SIZE - 1L);
    }
}