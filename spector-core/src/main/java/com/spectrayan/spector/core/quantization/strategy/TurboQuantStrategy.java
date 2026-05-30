package com.spectrayan.spector.core.quantization.strategy;
import com.spectrayan.spector.commons.error.SpectorException;

import com.spectrayan.spector.core.quantization.TurboQuantizer;
import com.spectrayan.spector.core.similarity.SimilarityFunction;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.commons.error.SpectorValidationException;

/**
 * Quantization strategy for TurboQuant (random-rotation + optimal scalar quantization).
 *
 * <p>Memory layout per vector: packed bytes per {@link TurboQuantizer#bytesPerVector()},
 * bit-width configurable (2/4/8 bits per dimension).</p>
 *
 * <h3>Distance computation</h3>
 * <p>Uses {@link TurboQuantizer#distanceFromRotatedQuery} with a pre-rotated query
 * (rotate-once, evaluate-N-times pattern via {@link DistanceContext.TurboContext}).
 * Supports L2 and dot product families.</p>
 */
final class TurboQuantStrategy implements QuantizationStrategy {

    private final TurboQuantizer quantizer;
    private final SimilarityFunction similarityFunction;
    private final int bpv;

    TurboQuantStrategy(TurboQuantizer quantizer, SimilarityFunction similarityFunction) {
        this.quantizer = quantizer;
        this.similarityFunction = similarityFunction;
        this.bpv = quantizer.bytesPerVector();
    }

    @Override
    public void encode(float[] vector, MemorySegment segment, long offset) {
        byte[] packed = quantizer.encodeToBytes(vector);
        MemorySegment.copy(packed, 0, segment, ValueLayout.JAVA_BYTE, offset, packed.length);
    }

    @Override
    public float[] decode(MemorySegment segment, long offset, int dimensions) {
        byte[] packed = new byte[bpv];
        MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, offset, packed, 0, bpv);
        return quantizer.decodeFromBytes(packed);
    }

    @Override
    public float distance(MemorySegment segment, long offset, DistanceContext ctx) {
        if (!(ctx instanceof DistanceContext.TurboContext tc)) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "context", "expected TurboContext but got " + ctx.getClass().getSimpleName());
        }
        byte[] packed = new byte[bpv];
        MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, offset, packed, 0, bpv);
        return quantizer.distanceFromRotatedQuery(tc.rotatedQuery(), packed);
    }

    @Override
    public DistanceContext prepareQueryContext(float[] query) {
        // Rotate query once; reuse across all candidates
        return new DistanceContext.TurboContext(quantizer.rotateQuery(query));
    }

    @Override
    public int bytesPerVector() {
        return bpv;
    }

    @Override
    public int compressionFactor(int dimensions) {
        return Math.max(1, (dimensions * 4) / bpv);
    }
}