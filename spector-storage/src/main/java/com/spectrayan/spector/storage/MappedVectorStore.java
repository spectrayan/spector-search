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
    private volatile boolean closed;

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

        log.info("MappedVectorStore created: path={}, dimensions={}, capacity={}, bytes={}",
                filePath, dimensions, capacity, totalBytes);
    }

    @Override
    public synchronized int put(String id, float[] vector) {
        ensureOpen();
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
    }

    @Override
    public float[] get(String id) {
        ensureOpen();
        Integer index = idToIndex.get(id);
        return index == null ? null : layout.readVector(segment, index);
    }

    @Override
    public float[] getByIndex(int index) {
        ensureOpen();
        validateIndex(index);
        return layout.readVector(segment, index);
    }

    @Override
    public void getByIndex(int index, float[] dst, int dstOffset) {
        ensureOpen();
        validateIndex(index);
        layout.readVector(segment, index, dst, dstOffset);
    }

    @Override
    public int indexOf(String id) {
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
    public synchronized void close() {
        if (!closed) {
            closed = true;
            try {
                // Force pending writes to disk
                segment.force();
                arena.close();
                channel.close();
                raf.close();
                log.info("MappedVectorStore closed: released {} vectors, file={}",
                        count.get(), filePath);
            } catch (IOException e) {
                log.warn("Error closing MappedVectorStore file channel", e);
            }
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("VectorStore is closed");
        }
    }

    private void validateIndex(int index) {
        if (index < 0 || index >= count.get()) {
            throw new IndexOutOfBoundsException("index=" + index + ", size=" + count.get());
        }
    }
}
