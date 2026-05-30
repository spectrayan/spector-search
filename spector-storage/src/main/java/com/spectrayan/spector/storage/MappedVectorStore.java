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
 * Memory-mapped vector store backed by a file via {@link FileChannel#map}.
 *
 * <p>Vectors are stored in a flat binary file and accessed through a
 * zero-copy {@link MemorySegment} mapped from the file. This enables
 * datasets larger than available RAM to be searched efficiently, with the
 * OS page cache handling hot/cold data transparently.</p>
 *
 * <p>The file format is simple: a contiguous sequence of float vectors,
 * each occupying {@code dimensions × 4} bytes. No header or metadata is
 * stored in the file itself; the ID-to-index mapping is maintained in memory.</p>
 *
 * <h3>Thread Safety</h3>
 * <ul>
 *   <li>Concurrent reads are safe (shared arena).</li>
 *   <li>Writes are serialized via {@code synchronized}.</li>
 * </ul>
 */
public class MappedVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(MappedVectorStore.class);

    private final VectorStoreLayout layout;
    private final int capacity;
    private final Path filePath;
    private final Arena arena;
    private final MemorySegment segment;
    private final RandomAccessFile raf;
    private final FileChannel channel;
    private final Map<String, Integer> idToIndex;
    private final AtomicInteger count;
    private final ReentrantLock writeLock = new ReentrantLock();
    private volatile boolean closed;
    private volatile long lastAccessed;

    /**
     * Creates or opens a memory-mapped vector store.
     *
     * @param filePath   path to the backing file (created if absent)
     * @param dimensions number of float elements per vector
     * @param capacity   maximum number of vectors
     * @throws IOException if the file cannot be created or mapped
     */
    public MappedVectorStore(Path filePath, int dimensions, int capacity) throws IOException {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }

        this.layout = new VectorStoreLayout(dimensions);
        this.capacity = capacity;
        this.filePath = filePath;
        this.idToIndex = new ConcurrentHashMap<>(capacity);
        this.count = new AtomicInteger(0);
        this.closed = false;
        this.lastAccessed = System.currentTimeMillis();

        // Ensure parent directories exist
        Path parent = filePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        long totalBytes = layout.totalByteSize(capacity);

        // Open file and pre-allocate to full size
        this.raf = new RandomAccessFile(filePath.toFile(), "rw");
        raf.setLength(totalBytes);
        this.channel = raf.getChannel();

        // Memory-map the entire file
        this.arena = Arena.ofShared();
        this.segment = channel.map(FileChannel.MapMode.READ_WRITE, 0, totalBytes, arena);

        warmup(); // Warm up asynchronously on creation

        log.info("MappedVectorStore created: path={}, dimensions={}, capacity={}, bytes={}",
                filePath, dimensions, capacity, totalBytes);
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
                layout.writeVector(segment, existingIndex, vector);
                return existingIndex;
            }

            // Allocate new slot
            int index = count.getAndIncrement();
            if (index >= capacity) {
                count.decrementAndGet();
                throw new IllegalStateException("Store is full: capacity=" + capacity);
            }

            layout.writeVector(segment, index, vector);
            idToIndex.put(id, index);
            return index;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public float[] get(String id) {
        ensureOpen();
        this.lastAccessed = System.currentTimeMillis();
        Integer index = idToIndex.get(id);
        return index == null ? null : layout.readVector(segment, index);
    }

    @Override
    public float[] getByIndex(int index) {
        ensureOpen();
        validateIndex(index);
        this.lastAccessed = System.currentTimeMillis();
        return layout.readVector(segment, index);
    }

    @Override
    public void getByIndex(int index, float[] dst, int dstOffset) {
        ensureOpen();
        validateIndex(index);
        this.lastAccessed = System.currentTimeMillis();
        layout.readVector(segment, index, dst, dstOffset);
    }

    @Override
    public int indexOf(String id) {
        this.lastAccessed = System.currentTimeMillis();
        Integer index = idToIndex.get(id);
        return index == null ? -1 : index;
    }

    @Override
    public int size() {
        return count.get();
    }

    @Override
    public int dimensions() {
        return layout.dimensions();
    }

    @Override
    public int capacity() {
        return capacity;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    /**
     * Returns the path to the backing file.
     *
     * @return file path
     */
    public Path filePath() {
        return filePath;
    }

    @Override
    public void close() {
        writeLock.lock();
        try {
            if (!closed) {
                closed = true;
                try {
                    // Force pending writes to disk
                    segment.force();
                    if (segment.isMapped()) {
                        com.spectrayan.spector.commons.concurrent.MemoryPinning.unlock(segment);
                        segment.unload();
                    }
                    arena.close();
                    channel.close();
                    raf.close();
                    log.info("MappedVectorStore closed: released {} vectors, file={}",
                            count.get(), filePath);
                } catch (IOException e) {
                    log.warn("Error closing MappedVectorStore file channel", e);
                }
            }
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Pre-touches and loads the mapped memory segment into physical memory
     * to prevent cold-start page fault latency spikes during initial queries.
     * Performs a best-effort asynchronous load using a virtual thread.
     */
    public void warmup() {
        if (segment.isMapped()) {
            Thread.startVirtualThread(() -> {
                long start = System.nanoTime();
                try {
                    segment.load();
                    boolean pinned = com.spectrayan.spector.commons.concurrent.MemoryPinning.lock(segment);
                    long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                    log.info("MappedVectorStore warmed up successfully (pinned={}) in {} ms (file={})",
                            pinned, elapsedMs, filePath);
                } catch (Exception e) {
                    log.warn("Failed to warm up MappedVectorStore: {}", e.getMessage());
                }
            });
        }
    }

    /**
     * Evicts the mapped segment pages from physical memory if it has been inactive
     * for at least the specified grace period.
     *
     * @param gracePeriodMs threshold of inactivity in milliseconds
     * @return true if successfully evicted, false if segment is active or not mapped
     */
    public boolean unloadIdle(long gracePeriodMs) {
        writeLock.lock();
        try {
            if (!closed && segment.isMapped()) {
                long idleMs = System.currentTimeMillis() - lastAccessed;
                if (idleMs >= gracePeriodMs) {
                    com.spectrayan.spector.commons.concurrent.MemoryPinning.unlock(segment);
                    segment.unload();
                    log.info("MappedVectorStore idle-evicted: file={} (idle for {} ms)", filePath, idleMs);
                    return true;
                }
            }
            return false;
        } finally {
            writeLock.unlock();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException(com.spectrayan.spector.commons.error.ErrorCode.SEGMENT_CLOSED.format());
        }
    }

    private void validateIndex(int index) {
        if (index < 0 || index >= count.get()) {
            throw new IndexOutOfBoundsException("index=" + index + ", size=" + count.get());
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ID MAPPING PERSISTENCE
    // ══════════════════════════════════════════════════════════════

    /** File magic: "VIDS" in ASCII. */
    private static final int VIDS_MAGIC = 0x56494453;

    /** File format version. */
    private static final int VIDS_VERSION = 1;

    /** File header: 4B magic + 4B version + 4B count + 4B reserved = 16 bytes. */
    private static final int VIDS_HEADER_BYTES = 16;

    /**
     * Saves the id→index mapping to a binary file.
     *
     * @param mappingPath path to write the ID mapping file
     */
    public void saveIdMappings(Path mappingPath) {
        Path parent = mappingPath.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
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
            log.info("MappedVectorStore ID mappings saved: {} entries → {}", idToIndex.size(), mappingPath);

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
            header.getInt();

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

            // Restore the count to one past the highest loaded index
            if (maxIdx >= 0) {
                count.set(maxIdx + 1);
            }

            log.info("MappedVectorStore ID mappings loaded: {} entries from {}", idToIndex.size(), mappingPath);

        } catch (IOException e) {
            log.error("Failed to load ID mappings: {}", e.getMessage());
        }
    }
}

