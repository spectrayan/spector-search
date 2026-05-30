package com.spectrayan.spector.core.quantization.strategy;
import com.spectrayan.spector.commons.error.SpectorException;

import com.spectrayan.spector.core.quantization.CrumbPacker;
import com.spectrayan.spector.core.quantization.NonUniformQuantizer;
import com.spectrayan.spector.core.similarity.PackedDotProduct;
import com.spectrayan.spector.core.similarity.SimilarityFunction;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.commons.error.SpectorValidationException;

/**
 * Quantization strategy for 2-bit crumb-packed quantization via {@link NonUniformQuantizer}.
 *
 * <h3>Memory layout per vector</h3>
 * <pre>
 *   [byte × ceil(dimensions/4)]  — four 2-bit levels packed per byte (bits 7-6, 5-4, 3-2, 1-0)
 * </pre>
 *
 * <h3>Zero-Copy Distance</h3>
 * <p>The {@link #distance} method passes the off-heap {@link MemorySegment} and offset
 * directly to {@link PackedDotProduct#computeInt2(float[], MemorySegment, long, float[], int)}.
 * Crumbs are unpacked and centroids looked up inside the SIMD kernel — no intermediate
 * {@code byte[]} copy in the hot path.</p>
 */
final class Int2Strategy implements QuantizationStrategy {

    private final NonUniformQuantizer quantizer;
    private final SimilarityFunction similarityFunction;
    private final float[] globalCentroids;
    private final int bpv;

    Int2Strategy(NonUniformQuantizer quantizer, SimilarityFunction similarityFunction,
                 float[] globalCentroids) {
        this.quantizer = quantizer;
        this.similarityFunction = similarityFunction;
        this.globalCentroids = globalCentroids;
        this.bpv = (quantizer.dimensions() + 3) / 4; // ceil(D/4)
    }

    @Override
    public void encode(float[] vector, MemorySegment segment, long offset) {
        int[] levels = quantizer.encode(vector);
        byte[] packed = CrumbPacker.pack(levels, quantizer.dimensions());
        // Write packed bytes into off-heap segment
        MemorySegment.copy(packed, 0, segment, ValueLayout.JAVA_BYTE, offset, packed.length);
    }

    @Override
    public float[] decode(MemorySegment segment, long offset, int dimensions) {
        // Read crumbs directly from the off-heap segment
        byte[] packed = new byte[bpv];
        MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, offset, packed, 0, bpv);
        int[] levels = CrumbPacker.unpack(packed, dimensions);
        return quantizer.decode(levels);
    }

    /**
     * Computes INT2 asymmetric dot product — <b>zero-copy hot path</b>.
     *
     * <p>Passes the off-heap segment and offset directly to
     * {@link PackedDotProduct#computeInt2(float[], MemorySegment, long, float[], int)}.
     * No {@code byte[]} is allocated — crumbs are unpacked inside the SIMD kernel
     * reading directly from off-heap memory.</p>
     */
    @Override
    public float distance(MemorySegment segment, long offset, DistanceContext ctx) {
        if (!(ctx instanceof DistanceContext.PackedContext pc)) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "context", "expected PackedContext but got " + ctx.getClass().getSimpleName());
        }
        // Zero-copy: segment passed directly to the kernel — no byte[] allocation
        float dot = PackedDotProduct.computeInt2(
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
        return 16; // float32 (4 bytes) → INT2 (0.25 bytes)
    }
}