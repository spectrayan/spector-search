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

import com.spectrayan.spector.core.CrumbPacker;
import com.spectrayan.spector.core.NibblePacker;
import com.spectrayan.spector.core.NonUniformQuantizer;
import com.spectrayan.spector.core.QuantizationType;
import com.spectrayan.spector.core.ScalarQuantizer;
import com.spectrayan.spector.core.TurboQuantizer;

/**
 * Off-heap vector store that stores quantized vectors via Panama {@link MemorySegment}.
 *
 * <p>Supports multiple quantization types:</p>
 * <ul>
 *   <li><b>INT8</b> — one byte per dimension, using linear {@link ScalarQuantizer}</li>
 *   <li><b>INT4</b> — nibble-packed (2 values/byte), using {@link NonUniformQuantizer} + {@link NibblePacker}</li>
 *   <li><b>INT2</b> — crumb-packed (4 values/byte), using {@link NonUniformQuantizer} + {@link CrumbPacker}</li>
 * </ul>
 *
 * <h3>Memory Layout (per vector)</h3>
 * <pre>
 *   INT8: [byte × dimensions]
 *   INT4: [byte × ceil(dimensions/2)]
 *   INT2: [byte × ceil(dimensions/4)]
 * </pre>
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
    private final QuantizationType quantizationType;
    private final int bytesPerVector;
    private final ScalarQuantizer quantizer;            // used for INT8
    private final NonUniformQuantizer nonUniformQuantizer; // used for INT4/INT2
    private final TurboQuantizer turboQuantizer;        // used for TURBO_QUANT
    private final Arena arena;
    private final MemorySegment segment;
    private final Map<String, Integer> idToIndex;
    private final AtomicInteger count;
    private final ReentrantLock writeLock = new ReentrantLock();
    private volatile boolean closed;

    /**
     * Creates a quantized vector store for INT8 (backward-compatible constructor).
     *
     * @param dimensions vector dimensionality
     * @param capacity   max number of vectors
     * @param quantizer  the scalar quantizer (must be calibrated)
     */
    public QuantizedVectorStore(int dimensions, int capacity, ScalarQuantizer quantizer) {
        this(dimensions, capacity, QuantizationType.SCALAR_INT8, quantizer, null, null);
    }

    /**
     * Creates a quantized vector store for TurboQuant.
     *
     * @param dimensions      vector dimensionality
     * @param capacity        max number of vectors
     * @param turboQuantizer  the calibrated TurboQuantizer
     */
    public QuantizedVectorStore(int dimensions, int capacity, TurboQuantizer turboQuantizer) {
        this(dimensions, capacity, QuantizationType.TURBO_QUANT, null, null, turboQuantizer);
    }

    /**
     * Creates a quantized vector store with a specified quantization type (backward-compatible).
     *
     * @param dimensions          vector dimensionality
     * @param capacity            max number of vectors
     * @param quantizationType    the quantization type
     * @param quantizer           the scalar quantizer for INT8
     * @param nonUniformQuantizer the non-uniform quantizer for INT4/INT2
     */
    public QuantizedVectorStore(int dimensions, int capacity, QuantizationType quantizationType,
                                 ScalarQuantizer quantizer, NonUniformQuantizer nonUniformQuantizer) {
        this(dimensions, capacity, quantizationType, quantizer, nonUniformQuantizer, null);
    }

    /**
     * Creates a quantized vector store with a specified quantization type.
     *
     * <p>For INT8, a {@link ScalarQuantizer} is required. For INT4 and INT2, a
     * {@link NonUniformQuantizer} is required. For TURBO_QUANT, a {@link TurboQuantizer}
     * is required.</p>
     *
     * @param dimensions          vector dimensionality
     * @param capacity            max number of vectors
     * @param quantizationType    the quantization type
     * @param quantizer           the scalar quantizer for INT8 (may be null if not INT8)
     * @param nonUniformQuantizer the non-uniform quantizer for INT4/INT2 (may be null if not INT4/INT2)
     * @param turboQuantizer      the TurboQuantizer (may be null if not TURBO_QUANT)
     * @throws IllegalArgumentException if capacity is not positive, or if required quantizer is missing
     */
    public QuantizedVectorStore(int dimensions, int capacity, QuantizationType quantizationType,
                                 ScalarQuantizer quantizer, NonUniformQuantizer nonUniformQuantizer,
                                 TurboQuantizer turboQuantizer) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        if (quantizationType == null) throw new IllegalArgumentException("quantizationType must not be null");

        switch (quantizationType) {
            case SCALAR_INT8 -> {
                if (quantizer == null) {
                    throw new IllegalArgumentException("ScalarQuantizer is required for INT8");
                }
                if (quantizer.dimensions() != dimensions) {
                    throw new IllegalArgumentException("Quantizer dims " + quantizer.dimensions()
                            + " != store dims " + dimensions);
                }
            }
            case SCALAR_INT4, SCALAR_INT2 -> {
                if (nonUniformQuantizer == null) {
                    throw new IllegalArgumentException("NonUniformQuantizer is required for " + quantizationType);
                }
                if (nonUniformQuantizer.dimensions() != dimensions) {
                    throw new IllegalArgumentException("NonUniformQuantizer dims " + nonUniformQuantizer.dimensions()
                            + " != store dims " + dimensions);
                }
                int expectedLevels = quantizationType.levels();
                if (nonUniformQuantizer.levels() != expectedLevels) {
                    throw new IllegalArgumentException("NonUniformQuantizer levels " + nonUniformQuantizer.levels()
                            + " != expected levels " + expectedLevels + " for " + quantizationType);
                }
            }
            case TURBO_QUANT -> {
                if (turboQuantizer == null) {
                    throw new IllegalArgumentException("TurboQuantizer is required for TURBO_QUANT");
                }
                if (turboQuantizer.dimensions() != dimensions) {
                    throw new IllegalArgumentException("TurboQuantizer dims " + turboQuantizer.dimensions()
                            + " != store dims " + dimensions);
                }
            }
            default -> throw new IllegalArgumentException("Unsupported quantization type: " + quantizationType);
        }

        this.dimensions = dimensions;
        this.capacity = capacity;
        this.quantizationType = quantizationType;
        this.quantizer = quantizer;
        this.nonUniformQuantizer = nonUniformQuantizer;
        this.turboQuantizer = turboQuantizer;
        this.bytesPerVector = quantizationType.bytesPerVector(dimensions);
        this.arena = Arena.ofShared();

        long totalBytes = (long) capacity * bytesPerVector;
        this.segment = arena.allocate(totalBytes, ValueLayout.JAVA_BYTE.byteAlignment());
        this.idToIndex = new ConcurrentHashMap<>(capacity);
        this.count = new AtomicInteger(0);
        this.closed = false;

        int compressionFactor = switch (quantizationType) {
            case SCALAR_INT8 -> 4;
            case SCALAR_INT4, TURBO_QUANT -> 8;
            case SCALAR_INT2 -> 16;
            default -> 1;
        };

        log.info("QuantizedVectorStore created: dims={}, capacity={}, type={}, bytesPerVector={}, totalBytes={} ({}× smaller than float32)",
                dimensions, capacity, quantizationType, bytesPerVector, totalBytes, compressionFactor);
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
     * @return quantized byte array (packed for INT4/INT2)
     */
    public byte[] getQuantized(int index) {
        ensureOpen();
        validateIndex(index);
        byte[] result = new byte[bytesPerVector];
        long offset = (long) index * bytesPerVector;
        MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, offset, result, 0, bytesPerVector);
        return result;
    }

    /**
     * Returns a dequantized float vector (approximate reconstruction).
     *
     * @param index internal vector index
     * @return dequantized float array
     */
    public float[] getFloat(int index) {
        byte[] packed = getQuantized(index);
        return switch (quantizationType) {
            case SCALAR_INT8 -> quantizer.decode(packed);
            case SCALAR_INT4 -> {
                int[] levels = NibblePacker.unpack(packed, dimensions);
                yield nonUniformQuantizer.decode(levels);
            }
            case SCALAR_INT2 -> {
                int[] levels = CrumbPacker.unpack(packed, dimensions);
                yield nonUniformQuantizer.decode(levels);
            }
            case TURBO_QUANT -> turboQuantizer.decodeFromBytes(packed);
            default -> throw new IllegalStateException("Unsupported type: " + quantizationType);
        };
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
        long offset = (long) index * bytesPerVector;
        MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, offset, dst, dstOffset, bytesPerVector);
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

    /** Returns the quantization type. */
    public QuantizationType quantizationType() { return quantizationType; }

    /** Returns the number of bytes stored per vector. */
    public int bytesPerVector() { return bytesPerVector; }

    /** Returns the scalar quantizer (INT8 path), or null if not INT8. */
    public ScalarQuantizer quantizer() { return quantizer; }

    /** Returns the non-uniform quantizer (INT4/INT2 path), or null if INT8. */
    public NonUniformQuantizer nonUniformQuantizer() { return nonUniformQuantizer; }

    /** Returns the TurboQuantizer (TURBO_QUANT path), or null if not TurboQuant. */
    public TurboQuantizer turboQuantizer() { return turboQuantizer; }

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
        byte[] packed = switch (quantizationType) {
            case SCALAR_INT8 -> quantizer.encode(vector);
            case SCALAR_INT4 -> {
                int[] levels = nonUniformQuantizer.encode(vector);
                yield NibblePacker.pack(levels, dimensions);
            }
            case SCALAR_INT2 -> {
                int[] levels = nonUniformQuantizer.encode(vector);
                yield CrumbPacker.pack(levels, dimensions);
            }
            case TURBO_QUANT -> turboQuantizer.encodeToBytes(vector);
            default -> throw new IllegalStateException("Unsupported type: " + quantizationType);
        };
        long offset = (long) index * bytesPerVector;
        MemorySegment.copy(packed, 0, segment, ValueLayout.JAVA_BYTE, offset, bytesPerVector);
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
