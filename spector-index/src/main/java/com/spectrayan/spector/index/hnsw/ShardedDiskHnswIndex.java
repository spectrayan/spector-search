package com.spectrayan.spector.index;

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
import java.nio.file.Path;
import java.util.Arrays;
import java.util.BitSet;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Read-only HNSW index backed by multiple memory-mapped shard files.
 *
 * <p>Each shard is independently mmap'd via its own {@link Arena}. The HNSW
 * search algorithm works identically to {@link DiskHnswIndex} — greedy descent
 * through upper layers followed by beam search at layer 0 — but reads vectors
 * and neighbors from the correct shard using global-to-local index mapping:</p>
 * <pre>
 *   shardIdx  = globalNodeIdx / nodesPerShard
 *   localIdx  = globalNodeIdx % nodesPerShard
 * </pre>
 *
 * <p>Neighbor indices in the shard files are <b>global</b>, so cross-shard
 * graph traversal is transparent and requires no remapping.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>Concurrent searches are safe (shared arenas, read-only segments).</p>
 *
 * @see ShardedDiskHnswWriter
 * @see ShardedIndexFormat
 */
public class ShardedDiskHnswIndex implements VectorIndex {

    private static final Logger log = LoggerFactory.getLogger(ShardedDiskHnswIndex.class);

    private final Path shardDir;
    private final ShardedIndexFormat.Manifest manifest;
    private final Shard[] shards;
    private final String[] ids;  // global ID table (heap)
    private final SimilarityFunction similarityFunction;
    private volatile boolean closed;
    private volatile long lastAccessed;

    /**
     * Per-shard mmap context.
     */
    private record Shard(
            Path filePath,
            IndexFileFormat.Header header,
            Arena arena,
            MemorySegment segment,
            RandomAccessFile raf,
            FileChannel channel
    ) {
        void close() throws IOException {
            if (segment.isMapped()) {
                com.spectrayan.spector.commons.concurrent.MemoryPinning.unlock(segment);
                segment.unload();
            }
            arena.close();
            channel.close();
            raf.close();
        }
    }

    private ShardedDiskHnswIndex(Path shardDir, ShardedIndexFormat.Manifest manifest,
                                  Shard[] shards, String[] ids) {
        this.shardDir = shardDir;
        this.manifest = manifest;
        this.shards = shards;
        this.ids = ids;
        this.similarityFunction = SimilarityFunction.values()[manifest.similarity()];
        this.closed = false;
        this.lastAccessed = System.currentTimeMillis();
    }

    /**
     * Opens a sharded disk HNSW index for read-only search.
     *
     * @param shardDir directory containing the manifest and shard files
     * @return a ready-to-search sharded index
     * @throws IOException if any file cannot be read or is invalid
     */
    public static ShardedDiskHnswIndex open(Path shardDir) throws IOException {
        // 1. Read manifest
        var manifest = ShardedIndexFormat.readManifest(shardDir);
        manifest.validate();

        int shardCount = manifest.shardCount();
        Shard[] shards = new Shard[shardCount];

        // 2. Open each shard file
        try {
            for (int s = 0; s < shardCount; s++) {
                Path shardPath = shardDir.resolve(ShardedIndexFormat.shardFileName(s));
                var raf = new RandomAccessFile(shardPath.toFile(), "r");
                var channel = raf.getChannel();
                long fileSize = raf.length();

                var arena = Arena.ofShared();
                var segment = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize, arena);

                var header = IndexFileFormat.readHeader(segment);
                header.validate();

                shards[s] = new Shard(shardPath, header, arena, segment, raf, channel);
            }
        } catch (Exception e) {
            // Close any shards we already opened
            for (Shard shard : shards) {
                if (shard != null) {
                    try { shard.close(); } catch (Exception ignore) {}
                }
            }
            throw new IOException("Failed to open sharded index at " + shardDir, e);
        }

        // 3. Load global ID table from all shards
        String[] ids = new String[manifest.totalNodeCount()];
        int globalIdx = 0;
        for (int s = 0; s < shardCount; s++) {
            String[] shardIds = readIdTable(shards[s].segment(), shards[s].header());
            System.arraycopy(shardIds, 0, ids, globalIdx, shardIds.length);
            globalIdx += shardIds.length;
        }

        log.info("ShardedDiskHnswIndex opened: {} nodes across {} shards, dims={}, dir={}",
                manifest.totalNodeCount(), shardCount, manifest.dimensions(), shardDir);

        var index = new ShardedDiskHnswIndex(shardDir, manifest, shards, ids);
        index.warmup();
        return index;
    }

    // ─────────────── VectorIndex implementation ───────────────

    @Override
    public void add(String id, int storeIndex, float[] vector) {
        throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "ShardedDiskHnswIndex", "read-only; build with AbstractHnswIndex + ShardedDiskHnswWriter");
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ScoredResult[] search(float[] query, int k) {
        this.lastAccessed = System.currentTimeMillis();
        if (query.length != manifest.dimensions()) {
            throw new SpectorValidationException(ErrorCode.DIMENSIONS_MISMATCH, manifest.dimensions(), query.length);
        }
        if (manifest.totalNodeCount() == 0) {
            return new ScoredResult[0];
        }

        int ef = Math.max(k, 50);
        int currentNode = manifest.globalEntryPoint();

        // Phase 1: Greedy descent through upper layers
        for (int lc = manifest.globalMaxLevel(); lc > 0; lc--) {
            currentNode = greedyClosest(query, currentNode, lc);
        }

        // Phase 2: Beam search at layer 0
        NeighborQueue candidates = searchLayer(query, currentNode, ef);

        // Extract top-K
        boolean higherIsBetter = similarityFunction.higherIsBetter();
        ScoredResult[] results = candidates.toSortedResults(ids, higherIsBetter);
        if (results.length > k) {
            results = Arrays.copyOf(results, k);
        }
        return results;
    }

    @Override
    public int size() { return manifest.totalNodeCount(); }

    @Override
    public SimilarityFunction similarityFunction() { return similarityFunction; }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            for (Shard shard : shards) {
                try {
                    shard.close();
                } catch (IOException e) {
                    log.warn("Error closing shard {}", shard.filePath(), e);
                }
            }
            log.info("ShardedDiskHnswIndex closed: {} shards, dir={}", shards.length, shardDir);
        }
    }

    // ─────────────── Warmup ───────────────

    /**
     * Pre-touches all shard segments on virtual threads for warm page cache.
     */
    public void warmup() {
        for (Shard shard : shards) {
            if (shard.segment().isMapped()) {
                Thread.startVirtualThread(() -> {
                    long start = System.nanoTime();
                    try {
                        shard.segment().load();
                        boolean pinned = com.spectrayan.spector.commons.concurrent.MemoryPinning.lock(shard.segment());
                        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                        log.debug("Shard warmed up (pinned={}) in {} ms: {}",
                                pinned, elapsedMs, shard.filePath());
                    } catch (Exception e) {
                        log.warn("Failed to warm up shard {}: {}", shard.filePath(), e.getMessage());
                    }
                });
            }
        }
    }

    /**
     * Evicts idle shard pages from physical memory.
     *
     * @param gracePeriodMs threshold of inactivity in milliseconds
     * @return true if any shards were evicted
     */
    public synchronized boolean unloadIdle(long gracePeriodMs) {
        if (closed) return false;
        long idleMs = System.currentTimeMillis() - lastAccessed;
        if (idleMs < gracePeriodMs) return false;

        boolean evicted = false;
        for (Shard shard : shards) {
            if (shard.segment().isMapped()) {
                com.spectrayan.spector.commons.concurrent.MemoryPinning.unlock(shard.segment());
                shard.segment().unload();
                evicted = true;
            }
        }
        if (evicted) {
            log.info("ShardedDiskHnswIndex idle-evicted all shards (idle for {} ms)", idleMs);
        }
        return evicted;
    }

    // ─────────────── Accessors ───────────────

    /** Returns the shard directory path. */
    public Path shardDir() { return shardDir; }

    /** Returns the manifest. */
    public ShardedIndexFormat.Manifest manifest() { return manifest; }

    /** Returns the number of shards. */
    public int shardCount() { return shards.length; }

    /** Returns the HNSW entry point node index. */
    public int entryPoint() { return manifest.globalEntryPoint(); }

    /** Returns the HNSW maximum level. */
    public int maxLevel() { return manifest.globalMaxLevel(); }

    /** Returns the ID for the given global node index. */
    public String getId(int globalNodeIdx) { return ids[globalNodeIdx]; }

    // ─────────────── Graph operations (mmap-backed, cross-shard) ───────────────

    /**
     * Reads a vector from the correct shard's mmap'd segment.
     *
     * @param globalNodeIdx global node index
     * @return the float32 vector
     */
    public float[] readVector(int globalNodeIdx) {
        int shardIdx = manifest.shardFor(globalNodeIdx);
        int localIdx = manifest.localIndex(globalNodeIdx);
        Shard shard = shards[shardIdx];
        int dims = manifest.dimensions();
        float[] vector = new float[dims];
        long offset = shard.header().vectorDataOffset()
                + (long) localIdx * dims * Float.BYTES;
        MemorySegment.copy(shard.segment(), IndexFileFormat.FLOAT_U, offset, vector, 0, dims);
        return vector;
    }

    /**
     * Reads neighbor indices from the correct shard's graph region.
     * Returned indices are <b>global</b>.
     *
     * @param globalNodeIdx global node index
     * @param layer         the HNSW layer
     * @return neighbor indices (global)
     */
    public int[] readNeighbors(int globalNodeIdx, int layer) {
        int shardIdx = manifest.shardFor(globalNodeIdx);
        int localIdx = manifest.localIndex(globalNodeIdx);
        Shard shard = shards[shardIdx];
        MemorySegment seg = shard.segment();
        IndexFileFormat.Header h = shard.header();

        long blockOffset = h.graphDataOffset() + (long) localIdx * h.graphBlockSize();
        long pos = blockOffset + 4; // skip level field

        if (layer == 0) {
            int count = seg.get(IndexFileFormat.INT_U, pos);
            pos += 4;
            int[] neighbors = new int[count];
            for (int i = 0; i < count; i++) {
                neighbors[i] = seg.get(IndexFileFormat.INT_U, pos + (long) i * 4);
            }
            return neighbors;
        }

        // Skip layer 0
        pos += 4 + (long) h.maxLevel0Connections() * 4;

        // Skip to the requested upper layer
        for (int l = 1; l < layer; l++) {
            pos += 4 + (long) h.m() * 4;
        }

        int count = seg.get(IndexFileFormat.INT_U, pos);
        pos += 4;
        int[] neighbors = new int[count];
        for (int i = 0; i < count; i++) {
            neighbors[i] = seg.get(IndexFileFormat.INT_U, pos + (long) i * 4);
        }
        return neighbors;
    }

    /**
     * Reads the HNSW level for a global node index.
     */
    public int readLevel(int globalNodeIdx) {
        int shardIdx = manifest.shardFor(globalNodeIdx);
        int localIdx = manifest.localIndex(globalNodeIdx);
        Shard shard = shards[shardIdx];
        long blockOffset = shard.header().graphDataOffset()
                + (long) localIdx * shard.header().graphBlockSize();
        return shard.segment().get(IndexFileFormat.INT_U, blockOffset);
    }

    // ─────────────── HNSW search algorithm ───────────────

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
        BitSet visited = new BitSet(manifest.totalNodeCount());
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

    private float distance(float[] query, int globalNodeIdx) {
        float[] vector = readVector(globalNodeIdx);
        return similarityFunction.compute(query, vector);
    }

    private boolean isBetter(float a, float b) {
        return similarityFunction.higherIsBetter() ? a > b : a < b;
    }

    private boolean minHeap() { return !similarityFunction.higherIsBetter(); }
    private boolean maxHeap() { return similarityFunction.higherIsBetter(); }

    // ─────────────── ID table reader ───────────────

    private static String[] readIdTable(MemorySegment segment, IndexFileFormat.Header header) {
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
