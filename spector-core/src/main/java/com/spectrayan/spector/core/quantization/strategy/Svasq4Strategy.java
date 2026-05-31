package com.spectrayan.spector.core.quantization.strategy;
import com.spectrayan.spector.commons.error.SpectorException;

import com.spectrayan.spector.core.quantization.vasq.Vasq4Encoder;
import com.spectrayan.spector.core.quantization.vasq.Vasq4QueryPrep;
import com.spectrayan.spector.core.quantization.vasq.VasqParams;
import com.spectrayan.spector.core.similarity.SimilarityFunction;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.commons.error.SpectorValidationException;

/**
 * Quantization strategy for VASQ-4 (FWHT-rotated asymmetric INT4 quantization).
 *
 * <h3>Memory layout per vector</h3>
 * <pre>
 *   [float32 exactNormSq (4 bytes)] [nibble-packed INT4 × paddedDim/2 bytes]
 * </pre>
 *
 * <h3>Distance computation</h3>
 * <p>{@link #prepareQueryContext} applies FWHT rotation, pre-scaling, deinterleaving,
 * and offset-bias folding <em>once per query</em>. The resulting
 * {@link DistanceContext.Vasq4Ctx} is reused for every candidate via
 * {@link SimilarityFunction#computeVasq4}, which dispatches to the Panama SIMD kernel
 * ({@link com.spectrayan.spector.core.quantization.vasq.Vasq4SimdKernel}) with zero
 * heap allocations on the hot path.</p>
 *
 * <h3>Thread safety</h3>
 * <p>This class is immutable after construction. The {@link DistanceContext.Vasq4Ctx}
 * returned by {@link #prepareQueryContext} is a per-call value that must not be
 * shared across concurrent searches.</p>
 */
public final class Vasq4Strategy implements QuantizationStrategy {

    private final Vasq4Encoder encoder;
    private final Vasq4QueryPrep queryPrep;
    private final SimilarityFunction similarityFunction;
    private final int bpv;
    private final int halfDim;

    /**
     * Creates a VASQ-4 strategy from pre-calibrated 4-bit parameters.
     *
     * @param params             calibrated VASQ parameters with bitWidth=4
     * @param similarityFunction distance metric (EUCLIDEAN → L2, COSINE/DOT → dot)
     */
    public Vasq4Strategy(VasqParams params, SimilarityFunction similarityFunction) {
        this.encoder = new Vasq4Encoder(params);
        this.queryPrep = new Vasq4QueryPrep(params);
        this.similarityFunction = similarityFunction;
        this.bpv = params.bytesPerVector();
        this.halfDim = params.paddedDim() / 2;
    }

    /**
     * Creates a VASQ-4 strategy from a pre-built encoder.
     *
     * @param encoder            pre-built VASQ-4 encoder (non-null)
     * @param similarityFunction distance metric
     */
    public Vasq4Strategy(Vasq4Encoder encoder, SimilarityFunction similarityFunction) {
        this.encoder = encoder;
        this.queryPrep = new Vasq4QueryPrep(encoder.params());
        this.similarityFunction = similarityFunction;
        this.bpv = encoder.bytesPerVector();
        this.halfDim = encoder.params().paddedDim() / 2;
    }

    /**
     * Encodes a float32 vector directly into the off-heap segment.
     *
     * <p>Applies FWHT rotation, INT4 quantization, offset encoding, and writes
     * the 4-byte norm header + nibble-packed codes — zero heap allocation.</p>
     */
    @Override
    public void encode(float[] vector, MemorySegment segment, long offset) {
        encoder.encode(vector, segment, offset);
    }

    /**
     * Decodes an approximation of the original vector from the off-heap segment.
     *
     * <p>Reads the nibble-packed codes, reverses offset encoding, and reconstructs via
     * {@code x̂ᵢ ≈ (uᵢ − 7) × scaleᵢ + μᵢ}.</p>
     */
    @Override
    public float[] decode(MemorySegment segment, long offset, int dimensions) {
        return encoder.decode(segment, offset, dimensions);
    }

    /**
     * Computes VASQ-4 distance between a stored (quantized) candidate and the
     * pre-prepared query state.
     *
     * <p>Delegates to {@link SimilarityFunction#computeVasq4} which dispatches to
     * the Panama SIMD kernel — reading directly from off-heap memory, zero GC pressure.</p>
     */
    @Override
    public float distance(MemorySegment segment, long offset, DistanceContext ctx) {
        if (!(ctx instanceof DistanceContext.Vasq4Ctx vc)) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "Expected Vasq4Ctx, got: " + ctx.getClass().getSimpleName());
        }
        return similarityFunction.computeVasq4(segment, offset, vc.halfDim(), vc.state());
    }

    /**
     * Prepares a per-query {@link DistanceContext.Vasq4Ctx} by applying FWHT rotation,
     * pre-scaling, deinterleaving, and offset-bias folding.
     *
     * <p>This is the O(D log D) step. Call it <em>once per search</em>, then reuse
     * the returned context for every candidate's {@link #distance} call.</p>
     */
    @Override
    public DistanceContext prepareQueryContext(float[] query) {
        return new DistanceContext.Vasq4Ctx(queryPrep.prepare(query), halfDim);
    }

    /** Returns the bytes per VASQ-4 encoded vector (4-byte header + paddedDim/2 nibble-packed codes). */
    @Override
    public int bytesPerVector() {
        return bpv;
    }

    @Override
    public int compressionFactor(int dimensions) {
        return Math.max(1, (dimensions * 4) / bpv);
    }

    /** Returns the backing VASQ-4 encoder. */
    public Vasq4Encoder encoder() {
        return encoder;
    }
}