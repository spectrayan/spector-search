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

import com.spectrayan.spector.core.quantization.NonUniformQuantizer;
import com.spectrayan.spector.core.quantization.QuantizationType;
import com.spectrayan.spector.core.quantization.ScalarQuantizer;
import com.spectrayan.spector.core.quantization.TurboQuantizer;
import com.spectrayan.spector.core.quantization.strategy.DistanceContext;
import com.spectrayan.spector.core.quantization.strategy.QuantizationStrategy;
import com.spectrayan.spector.core.quantization.strategy.QuantizationStrategyFactory;
import com.spectrayan.spector.core.quantization.vasq.VasqEncoder;
import com.spectrayan.spector.core.quantization.vasq.VasqParams;
import com.spectrayan.spector.core.similarity.SimilarityFunction;

/**
 * Off-heap vector store that stores quantized vectors via Panama {@link MemorySegment}.
 *
 * <p>Supports multiple quantization types via the {@link QuantizationStrategy} SPI:</p>
 * <ul>
 *   <li><b>INT8</b> — one byte per dimension, using linear {@link ScalarQuantizer}</li>
 *   <li><b>INT4</b> — nibble-packed (2 values/byte), using {@link NonUniformQuantizer}</li>
 *   <li><b>INT2</b> — crumb-packed (4 values/byte), using {@link NonUniformQuantizer}</li>
 *   <li><b>VASQ</b> — FWHT-rotated affine INT8 with exact-norm header, using {@link VasqEncoder}</li>
 * </ul>
 *
 * <h3>Design</h3>
 * <p>All quantization-specific logic is delegated to the {@link QuantizationStrategy} instance
 * created by {@link QuantizationStrategyFactory}. Adding a new quantization type requires
 * only a new strategy class — this class does not change (Open/Closed Principle).</p>
 *
 * <h3>Memory Layout (per vector)</h3>
 * <pre>
 *   INT8:      [byte × dimensions]
 *   INT4:      [byte × ceil(dimensions/2)]
 *   INT2:      [byte × ceil(dimensions/4)]
 *   VASQ:      [float32 exactNormSq (4 bytes)] [INT8 × paddedDim]
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

    /** Strategy encapsulating all quantization-type-specific encode/decode/distance logic. */
    private final QuantizationStrategy strategy;

    // Retained for backward-compat accessors and for VASQ zero-copy access from index layer
    private final ScalarQuantizer quantizer;
    private final NonUniformQuantizer nonUniformQuantizer;
    private final TurboQuantizer turboQuantizer;
    private final VasqEncoder vasqEncoder;

    private final Arena arena;
    private final MemorySegment segment;
    private final Map<String, Integer> idToIndex;
    private final AtomicInteger count;
    private final ReentrantLock writeLock = new ReentrantLock();
    private volatile boolean closed;

    // ─────────────── Constructors ───────────────

    /**
     * Creates a quantized vector store for INT8 (backward-compatible constructor).
     *
     * @param dimensions vector dimensionality
     * @param capacity   max number of vectors
     * @param quantizer  the scalar quantizer (must be calibrated)
     */
    public QuantizedVectorStore(int dimensions, int capacity, ScalarQuantizer quantizer) {
        this(dimensions, capacity, QuantizationType.SCALAR_INT8, quantizer, null, null, null,
                SimilarityFunction.COSINE);
    }

    /**
     * Creates a quantized vector store for TurboQuant.
     *
     * @param dimensions      vector dimensionality
     * @param capacity        max number of vectors
     * @param turboQuantizer  the calibrated TurboQuantizer
     */
    public QuantizedVectorStore(int dimensions, int capacity, TurboQuantizer turboQuantizer) {
        this(dimensions, capacity, QuantizationType.TURBO_QUANT, null, null, turboQuantizer, null,
                SimilarityFunction.COSINE);
    }

    /**
     * Creates a VASQ-mode vector store.
     *
     * <p>Vectors are stored as: {@code [4b float32 exactNormSq][paddedDim × signed INT8]}.</p>
     *
     * @param dimensions  vector dimensionality
     * @param capacity    max number of vectors
     * @param vasqParams  calibrated VASQ parameters
     */
    public QuantizedVectorStore(int dimensions, int capacity, VasqParams vasqParams) {
        this(dimensions, capacity, QuantizationType.VASQ, null, null, null,
                new VasqEncoder(vasqParams), SimilarityFunction.COSINE);
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
        this(dimensions, capacity, quantizationType, quantizer, nonUniformQuantizer, null, null,
                SimilarityFunction.COSINE);
    }

    /**
     * Full constructor — creates a quantized vector store with any quantization type.
     *
     * <p>For INT8, a {@link ScalarQuantizer} is required. For INT4 and INT2, a
     * {@link NonUniformQuantizer} is required. For TURBO_QUANT, a {@link TurboQuantizer}
     * is required. For VASQ, a {@link VasqEncoder} is required.</p>
     *
     * @param dimensions          vector dimensionality
     * @param capacity            max number of vectors
     * @param quantizationType    the quantization type
     * @param quantizer           the scalar quantizer for INT8 (may be null if not INT8)
     * @param nonUniformQuantizer the non-uniform quantizer for INT4/INT2 (may be null if not INT4/INT2)
     * @param turboQuantizer      the TurboQuantizer (may be null if not TURBO_QUANT)
     * @param vasqEncoder         the VASQ encoder (may be null if not VASQ)
     * @param similarityFunction  the similarity function used for distance context preparation
     * @throws IllegalArgumentException if capacity is not positive, or if required quantizer is missing
     */
    public QuantizedVectorStore(int dimensions, int capacity, QuantizationType quantizationType,
                                 ScalarQuantizer quantizer, NonUniformQuantizer nonUniformQuantizer,
                                 TurboQuantizer turboQuantizer, VasqEncoder vasqEncoder,
                                 SimilarityFunction similarityFunction) {
        if (capacity <= 0) throw new IllegalArgumentException(com.spectrayan.spector.commons.error.ErrorCode.DIMENSIONS_INVALID.format(0));
        if (quantizationType == null) throw new IllegalArgumentException(com.spectrayan.spector.commons.error.ErrorCode.ARGUMENT_NULL.format("quantizationType"));

        // Delegate validation + strategy creation to the Abstract Factory (with dimension checks)
        this.strategy = QuantizationStrategyFactory.createWithDimCheck(
                quantizationType, dimensions, quantizer, nonUniformQuantizer, turboQuantizer,
                vasqEncoder, similarityFunction);

        this.dimensions = dimensions;
        this.capacity = capacity;
        this.quantizationType = quantizationType;
        this.quantizer = quantizer;
        this.nonUniformQuantizer = nonUniformQuantizer;
        this.turboQuantizer = turboQuantizer;
        this.vasqEncoder = vasqEncoder;

        this.bytesPerVector = strategy.bytesPerVector();
        this.arena = Arena.ofShared();

        long totalBytes = (long) capacity * bytesPerVector;
        this.segment = arena.allocate(totalBytes, ValueLayout.JAVA_BYTE.byteAlignment());
        this.idToIndex = new ConcurrentHashMap<>(capacity);
        this.count = new AtomicInteger(0);
        this.closed = false;

        int compressionFactor = strategy.compressionFactor(dimensions);
        log.info("QuantizedVectorStore created: dims={}, capacity={}, type={}, bytesPerVector={}, totalBytes={} ({}× smaller than float32)",
                dimensions, capacity, quantizationType, bytesPerVector, totalBytes, compressionFactor);
    }

    /**
     * Backward-compatible 7-arg constructor (no similarity function — defaults to COSINE).
     *
     * @deprecated Use the 8-arg constructor with explicit {@link SimilarityFunction}.
     */
    @Deprecated
    public QuantizedVectorStore(int dimensions, int capacity, QuantizationType quantizationType,
                                 ScalarQuantizer quantizer, NonUniformQuantizer nonUniformQuantizer,
                                 TurboQuantizer turboQuantizer, VasqEncoder vasqEncoder) {
        this(dimensions, capacity, quantizationType, quantizer, nonUniformQuantizer,
                turboQuantizer, vasqEncoder, SimilarityFunction.COSINE);
    }

    // ─────────────── Write ───────────────

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

    // ─────────────── Read ───────────────

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
        ensureOpen();
        validateIndex(index);
        long offset = (long) index * bytesPerVector;
        return strategy.decode(segment, offset, dimensions);
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

    /**
     * Prepares a per-query {@link DistanceContext} for use with {@link #distance}.
     *
     * <p>Call this once per search; reuse the context for every candidate.</p>
     *
     * @param query float32 query vector
     * @return a per-query distance context
     */
    public DistanceContext prepareQueryContext(float[] query) {
        return strategy.prepareQueryContext(query);
    }

    /**
     * Computes quantized distance from a stored vector to the pre-prepared query context.
     *
     * @param index internal vector index
     * @param ctx   context from {@link #prepareQueryContext(float[])}
     * @return approximate distance
     */
    public float distance(int index, DistanceContext ctx) {
        ensureOpen();
        validateIndex(index);
        long offset = (long) index * bytesPerVector;
        return strategy.distance(segment, offset, ctx);
    }

    // ─────────────── Accessors ───────────────

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

    /** Returns the active {@link QuantizationStrategy} (useful for testing and inspection). */
    public QuantizationStrategy strategy() { return strategy; }

    /** Returns the scalar quantizer (INT8 path), or null if not INT8. */
    public ScalarQuantizer quantizer() { return quantizer; }

    /** Returns the non-uniform quantizer (INT4/INT2 path), or null if INT8. */
    public NonUniformQuantizer nonUniformQuantizer() { return nonUniformQuantizer; }

    /** Returns the TurboQuantizer (TURBO_QUANT path), or null if not TurboQuant. */
    public TurboQuantizer turboQuantizer() { return turboQuantizer; }

    /** Returns the VASQ encoder, or null if not VASQ mode. */
    public VasqEncoder vasqEncoder() { return vasqEncoder; }

    /**
     * Returns the underlying off-heap {@link MemorySegment}.
     *
     * <p>Used by the HNSW index layer to pass the segment directly to
     * {@link com.spectrayan.spector.core.quantization.vasq.VasqSimdKernel} without
     * copying — zero extra allocations in the hot path.</p>
     *
     * @return the off-heap segment
     */
    public MemorySegment segment() { return segment; }

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
        long offset = (long) index * bytesPerVector;
        strategy.encode(vector, segment, offset);
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException(com.spectrayan.spector.commons.error.ErrorCode.SEGMENT_CLOSED.format());
    }

    private void validateIndex(int index) {
        if (index < 0 || index >= count.get()) {
            throw new IndexOutOfBoundsException("index=" + index + ", size=" + count.get());
        }
    }
}
