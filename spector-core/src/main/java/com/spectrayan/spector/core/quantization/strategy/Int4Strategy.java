package com.spectrayan.spector.core.quantization.strategy;
import com.spectrayan.spector.commons.error.SpectorException;

import com.spectrayan.spector.core.quantization.NibblePacker;
import com.spectrayan.spector.core.quantization.NonUniformQuantizer;
import com.spectrayan.spector.core.similarity.PackedDotProduct;
import com.spectrayan.spector.core.similarity.SimilarityFunction;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.commons.error.SpectorValidationException;

/**
 * Quantization strategy for 4-bit nibble-packed quantization via {@link NonUniformQuantizer}.
 *
 * <h3>Memory layout per vector</h3>
 * <pre>
 *   [byte × ceil(dimensions/2)]  — two 4-bit levels packed per byte (high nibble first)
 * </pre>
 *
 * <h3>Zero-Copy Distance</h3>
 * <p>The {@link #distance} method passes the off-heap {@link MemorySegment} and offset
 * directly to {@link PackedDotProduct#computeInt4(float[], MemorySegment, long, float[], int)}.
 * Nibbles are unpacked and centroids looked up inside the SIMD kernel — no intermediate
 * {@code byte[]} copy in the hot path.</p>
 */
final class Int4Strategy implements QuantizationStrategy {

    private final NonUniformQuantizer quantizer;
    private final SimilarityFunction similarityFunction;
    private final float[] globalCentroids;
    private final int bpv;

    Int4Strategy(NonUniformQuantizer quantizer, SimilarityFunction similarityFunction,
                 float[] globalCentroids) {
        this.quantizer = quantizer;
        this.similarityFunction = similarityFunction;
        this.globalCentroids = globalCentroids;
        this.bpv = (quantizer.dimensions() + 1) / 2; // ceil(D/2)
    }

    @Override
    public void encode(float[] vector, MemorySegment segment, long offset) {
        int[] levels = quantizer.encode(vector);
        byte[] packed = NibblePacker.pack(levels, quantizer.dimensions());
        // Write packed bytes into off-heap segment
        MemorySegment.copy(packed, 0, segment, ValueLayout.JAVA_BYTE, offset, packed.length);
    }

    @Override
    public float[] decode(MemorySegment segment, long offset, int dimensions) {
        // Read nibbles directly from the off-heap segment
        byte[] packed = new byte[bpv];
        MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, offset, packed, 0, bpv);
        int[] levels = NibblePacker.unpack(packed, dimensions);
        return quantizer.decode(levels);
    }

    /**
     * Computes INT4 asymmetric dot product — <b>zero-copy hot path</b>.
     *
     * <p>Passes the off-heap segment and offset directly to
     * {@link PackedDotProduct#computeInt4(float[], MemorySegment, long, float[], int)}.
     * No {@code byte[]} is allocated — nibbles are unpacked inside the SIMD kernel
     * reading directly from off-heap memory.</p>
     */
    @Override
    public float distance(MemorySegment segment, long offset, DistanceContext ctx) {
        if (!(ctx instanceof DistanceContext.PackedContext pc)) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "context", "expected PackedContext but got " + ctx.getClass().getSimpleName());
        }
        // Zero-copy: segment passed directly to the kernel — no byte[] allocation
        float dot = PackedDotProduct.computeInt4(
                pc.query(), segment, offset, pc.globalCentroids(), pc.dimensions());
        return similarityFunction.higherIsBetter() ? dot : -dot;
    }

    @Override
    public DistanceContext prepareQueryContext(float[] query) {
        return new DistanceContext.PackedContext(query, globalCentroids, quantizer.dimensions());
    }

    @Override
    public int bytesPerVector() {
        return bpv;
    }

    @Override
    public int compressionFactor(int dimensions) {
        return 8; // float32 (4 bytes) → INT4 (0.5 bytes)
    }
}