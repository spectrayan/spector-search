package com.spectrayan.spector.storage;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.SpectorStoreFullException;
import com.spectrayan.spector.commons.error.SpectorSegmentClosedException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * In-memory vector store backed by a contiguous off-heap {@link MemorySegment}.
 *
 * <p>All vector data lives outside the Java heap in a Panama {@link Arena}-managed
 * segment. This eliminates GC pressure for large vector datasets while providing
 * deterministic memory cleanup on {@link #close()}.</p>
 *
 * <p>The store pre-allocates a fixed-capacity segment. Vectors are written
 * sequentially; ID-to-index mapping is maintained in a {@link ConcurrentHashMap}
 * for concurrent read access from virtual threads.</p>
 *
 * <h3>Thread Safety</h3>
 * <ul>
 *   <li>Concurrent reads are safe (shared arena).</li>
 *   <li>Writes are serialized via {@code synchronized} on write path only.</li>
 * </ul>
 */
public class InMemoryVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryVectorStore.class);

    private final VectorStoreLayout layout;
    private final int capacity;
    private final Arena arena;
    private final MemorySegment segment;
    private final Map<String, Integer> idToIndex;
    private final AtomicInteger count;
    private final ReentrantLock writeLock = new ReentrantLock();
    private volatile boolean closed;

    /**
     * Creates a new in-memory vector store.
     *
     * @param dimensions number of float elements per vector
     * @param capacity   maximum number of vectors to store
     */
    public InMemoryVectorStore(int dimensions, int capacity) {
        if (capacity <= 0) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "capacity", 1, Integer.MAX_VALUE, capacity);
        }

        this.layout = new VectorStoreLayout(dimensions);
        this.capacity = capacity;
        this.arena = Arena.ofShared();
        this.segment = arena.allocate(layout.totalByteSize(capacity),
                ValueLayout.JAVA_FLOAT.byteAlignment());
        this.idToIndex = new ConcurrentHashMap<>(capacity);
        this.count = new AtomicInteger(0);
        this.closed = false;

        log.info("InMemoryVectorStore created: dimensions={}, capacity={}, bytes={}",
                dimensions, capacity, layout.totalByteSize(capacity));
    }

    @Override
    public int put(String id, float[] vector) {
        writeLock.lock();
        try {
            ensureOpen();
            if (vector.length != layout.dimensions()) {
                throw new SpectorValidationException(ErrorCode.DIMENSIONS_MISMATCH, "Expected " + layout.dimensions() + " dimensions, got " + vector.length);
            }

            // Check if ID already exists (update in-place)
            Integer existingIndex = idToIndex.get(id);
            if (existingIndex != null) {
                layout.writeVector(segment, existingIndex, vector);
                return existingIndex;
            }

            // Allocate new slot
            int index = count.getAndIncrement();
            if (index >= capacity) {
                count.decrementAndGet();
                throw new SpectorStoreFullException(capacity);
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

    @Override
    public void close() {
        writeLock.lock();
        try {
            if (!closed) {
                closed = true;
                arena.close();
                log.info("InMemoryVectorStore closed: released {} vectors", count.get());
            }
        } finally {
            writeLock.unlock();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new SpectorSegmentClosedException();
        }
    }

    private void validateIndex(int index) {
        if (index < 0 || index >= count.get()) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, 
                    "index=" + index + ", size=" + count.get());
        }
    }
}