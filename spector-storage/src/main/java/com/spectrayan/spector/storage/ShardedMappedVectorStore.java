package com.spectrayan.spector.storage;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Memory-mapped vector store that spreads vectors across multiple shard files.
 *
 * <p>Each shard file holds up to {@code nodesPerShard} vectors. Shards are
 * <b>lazily allocated</b> — a new shard file is created only when the current
 * shard fills up. This eliminates the pre-allocation of the full capacity
 * upfront (which caused a 1.6 GB file for 100K × 384-dim vectors).</p>
 *
 * <p>Shard resolution is trivial: {@code shardIdx = vectorIndex / nodesPerShard},
 * {@code localIdx = vectorIndex % nodesPerShard}. This matches the HNSW index
 * sharding boundary so index shard N contains the graph for the same vectors
 * that live in vector shard N.</p>
 *
 * <h3>File Naming</h3>
 * <pre>
 *   index_shards/vectors-000000.mmap
 *   index_shards/vectors-000001.mmap
 *   ...
 * </pre>
 *
 * <h3>Thread Safety</h3>
 * <ul>
 *   <li>Concurrent reads are safe (shared arenas).</li>
 *   <li>Writes are serialized via {@link ReentrantLock}.</li>
 * </ul>
 */
public class ShardedMappedVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(ShardedMappedVectorStore.class);

    /** Shard file name format: vectors-000000.mmap */
    private static final String SHARD_NAME_FORMAT = "vectors-%06d.mmap";

    private final VectorStoreLayout layout;
    private final int capacity;
    private final int nodesPerShard;
    private final Path shardDir;
    private final Map<String, Integer> idToIndex;
    private final AtomicInteger count;
    private final ReentrantLock writeLock = new ReentrantLock();
    private volatile boolean closed;
    private volatile long lastAccessed;

    /**
     * Per-shard mmap context. Lazily allocated.
     */
    private static final class VectorShard {
        final Path filePath;
        final int shardCapacity;
        final Arena arena;
        final MemorySegment segment;
        final RandomAccessFile raf;
        final FileChannel channel;

        VectorShard(Path filePath, int shardCapacity, Arena arena,
                    MemorySegment segment, RandomAccessFile raf, FileChannel channel) {
            this.filePath = filePath;
            this.shardCapacity = shardCapacity;
            this.arena = arena;
            this.segment = segment;
            this.raf = raf;
            this.channel = channel;
        }

        void close() throws IOException {
            if (segment.isMapped()) {
                segment.force();
                com.spectrayan.spector.commons.concurrent.MemoryPinning.unlock(segment);
                segment.unload();
            }
            arena.close();
            channel.close();
            raf.close();
        }
    }

    /** Lazily-growing array of shard contexts. */
    private VectorShard[] shards;
    private int activeShardCount;

    /**
     * Creates a sharded vector store.
     *
     * @param shardDir      directory for shard files (created if absent)
     * @param dimensions    number of float elements per vector
     * @param capacity      maximum total number of vectors
     * @param nodesPerShard maximum vectors per shard file
     * @throws IOException if directory creation fails
     */
    public ShardedMappedVectorStore(Path shardDir, int dimensions, int capacity,
                                     int nodesPerShard) throws IOException {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive: " + capacity);
        if (nodesPerShard <= 0) throw new IllegalArgumentException("nodesPerShard must be positive: " + nodesPerShard);

        this.layout = new VectorStoreLayout(dimensions);
        this.capacity = capacity;
        this.nodesPerShard = nodesPerShard;
        this.shardDir = shardDir;
        this.idToIndex = new ConcurrentHashMap<>(capacity);
        this.count = new AtomicInteger(0);
        this.closed = false;
        this.lastAccessed = System.currentTimeMillis();

        Files.createDirectories(shardDir);

        int maxShards = (capacity + nodesPerShard - 1) / nodesPerShard;
        this.shards = new VectorShard[maxShards];
        this.activeShardCount = 0;

        // Open any existing shard files (for restart recovery)
        for (int s = 0; s < maxShards; s++) {
            Path shardPath = shardDir.resolve(shardFileName(s));
            if (Files.exists(shardPath)) {
                shards[s] = openShard(shardPath, s);
                activeShardCount = s + 1;
            } else {
                break; // Shards are contiguous
            }
        }

        log.info("ShardedMappedVectorStore created: dir={}, dims={}, capacity={}, nodesPerShard={}, existingShards={}",
                shardDir, dimensions, capacity, nodesPerShard, activeShardCount);
    }

    @Override
    public int put(String id, float[] vector) {
        writeLock.lock();
        try {
            ensureOpen();
            this.lastAccessed = System.currentTimeMillis();
            if (vector.length != layout.dimensions()) {
                throw new IllegalArgumentException(
                        "Expected " + layout.dimensions() + " dimensions, got " + vector.length);
            }

            // Update in-place if ID exists
            Integer existingIndex = idToIndex.get(id);
            if (existingIndex != null) {
                writeVectorAt(existingIndex, vector);
                return existingIndex;
            }

            // Allocate new slot
            int index = count.getAndIncrement();
            if (index >= capacity) {
                count.decrementAndGet();
                throw new IllegalStateException("Store is full: capacity=" + capacity);
            }

            // Ensure the target shard exists
            int shardIdx = index / nodesPerShard;
            ensureShardOpen(shardIdx);

            writeVectorAt(index, vector);
            idToIndex.put(id, index);
            return index;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Failed to open vector shard", e);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public float[] get(String id) {
        ensureOpen();
        this.lastAccessed = System.currentTimeMillis();
        Integer index = idToIndex.get(id);
        return index == null ? null : readVectorAt(index);
    }

    @Override
    public float[] getByIndex(int index) {
        ensureOpen();
        validateIndex(index);
        this.lastAccessed = System.currentTimeMillis();
        return readVectorAt(index);
    }

    @Override
    public void getByIndex(int index, float[] dst, int dstOffset) {
        ensureOpen();
        validateIndex(index);
        this.lastAccessed = System.currentTimeMillis();
        int shardIdx = index / nodesPerShard;
        int localIdx = index % nodesPerShard;
        layout.readVector(shards[shardIdx].segment, localIdx, dst, dstOffset);
    }

    @Override
    public int indexOf(String id) {
        this.lastAccessed = System.currentTimeMillis();
        Integer index = idToIndex.get(id);
        return index == null ? -1 : index;
    }

    @Override
    public int size() { return count.get(); }

    @Override
    public int dimensions() { return layout.dimensions(); }

    @Override
    public int capacity() { return capacity; }

    @Override
    public boolean isClosed() { return closed; }

    /** Returns the path to the shard directory. */
    public Path shardDir() { return shardDir; }

    /** Returns the nodes-per-shard configuration. */
    public int nodesPerShard() { return nodesPerShard; }

    /** Returns the number of active (open) shard files. */
    public int activeShardCount() { return activeShardCount; }

    @Override
    public void close() {
        writeLock.lock();
        try {
            if (!closed) {
                closed = true;
                for (int s = 0; s < activeShardCount; s++) {
                    if (shards[s] != null) {
                        try {
                            shards[s].close();
                        } catch (IOException e) {
                            log.warn("Error closing vector shard {}", shards[s].filePath, e);
                        }
                    }
                }
                log.info("ShardedMappedVectorStore closed: {} vectors across {} shards, dir={}",
                        count.get(), activeShardCount, shardDir);
            }
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Pre-touches all shard segments on virtual threads for warm page cache.
     */
    public void warmup() {
        for (int s = 0; s < activeShardCount; s++) {
            final VectorShard shard = shards[s];
            if (shard != null && shard.segment.isMapped()) {
                Thread.startVirtualThread(() -> {
                    long start = System.nanoTime();
                    try {
                        shard.segment.load();
                        boolean pinned = com.spectrayan.spector.commons.concurrent.MemoryPinning.lock(shard.segment);
                        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                        log.debug("Vector shard warmed up (pinned={}) in {} ms: {}",
                                pinned, elapsedMs, shard.filePath);
                    } catch (Exception e) {
                        log.warn("Failed to warm up vector shard {}: {}", shard.filePath, e.getMessage());
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
    public boolean unloadIdle(long gracePeriodMs) {
        writeLock.lock();
        try {
            if (closed) return false;
            long idleMs = System.currentTimeMillis() - lastAccessed;
            if (idleMs < gracePeriodMs) return false;

            boolean evicted = false;
            for (int s = 0; s < activeShardCount; s++) {
                if (shards[s] != null && shards[s].segment.isMapped()) {
                    com.spectrayan.spector.commons.concurrent.MemoryPinning.unlock(shards[s].segment);
                    shards[s].segment.unload();
                    evicted = true;
                }
            }
            if (evicted) {
                log.info("ShardedMappedVectorStore idle-evicted all shards (idle for {} ms)", idleMs);
            }
            return evicted;
        } finally {
            writeLock.unlock();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ID MAPPING PERSISTENCE (same format as MappedVectorStore)
    // ══════════════════════════════════════════════════════════════

    /** File magic: "VIDS" in ASCII. */
    private static final int VIDS_MAGIC = 0x56494453;
    private static final int VIDS_VERSION = 1;
    private static final int VIDS_HEADER_BYTES = 16;

    /**
     * Saves the id→index mapping to a binary file.
     *
     * @param mappingPath path to write the ID mapping file
     */
    public void saveIdMappings(Path mappingPath) {
        Path parent = mappingPath.getParent();
        if (parent != null) {
            try { Files.createDirectories(parent); } catch (IOException e) {
                log.warn("Cannot create id-mappings directory: {}", e.getMessage());
                return;
            }
        }

        try (var ch = FileChannel.open(mappingPath,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {

            java.nio.ByteBuffer header = java.nio.ByteBuffer.allocate(VIDS_HEADER_BYTES);
            header.putInt(VIDS_MAGIC);
            header.putInt(VIDS_VERSION);
            header.putInt(idToIndex.size());
            header.putInt(0);
            header.flip();
            ch.write(header);

            for (var entry : idToIndex.entrySet()) {
                byte[] idBytes = entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(4 + idBytes.length + 4);
                buf.putInt(idBytes.length);
                buf.put(idBytes);
                buf.putInt(entry.getValue());
                buf.flip();
                ch.write(buf);
            }

            ch.force(true);
            log.info("ShardedMappedVectorStore ID mappings saved: {} entries → {}", idToIndex.size(), mappingPath);
        } catch (IOException e) {
            log.error("Failed to save ID mappings: {}", e.getMessage());
        }
    }

    /**
     * Loads id→index mappings from a binary file.
     *
     * @param mappingPath path to read the ID mapping file
     */
    public void loadIdMappings(Path mappingPath) {
        if (mappingPath == null || !Files.exists(mappingPath)) {
            log.info("ID mappings file not found: {}", mappingPath);
            return;
        }

        try (var ch = FileChannel.open(mappingPath, java.nio.file.StandardOpenOption.READ)) {
            if (ch.size() < VIDS_HEADER_BYTES) return;

            java.nio.ByteBuffer header = java.nio.ByteBuffer.allocate(VIDS_HEADER_BYTES);
            ch.read(header);
            header.flip();

            int magic = header.getInt();
            int version = header.getInt();
            int entryCount = header.getInt();
            header.getInt(); // reserved

            if (magic != VIDS_MAGIC || version != VIDS_VERSION) {
                log.warn("Invalid ID mappings file header, skipping");
                return;
            }

            int maxIdx = -1;
            for (int i = 0; i < entryCount; i++) {
                java.nio.ByteBuffer lenBuf = java.nio.ByteBuffer.allocate(4);
                ch.read(lenBuf);
                lenBuf.flip();
                int idLen = lenBuf.getInt();

                java.nio.ByteBuffer idBuf = java.nio.ByteBuffer.allocate(idLen);
                ch.read(idBuf);
                idBuf.flip();
                String id = new String(idBuf.array(), 0, idLen, java.nio.charset.StandardCharsets.UTF_8);

                java.nio.ByteBuffer idxBuf = java.nio.ByteBuffer.allocate(4);
                ch.read(idxBuf);
                idxBuf.flip();
                int idx = idxBuf.getInt();

                idToIndex.put(id, idx);
                if (idx > maxIdx) maxIdx = idx;
            }

            if (maxIdx >= 0) {
                count.set(maxIdx + 1);
            }

            log.info("ShardedMappedVectorStore ID mappings loaded: {} entries from {}", idToIndex.size(), mappingPath);
        } catch (IOException e) {
            log.error("Failed to load ID mappings: {}", e.getMessage());
        }
    }

    // ─────────────── Internal helpers ───────────────

    private void writeVectorAt(int globalIndex, float[] vector) {
        int shardIdx = globalIndex / nodesPerShard;
        int localIdx = globalIndex % nodesPerShard;
        layout.writeVector(shards[shardIdx].segment, localIdx, vector);
    }

    private float[] readVectorAt(int globalIndex) {
        int shardIdx = globalIndex / nodesPerShard;
        int localIdx = globalIndex % nodesPerShard;
        return layout.readVector(shards[shardIdx].segment, localIdx);
    }

    private void ensureShardOpen(int shardIdx) throws IOException {
        if (shardIdx < activeShardCount && shards[shardIdx] != null) {
            return; // Already open
        }
        // Open shards up to and including shardIdx
        for (int s = activeShardCount; s <= shardIdx; s++) {
            Path shardPath = shardDir.resolve(shardFileName(s));
            shards[s] = openShard(shardPath, s);
        }
        activeShardCount = shardIdx + 1;
    }

    private VectorShard openShard(Path shardPath, int shardIdx) throws IOException {
        int shardCapacity = Math.min(nodesPerShard,
                capacity - shardIdx * nodesPerShard);
        long totalBytes = layout.totalByteSize(shardCapacity);

        var raf = new RandomAccessFile(shardPath.toFile(), "rw");
        raf.setLength(totalBytes);
        var channel = raf.getChannel();
        var arena = Arena.ofShared();
        var segment = channel.map(FileChannel.MapMode.READ_WRITE, 0, totalBytes, arena);

        log.debug("Opened vector shard {}: path={}, capacity={}, bytes={}",
                shardIdx, shardPath, shardCapacity, totalBytes);

        return new VectorShard(shardPath, shardCapacity, arena, segment, raf, channel);
    }

    private static String shardFileName(int shardIdx) {
        return String.format(SHARD_NAME_FORMAT, shardIdx);
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("VectorStore is closed");
    }

    private void validateIndex(int index) {
        if (index < 0 || index >= count.get()) {
            throw new IndexOutOfBoundsException("index=" + index + ", size=" + count.get());
        }
    }
}
