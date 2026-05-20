package com.spectrayan.spector.index;

import com.spectrayan.spector.core.SimilarityFunction;
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
import java.nio.file.Path;
import java.util.BitSet;

/**
 * Read-only HNSW index backed by a memory-mapped file.
 *
 * <p>Opens a file written by {@link DiskHnswWriter} and provides ANN search
 * via zero-copy memory-mapped access. The OS page cache transparently handles
 * hot/cold data, enabling datasets larger than available RAM.</p>
 *
 * <h3>Startup Time</h3>
 * <p>Startup is near-instant (a single mmap syscall) — no deserialization needed.
 * Only the ID table is loaded into heap memory.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>Concurrent searches are safe (shared arena, read-only segment).</p>
 *
 * @see DiskHnswWriter
 * @see IndexFileFormat
 */
public class DiskHnswIndex implements VectorIndex {

    private static final Logger log = LoggerFactory.getLogger(DiskHnswIndex.class);

    private final Path filePath;
    private final IndexFileFormat.Header header;
    private final Arena arena;
    private final MemorySegment segment;
    private final RandomAccessFile raf;
    private final FileChannel channel;
    private final String[] ids;
    private final SimilarityFunction similarityFunction;
    private volatile boolean closed;

    private DiskHnswIndex(Path filePath, IndexFileFormat.Header header,
                           Arena arena, MemorySegment segment,
                           RandomAccessFile raf, FileChannel channel,
                           String[] ids) {
        this.filePath = filePath;
        this.header = header;
        this.arena = arena;
        this.segment = segment;
        this.raf = raf;
        this.channel = channel;
        this.ids = ids;
        this.similarityFunction = header.similarityFunction();
        this.closed = false;
    }

    /**
     * Opens a disk-based HNSW index for read-only search.
     *
     * @param indexPath path to the index file
     * @return a ready-to-search disk index
     * @throws IOException if the file cannot be read or is invalid
     */
    public static DiskHnswIndex open(Path indexPath) throws IOException {
        var raf = new RandomAccessFile(indexPath.toFile(), "r");
        var channel = raf.getChannel();
        long fileSize = raf.length();

        var arena = Arena.ofShared();
        var segment = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize, arena);

        // Read and validate header
        var header = IndexFileFormat.readHeader(segment);
        header.validate();

        // Load ID table into heap
        String[] ids = readIdTable(segment, header);

        log.info("DiskHnswIndex opened: {} nodes, {} dims, file={} ({} bytes)",
                header.nodeCount(), header.dimensions(), indexPath, fileSize);

        return new DiskHnswIndex(indexPath, header, arena, segment, raf, channel, ids);
    }

    @Override
    public void add(String id, int storeIndex, float[] vector) {
        throw new UnsupportedOperationException(
                "DiskHnswIndex is read-only. Build with HnswIndex → DiskHnswWriter.");
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ScoredResult[] search(float[] query, int k) {
        if (query.length != header.dimensions()) {
            throw new IllegalArgumentException(
                    "Expected " + header.dimensions() + " dims, got " + query.length);
        }
        if (header.nodeCount() == 0) {
            return new ScoredResult[0];
        }

        int ef = Math.max(k, 50); // default efSearch
        int currentNode = header.entryPoint();

        // Phase 1: Greedy descent through upper layers
        for (int lc = header.maxLevel(); lc > 0; lc--) {
            currentNode = greedyClosest(query, currentNode, lc);
        }

        // Phase 2: Beam search at layer 0
        NeighborQueue candidates = searchLayer(query, currentNode, ef);

        // Extract top-K
        boolean higherIsBetter = similarityFunction.higherIsBetter();
        ScoredResult[] results = candidates.toSortedResults(ids, higherIsBetter);
        if (results.length > k) {
            results = java.util.Arrays.copyOf(results, k);
        }
        return results;
    }

    @Override
    public int size() { return header.nodeCount(); }

    @Override
    public SimilarityFunction similarityFunction() { return similarityFunction; }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            try {
                arena.close();
                channel.close();
                raf.close();
                log.info("DiskHnswIndex closed: {}", filePath);
            } catch (IOException e) {
                log.warn("Error closing DiskHnswIndex", e);
            }
        }
    }

    /** Returns the file path. */
    public Path filePath() { return filePath; }

    /** Returns the header. */
    public IndexFileFormat.Header header() { return header; }

    // ─────────────── Graph operations (mmap-backed) ───────────────

    private int greedyClosest(float[] query, int startNode, int layer) {
        int current = startNode;
        float currentDist = distance(query, current);
        boolean improved = true;

        while (improved) {
            improved = false;
            int[] nbrs = readNeighbors(current, layer);
            for (int neighbor : nbrs) {
                float dist = distance(query, neighbor);
                if (isBetter(dist, currentDist)) {
                    current = neighbor;
                    currentDist = dist;
                    improved = true;
                }
            }
        }
        return current;
    }

    private NeighborQueue searchLayer(float[] query, int entryNode, int ef) {
        BitSet visited = new BitSet(header.nodeCount());
        NeighborQueue candidates = new NeighborQueue(ef + 1, ef, maxHeap());
        NeighborQueue workQueue = new NeighborQueue(ef + 1, minHeap());

        float entryDist = distance(query, entryNode);
        candidates.add(entryNode, entryDist);
        workQueue.add(entryNode, entryDist);
        visited.set(entryNode);

        while (!workQueue.isEmpty()) {
            float currentDist = workQueue.topScore();
            int current = workQueue.poll();

            if (candidates.size() >= ef && !isBetter(currentDist, candidates.topScore())) {
                break;
            }

            int[] nbrs = readNeighbors(current, 0);
            for (int neighbor : nbrs) {
                if (!visited.get(neighbor)) {
                    visited.set(neighbor);
                    float dist = distance(query, neighbor);
                    if (candidates.size() < ef || isBetter(dist, candidates.topScore())) {
                        candidates.add(neighbor, dist);
                        workQueue.add(neighbor, dist);
                    }
                }
            }
        }
        return candidates;
    }

    // ─────────────── Mmap accessors ───────────────

    /** Reads a vector from the mmap'd vector data region. */
    private float[] readVector(int nodeIdx) {
        int dims = header.dimensions();
        float[] vector = new float[dims];
        long offset = header.vectorDataOffset() + (long) nodeIdx * dims * Float.BYTES;
        MemorySegment.copy(segment, IndexFileFormat.FLOAT_U, offset, vector, 0, dims);
        return vector;
    }

    /** Reads neighbor indices from the mmap'd graph data region. */
    private int[] readNeighbors(int nodeIdx, int layer) {
        long blockOffset = header.graphDataOffset()
                + (long) nodeIdx * header.graphBlockSize();

        // Skip level field
        long pos = blockOffset + 4;

        if (layer == 0) {
            int count = segment.get(IndexFileFormat.INT_U, pos);
            pos += 4;
            int[] neighbors = new int[count];
            for (int i = 0; i < count; i++) {
                neighbors[i] = segment.get(IndexFileFormat.INT_U, pos + (long) i * 4);
            }
            return neighbors;
        }

        // Skip layer 0
        pos += 4 + (long) header.maxLevel0Connections() * 4;

        // Skip to the requested upper layer
        for (int l = 1; l < layer; l++) {
            pos += 4 + (long) header.m() * 4;
        }

        int count = segment.get(IndexFileFormat.INT_U, pos);
        pos += 4;
        int[] neighbors = new int[count];
        for (int i = 0; i < count; i++) {
            neighbors[i] = segment.get(IndexFileFormat.INT_U, pos + (long) i * 4);
        }
        return neighbors;
    }

    private float distance(float[] query, int nodeIdx) {
        float[] vector = readVector(nodeIdx);
        return similarityFunction.compute(query, vector);
    }

    private boolean isBetter(float a, float b) {
        return similarityFunction.higherIsBetter() ? a > b : a < b;
    }

    private boolean minHeap() { return !similarityFunction.higherIsBetter(); }
    private boolean maxHeap() { return similarityFunction.higherIsBetter(); }

    // ─────────────── ID table ───────────────

    private static String[] readIdTable(MemorySegment segment,
                                         IndexFileFormat.Header header) {
        String[] ids = new String[header.nodeCount()];
        long pos = header.idTableOffset();

        for (int i = 0; i < header.nodeCount(); i++) {
            int len = segment.get(IndexFileFormat.INT_U, pos);
            pos += 4;
            byte[] bytes = new byte[len];
            MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, pos, bytes, 0, len);
            ids[i] = new String(bytes, StandardCharsets.UTF_8);
            pos += len;
        }
        return ids;
    }
}
