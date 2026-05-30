package com.spectrayan.spector.storage;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Defines the memory layout for contiguous vector storage using Panama's
 * {@link MemoryLayout} API.
 *
 * <p>Vectors are stored as a flat sequence of floats in off-heap memory.
 * Each vector occupies {@code dimensions} consecutive floats. The layout
 * enables {@link VarHandle}-based access that the JIT can optimize to
 * single MOV instructions.</p>
 *
 * <h3>Memory Layout</h3>
 * <pre>
 *   [vector_0: float × D] [vector_1: float × D] ... [vector_N: float × D]
 * </pre>
 *
 * @param dimensions the number of float elements per vector
 */
public record VectorStoreLayout(int dimensions) {

    /** Size of a single float element in bytes. */
    public static final long FLOAT_BYTES = ValueLayout.JAVA_FLOAT.byteSize();

    public VectorStoreLayout {
        if (dimensions <= 0) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "dimensions", 1, Integer.MAX_VALUE, dimensions);
        }
    }

    /**
     * Returns the byte size of a single vector.
     *
     * @return vector size in bytes
     */
    public long vectorByteSize() {
        return (long) dimensions * FLOAT_BYTES;
    }

    /**
     * Returns the byte offset of the vector at the given index.
     *
     * @param vectorIndex zero-based vector index
     * @return byte offset from segment start
     */
    public long vectorOffset(int vectorIndex) {
        return (long) vectorIndex * vectorByteSize();
    }

    /**
     * Returns the byte offset of a specific float element within a vector.
     *
     * @param vectorIndex zero-based vector index
     * @param elementIndex zero-based element index within the vector
     * @return byte offset from segment start
     */
    public long elementOffset(int vectorIndex, int elementIndex) {
        return vectorOffset(vectorIndex) + (long) elementIndex * FLOAT_BYTES;
    }

    /**
     * Returns the total byte size needed to store {@code count} vectors.
     *
     * @param count number of vectors
     * @return total byte size
     */
    public long totalByteSize(int count) {
        return (long) count * vectorByteSize();
    }

    /**
     * Writes a float array into the segment at the given vector index.
     *
     * @param segment the memory segment
     * @param vectorIndex the vector slot index
     * @param vector the float array to write (must have length == dimensions)
     */
    public void writeVector(MemorySegment segment, int vectorIndex, float[] vector) {
        if (vector.length != dimensions) {
            throw new SpectorValidationException(ErrorCode.DIMENSIONS_MISMATCH, dimensions, vector.length);
        }
        long offset = vectorOffset(vectorIndex);
        MemorySegment.copy(vector, 0, segment, ValueLayout.JAVA_FLOAT, offset, dimensions);
    }

    /**
     * Reads a float array from the segment at the given vector index.
     *
     * @param segment the memory segment
     * @param vectorIndex the vector slot index
     * @return a new float array containing the vector data
     */
    public float[] readVector(MemorySegment segment, int vectorIndex) {
        float[] result = new float[dimensions];
        long offset = vectorOffset(vectorIndex);
        MemorySegment.copy(segment, ValueLayout.JAVA_FLOAT, offset, result, 0, dimensions);
        return result;
    }

    /**
     * Reads a float array from the segment at the given vector index into an existing buffer.
     *
     * @param segment the memory segment
     * @param vectorIndex the vector slot index
     * @param dst destination array
     * @param dstOffset offset into destination
     */
    public void readVector(MemorySegment segment, int vectorIndex, float[] dst, int dstOffset) {
        long offset = vectorOffset(vectorIndex);
        MemorySegment.copy(segment, ValueLayout.JAVA_FLOAT, offset, dst, dstOffset, dimensions);
    }
}
