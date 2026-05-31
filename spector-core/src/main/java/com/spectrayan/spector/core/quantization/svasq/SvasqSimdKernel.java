package com.spectrayan.spector.core.quantization.vasq;

import com.spectrayan.spector.core.simd.SimdCapability;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorSpecies;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * SIMD-accelerated VASQ distance kernel using Java Panama Vector API.
 *
 * <h3>The Hot Loop (L2 Distance)</h3>
 * <pre>
 *   For each pair of {@code vecLen} blocks:
 *     z0 = castShape(loadBytes(segment, offset + i))       // INT8 → float32 block 0
 *     z1 = castShape(loadBytes(segment, offset + i + vl))  // INT8 → float32 block 1
 *     acc0 += z0 × qTilde[i]                               // FMA block 0
 *     acc1 += z1 × qTilde[i + vl]                          // FMA block 1 (ILP)
 *   dot = reduceLanes(acc0 + acc1)
 *   L2  = exactNormSq + constL2Q - 2 × dot
 * </pre>
 *
 * <h3>2× Loop Unrolling</h3>
 * <p>The inner loop processes two SIMD blocks per iteration instead of one.
 * This exposes instruction-level parallelism (ILP): while the CPU is doing the
 * FMA for block 0, it can simultaneously load/widen block 1. Typical gain:
 * 10–20% throughput on L2-resident data at D≥256.</p>
 *
 * <p>If {@code paddedDim / vecLen} is odd (i.e., there is one leftover block),
 * the residual block is handled by the cleanup loop. Since {@code paddedDim} is
 * always a power-of-two and {@code vecLen} is always a power-of-two, the only
 * case where the cleanup fires is when {@code paddedDim == vecLen} (e.g., AVX-512
 * with 16 lanes and paddedDim=16), which is extremely rare and still handled correctly.</p>
 *
 * <h3>Key Implementation Decisions</h3>
 * <ul>
 *   <li><strong>Species sizing:</strong> {@code B_SPECIES} has the same lane count as
 *       {@code F_SPECIES} (e.g., 8 bytes for AVX2's 8 floats). This avoids the
 *       4× throughput loss from using {@code SPECIES_256} bytes with 256-bit floats.</li>
 *   <li><strong>FMA:</strong> {@code zFloat.fma(qVec, acc)} explicitly requests a
 *       fused-multiply-add, avoiding the {@code add(mul())} pattern.</li>
 *   <li><strong>No tail loop:</strong> {@code paddedDim % vecLen == 0} is always true
 *       (both are powers-of-two), so the cleanup loop body never executes unless
 *       {@code paddedDim == vecLen}.</li>
 *   <li><strong>{@code reduceLanes} outside the loop:</strong> horizontal reduction is a
 *       single call after all FMA iterations, not per-iteration.</li>
 * </ul>
 *
 * <h3>Signed INT8 Widening</h3>
 * <p>{@code ByteVector.castShape(F_SPECIES, 0)} performs a <em>signed</em> widening
 * from INT8 to INT32 to float32, mapping to {@code vpmovsxbd} + {@code vcvtdq2ps}
 * on AVX2. This is correct for VASQ's signed [-127, 127] codes.</p>
 *
 * <p>All methods are stateless and safe for concurrent use.</p>
 */
public final class VasqSimdKernel {

    // Preferred float species: AVX2 → 8 lanes (256-bit), AVX-512 → 16 lanes (512-bit)
    private static final VectorSpecies<Float> F_SPECIES = SimdCapability.PREFERRED_SPECIES;

    // Byte species with the SAME lane count as F_SPECIES.
    // VectorShape.forBitSize(length × 8): 8 lanes → 64-bit, 16 lanes → 128-bit.
    private static final VectorSpecies<Byte> B_SPECIES =
            VectorSpecies.of(byte.class,
                    VectorShape.forBitSize(F_SPECIES.length() * Byte.SIZE));

    /** Number of float lanes in one SIMD register. Pre-cached to avoid method call in hot loop. */
    private static final int VL = F_SPECIES.length();

    static {
        assert B_SPECIES.length() == F_SPECIES.length()
                : "B_SPECIES lanes must equal F_SPECIES lanes";
    }

    private VasqSimdKernel() {}

    /**
     * Computes the approximate squared L2 distance between a prepared query and an
     * encoded VASQ vector stored in a {@link MemorySegment}.
     *
     * <p>Formula: {@code L2 ≈ exactNormSq + constL2Q - 2 × Σᵢ(q̃ᵢ × zᵢ)}</p>
     *
     * <p>Uses a 2× unrolled inner loop to expose instruction-level parallelism.
     * Reads directly from off-heap memory with zero JVM GC allocations.</p>
     *
     * @param segment    off-heap memory segment containing the encoded vector database
     * @param offset     byte offset of the target vector's 4-byte norm header
     * @param paddedDim  padded dimensionality (must be power-of-two ≥ {@code F_SPECIES.length()})
     * @param qs         pre-prepared query state (from {@link VasqQueryPrep#prepare})
     * @return approximate squared L2 distance (non-negative)
     */
    public static float computeL2(MemorySegment segment, long offset,
                                   int paddedDim, VasqQueryState qs) {
        float exactNormSq = segment.get(ValueLayout.JAVA_FLOAT, offset);
        long  codeOffset  = offset + 4L;
        float[] qTilde    = qs.qTilde();

        FloatVector acc0 = FloatVector.zero(F_SPECIES);
        FloatVector acc1 = FloatVector.zero(F_SPECIES);

        // 2× unrolled SIMD FMA loop — processes 2 × VL dimensions per iteration
        int i = 0;
        int limit2 = paddedDim - VL; // last start index of a full 2× pair
        for (; i < limit2; i += VL * 2) {
            // Block 0
            ByteVector  zB0 = ByteVector.fromMemorySegment(
                    B_SPECIES, segment, codeOffset + i, ByteOrder.nativeOrder());
            FloatVector zF0 = (FloatVector) zB0.castShape(F_SPECIES, 0);
            FloatVector qV0 = FloatVector.fromArray(F_SPECIES, qTilde, i);
            acc0 = zF0.fma(qV0, acc0);

            // Block 1 — overlaps with block 0 in the CPU pipeline (ILP)
            ByteVector  zB1 = ByteVector.fromMemorySegment(
                    B_SPECIES, segment, codeOffset + i + VL, ByteOrder.nativeOrder());
            FloatVector zF1 = (FloatVector) zB1.castShape(F_SPECIES, 0);
            FloatVector qV1 = FloatVector.fromArray(F_SPECIES, qTilde, i + VL);
            acc1 = zF1.fma(qV1, acc1);
        }
        // Cleanup: 0 or 1 remaining block (only when paddedDim == VL)
        for (; i < paddedDim; i += VL) {
            ByteVector  zB = ByteVector.fromMemorySegment(
                    B_SPECIES, segment, codeOffset + i, ByteOrder.nativeOrder());
            FloatVector zF = (FloatVector) zB.castShape(F_SPECIES, 0);
            FloatVector qV = FloatVector.fromArray(F_SPECIES, qTilde, i);
            acc0 = zF.fma(qV, acc0);
        }

        // Single horizontal reduction — both accumulators combined before reduce
        float dot = acc0.add(acc1).reduceLanes(VectorOperators.ADD);

        // L2 = ‖x_exact‖² + (‖q‖² - 2·C(q)) - 2·Σ q̃ᵢzᵢ
        return exactNormSq + qs.constL2Q() - 2f * dot;
    }

    /**
     * Computes the approximate inner product between a prepared query and a VASQ vector.
     *
     * <p>Formula: {@code IP ≈ Σᵢ(q̃ᵢ × zᵢ) + C(q)}</p>
     *
     * <p>Uses the same 2× unrolled loop as {@link #computeL2} for symmetric throughput.</p>
     *
     * @param segment    off-heap memory segment
     * @param offset     byte offset of the target vector's norm header (4-byte prefix)
     * @param paddedDim  padded dimensionality
     * @param qs         pre-prepared query state
     * @return approximate inner product (asymmetric: query in float32, corpus in INT8)
     */
    public static float computeDot(MemorySegment segment, long offset,
                                    int paddedDim, VasqQueryState qs) {
        long    codeOffset = offset + 4L;
        float[] qTilde     = qs.qTilde();

        FloatVector acc0 = FloatVector.zero(F_SPECIES);
        FloatVector acc1 = FloatVector.zero(F_SPECIES);

        int i = 0;
        int limit2 = paddedDim - VL;
        for (; i < limit2; i += VL * 2) {
            ByteVector  zB0 = ByteVector.fromMemorySegment(
                    B_SPECIES, segment, codeOffset + i, ByteOrder.nativeOrder());
            FloatVector zF0 = (FloatVector) zB0.castShape(F_SPECIES, 0);
            acc0 = zF0.fma(FloatVector.fromArray(F_SPECIES, qTilde, i), acc0);

            ByteVector  zB1 = ByteVector.fromMemorySegment(
                    B_SPECIES, segment, codeOffset + i + VL, ByteOrder.nativeOrder());
            FloatVector zF1 = (FloatVector) zB1.castShape(F_SPECIES, 0);
            acc1 = zF1.fma(FloatVector.fromArray(F_SPECIES, qTilde, i + VL), acc1);
        }
        for (; i < paddedDim; i += VL) {
            ByteVector  zB = ByteVector.fromMemorySegment(
                    B_SPECIES, segment, codeOffset + i, ByteOrder.nativeOrder());
            FloatVector zF = (FloatVector) zB.castShape(F_SPECIES, 0);
            acc0 = zF.fma(FloatVector.fromArray(F_SPECIES, qTilde, i), acc0);
        }

        return acc0.add(acc1).reduceLanes(VectorOperators.ADD) + qs.dotOffset();
    }

    /**
     * Returns the number of float lanes in a SIMD register on this platform.
     *
     * @return lane count (e.g. 8 for AVX2, 16 for AVX-512)
     */
    public static int laneCount() {
        return VL;
    }

    /**
     * Returns the float vector species used by this kernel.
     *
     * @return float vector species
     */
    public static VectorSpecies<Float> floatSpecies() {
        return F_SPECIES;
    }

    /**
     * Returns the byte vector species used for INT8 loads.
     *
     * <p>The byte species always has the same number of lanes as the float species.</p>
     *
     * @return byte vector species
     */
    public static VectorSpecies<Byte> byteSpecies() {
        return B_SPECIES;
    }
}
