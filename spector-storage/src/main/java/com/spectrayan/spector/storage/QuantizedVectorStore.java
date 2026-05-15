package com.spectrayan.spector.storage;

import com.spectrayan.spector.core.ScalarQuantizer;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Off-heap vector store that stores quantized int8 vectors via Panama {@link MemorySegment}.
 *
 * <p>Vectors are quantized on write using a {@link ScalarQuantizer} and stored
 * as contiguous byte arrays in off-heap memory. This reduces memory usage by 4×
 * compared to float32 storage while maintaining the same API.</p>
 *
 * <h3>Memory Layout (per vector)</h3>
 * <pre>
 *   [byte × dimensions]  — quantized vector data
 * </pre>
 *
 * <p>The quantizer's min/max/scale arrays are held separately (small, ~dims × 4 × 3 bytes).</p>
 *
 * <h3>Thread Safety</h3>
 * <ul>
 *   <li>Concurrent reads are safe (shared arena).</li>
 *   <li>Writes are serialized via {@link ReentrantLock}.</li>
 * </ul>
 */
public class QuantizedVectorStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(QuantizedVectorStore.class);

    private final int dimensions;
    private final int capacity;
    private final ScalarQuantizer quantizer;
    private final Arena arena;
    private final MemorySegment segment;
    private final Map<String, Integer> idToIndex;
    private final AtomicInteger count;
    private final ReentrantLock writeLock = new ReentrantLock();
    private volatile boolean closed;

    /**
     * Creates a quantized vector store.
     *
     * @param dimensions vector dimensionality
     * @param capacity   max number of vectors
     * @param quantizer  the scalar quantizer (must be calibrated)
     */
    public QuantizedVectorStore(int dimensions, int capacity, ScalarQuantizer quantizer) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        if (quantizer.dimensions() != dimensions) {
            throw new IllegalArgumentException("Quantizer dims " + quantizer.dimensions()
                    + " != store dims " + dimensions);
        }

        this.dimensions = dimensions;
        this.capacity = capacity;
        this.quantizer = quantizer;
        this.arena = Arena.ofShared();
        // Each vector: dims bytes
        long totalBytes = (long) capacity * dimensions;
        this.segment = arena.allocate(totalBytes, ValueLayout.JAVA_BYTE.byteAlignment());
        this.idToIndex = new ConcurrentHashMap<>(capacity);
        this.count = new AtomicInteger(0);
        this.closed = false;

        log.info("QuantizedVectorStore created: dims={}, capacity={}, bytes={} ({}× smaller than float32)",
                dimensions, capacity, totalBytes, 4);
    }

    /**
     * Stores a float vector, quantizing it internally.
     *
     * @param id     vector identifier
     * @param vector float32 vector (will be quantized)
     * @return internal index
     */
    public int put(String id, float[] vector) {
        writeLock.lock();
        try {
            ensureOpen();
            if (vector.length != dimensions) {
                throw new IllegalArgumentException(
                        "Expected " + dimensions + " dims, got " + vector.length);
            }

            Integer existing = idToIndex.get(id);
            if (existing != null) {
                writeQuantized(existing, vector);
                return existing;
            }

            int index = count.getAndIncrement();
            if (index >= capacity) {
                count.decrementAndGet();
                throw new IllegalStateException("Store is full: capacity=" + capacity);
            }

            writeQuantized(index, vector);
            idToIndex.put(id, index);
            return index;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Returns the quantized bytes for the given index.
     *
     * @param index internal vector index
     * @return quantized byte array
     */
    public byte[] getQuantized(int index) {
        ensureOpen();
        validateIndex(index);
        byte[] result = new byte[dimensions];
        long offset = (long) index * dimensions;
        MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, offset, result, 0, dimensions);
        return result;
    }

    /**
     * Returns a dequantized float vector (approximate reconstruction).
     *
     * @param index internal vector index
     * @return dequantized float array
     */
    public float[] getFloat(int index) {
        byte[] quantized = getQuantized(index);
        return quantizer.decode(quantized);
    }

    /**
     * Reads quantized bytes directly into a buffer (zero-copy from segment).
     *
     * @param index     internal vector index
     * @param dst       destination byte array
     * @param dstOffset offset into destination
     */
    public void getQuantized(int index, byte[] dst, int dstOffset) {
        ensureOpen();
        validateIndex(index);
        long offset = (long) index * dimensions;
        MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, offset, dst, dstOffset, dimensions);
    }

    /** Returns the index for a given ID, or -1. */
    public int indexOf(String id) {
        Integer index = idToIndex.get(id);
        return index == null ? -1 : index;
    }

    /** Returns the number of vectors stored. */
    public int size() { return count.get(); }

    /** Returns the dimensionality. */
    public int dimensions() { return dimensions; }

    /** Returns the capacity. */
    public int capacity() { return capacity; }

    /** Returns the quantizer used. */
    public ScalarQuantizer quantizer() { return quantizer; }

    /** Returns true if closed. */
    public boolean isClosed() { return closed; }

    @Override
    public void close() {
        writeLock.lock();
        try {
            if (!closed) {
                closed = true;
                arena.close();
                log.info("QuantizedVectorStore closed: released {} vectors", count.get());
            }
        } finally {
            writeLock.unlock();
        }
    }

    // ─────────────── Internals ───────────────

    private void writeQuantized(int index, float[] vector) {
        byte[] quantized = quantizer.encode(vector);
        long offset = (long) index * dimensions;
        MemorySegment.copy(quantized, 0, segment, ValueLayout.JAVA_BYTE, offset, dimensions);
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("QuantizedVectorStore is closed");
    }

    private void validateIndex(int index) {
        if (index < 0 || index >= count.get()) {
            throw new IndexOutOfBoundsException("index=" + index + ", size=" + count.get());
        }
    }
}
