package com.spectrayan.spector.core.quantization.strategy;
import com.spectrayan.spector.commons.error.SpectorException;

import com.spectrayan.spector.core.quantization.ScalarQuantizer;
import com.spectrayan.spector.core.similarity.SimilarityFunction;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.commons.error.SpectorValidationException;

/**
 * Quantization strategy for INT8 scalar quantization via {@link ScalarQuantizer}.
 *
 * <h3>Memory layout per vector</h3>
 * <pre>
 *   [unsigned byte × dimensions]
 * </pre>
 * One unsigned byte per dimension, linear min/max mapping calibrated by {@link ScalarQuantizer}.
 *
 * <h3>Zero-Copy Distance</h3>
 * <p>The {@link #distance} method passes the off-heap {@link MemorySegment} and offset
 * directly to {@link SimilarityFunction#computeQuantizedFromSegment} — no {@code byte[]}
 * intermediate allocation in the hot path. The encoded bytes are read from off-heap
 * memory inside the SIMD kernel.</p>
 */
final class Int8Strategy implements QuantizationStrategy {

    private final ScalarQuantizer quantizer;
    private final SimilarityFunction similarityFunction;
    private final int bpv; // bytes per vector = dimensions

    Int8Strategy(ScalarQuantizer quantizer, SimilarityFunction similarityFunction) {
        this.quantizer = quantizer;
        this.similarityFunction = similarityFunction;
        this.bpv = quantizer.dimensions();
    }

    @Override
    public void encode(float[] vector, MemorySegment segment, long offset) {
        byte[] encoded = quantizer.encode(vector);
        MemorySegment.copy(encoded, 0, segment, ValueLayout.JAVA_BYTE, offset, encoded.length);
    }

    @Override
    public float[] decode(MemorySegment segment, long offset, int dimensions) {
        // Read directly from segment — reconstruct float via dequantization
        float[] mins   = quantizer.mins();
        float[] scales = quantizer.scales();
        float[] result = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            int unsigned = segment.get(ValueLayout.JAVA_BYTE, offset + i) & 0xFF;
            result[i] = unsigned * scales[i] + mins[i];
        }
        return result;
    }

    /**
     * Computes INT8 asymmetric distance — <b>zero-copy hot path</b>.
     *
     * <p>Passes the off-heap segment and offset directly to
     * {@link SimilarityFunction#computeQuantizedFromSegment}. The bytes are read
     * inside the SIMD kernel without any intermediate {@code byte[]} allocation.</p>
     */
    @Override
    public float distance(MemorySegment segment, long offset, DistanceContext ctx) {
        if (!(ctx instanceof DistanceContext.Int8Context ic)) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "context", "expected Int8Context but got " + ctx.getClass().getSimpleName());
        }
        // Zero-copy: segment is passed directly to the kernel — no byte[] allocation
        return similarityFunction.computeQuantizedFromSegment(
                ic.query(), segment, offset, ic.mins(), ic.scales(), bpv);
    }

    @Override
    public DistanceContext prepareQueryContext(float[] query) {
        return new DistanceContext.Int8Context(query, quantizer.mins(), quantizer.scales());
    }

    @Override
    public int bytesPerVector() {
        return bpv;
    }

    @Override
    public int compressionFactor(int dimensions) {
        return 4; // float32 (4 bytes) → INT8 (1 byte)
    }
}