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
 * SIMD-accelerated VASQ-4 distance kernel for nibble-packed INT4 codes.
 *
 * <h3>Nibble Packing Format</h3>
 * <p>Each stored byte contains two offset-encoded unsigned 4-bit values [0, 14]:</p>
 * <pre>
 *   byte[k] = (u_even &lt;&lt; 4) | (u_odd &amp; 0x0F)
 *   where u_even = dim[2k] + 7,  u_odd = dim[2k+1] + 7
 * </pre>
 *
 * <h3>The Hot Loop (L2 Distance)</h3>
 * <pre>
 *   For each VL-byte block at position i (processes 2×VL dimensions):
 *     packed = loadBytes(segment, codeOffset + i)        // VL bytes = 2×VL nibbles
 *     hi     = (packed &gt;&gt;&gt; 4) &amp; 0x0F                    // even dims [0, 14]
 *     lo     = packed &amp; 0x0F                             // odd dims  [0, 14]
 *     hiF    = castShape(hi)                             // unsigned widening → float32
 *     loF    = castShape(lo)                             // unsigned widening → float32
 *     accHi += hiF × qTildeHi[i]                        // FMA for even dims
 *     accLo += loF × qTildeLo[i]                        // FMA for odd dims (ILP)
 *   dot = reduceLanes(accHi + accLo)
 *   L2  = exactNormSq + constL2Q − 2 × dot
 * </pre>
 *
 * <h3>Unsigned Widening</h3>
 * <p>After masking, all byte values are in [0, 14] (non-negative in signed byte range).
 * {@code castShape} performs signed widening ({@code vpmovsxbd}), which produces
 * correct float32 values for [0, 14] since the sign bit is always 0.</p>
 *
 * <h3>ILP via High/Low Split</h3>
 * <p>Processing high and low nibbles independently exposes instruction-level parallelism:
 * the CPU can pipeline the high-nibble FMA while loading/masking the low nibble from
 * the same byte vector. This is analogous to the 2× unrolling in {@link VasqSimdKernel}.</p>
 *
 * <p>All methods are stateless and safe for concurrent use.</p>
 *
 * @see Vasq4QueryState
 * @see Vasq4QueryPrep
 * @see VasqSimdKernel
 */
public final class Vasq4SimdKernel {

    // Preferred float species: AVX2 → 8 lanes (256-bit), AVX-512 → 16 lanes (512-bit)
    private static final VectorSpecies<Float> F_SPECIES = SimdCapability.PREFERRED_SPECIES;

    // Byte species with the SAME lane count as F_SPECIES.
    private static final VectorSpecies<Byte> B_SPECIES =
            VectorSpecies.of(byte.class,
                    VectorShape.forBitSize(F_SPECIES.length() * Byte.SIZE));

    /** Number of float lanes per SIMD register. */
    private static final int VL = F_SPECIES.length();

    /** Mask for extracting the low nibble (bits 3..0). */
    private static final byte NIBBLE_MASK = 0x0F;

    static {
        assert B_SPECIES.length() == F_SPECIES.length()
                : "B_SPECIES lanes must equal F_SPECIES lanes";
    }

    private Vasq4SimdKernel() {}

    /**
     * Computes the approximate squared L2 distance between a prepared VASQ-4 query
     * and a nibble-packed encoded vector in a {@link MemorySegment}.
     *
     * <p>Formula: {@code L2 ≈ exactNormSq + constL2Q − 2 × dotUnsigned}</p>
     * <p>where {@code dotUnsigned = Σ uHi × qTildeHi + Σ uLo × qTildeLo}.</p>
     *
     * <p>Reads directly from off-heap memory with zero JVM GC allocations.</p>
     *
     * @param segment    off-heap memory segment containing the encoded vector database
     * @param offset     byte offset of the target vector's 4-byte float32 norm header
     * @param halfDim    half of paddedDim (= number of nibble-packed bytes to process)
     * @param qs         pre-prepared VASQ-4 query state (from {@link Vasq4QueryPrep#prepare})
     * @return approximate squared L2 distance (non-negative)
     */
    public static float computeL2(MemorySegment segment, long offset,
                                   int halfDim, Vasq4QueryState qs) {
        float exactNormSq = segment.get(ValueLayout.JAVA_FLOAT, offset);
        long  codeOffset  = offset + 4L;
        float[] qTildeHi  = qs.qTildeHi();
        float[] qTildeLo  = qs.qTildeLo();

        FloatVector accHi = FloatVector.zero(F_SPECIES);
        FloatVector accLo = FloatVector.zero(F_SPECIES);

        // Main SIMD loop — processes VL packed bytes (= 2×VL dimensions) per iteration
        int i = 0;
        for (; i + VL <= halfDim; i += VL) {
            // Load VL packed bytes from off-heap segment
            ByteVector packed = ByteVector.fromMemorySegment(
                    B_SPECIES, segment, codeOffset + i, ByteOrder.nativeOrder());

            // Extract high nibbles → even-indexed dimensions [0, 14]
            ByteVector hi = packed.lanewise(VectorOperators.LSHR, 4).and(NIBBLE_MASK);
            FloatVector hiF = (FloatVector) hi.castShape(F_SPECIES, 0);
            FloatVector qHi = FloatVector.fromArray(F_SPECIES, qTildeHi, i);
            accHi = hiF.fma(qHi, accHi);

            // Extract low nibbles → odd-indexed dimensions [0, 14]
            ByteVector lo = packed.and(NIBBLE_MASK);
            FloatVector loF = (FloatVector) lo.castShape(F_SPECIES, 0);
            FloatVector qLo = FloatVector.fromArray(F_SPECIES, qTildeLo, i);
            accLo = loF.fma(qLo, accLo);
        }

        // Scalar cleanup for tail (rare: only when halfDim is not a multiple of VL)
        float scalarDot = 0f;
        for (; i < halfDim; i++) {
            byte packed = segment.get(ValueLayout.JAVA_BYTE, codeOffset + i);
            int hiVal = (packed >>> 4) & 0x0F;
            int loVal = packed & 0x0F;
            scalarDot += hiVal * qTildeHi[i] + loVal * qTildeLo[i];
        }

        float dot = accHi.add(accLo).reduceLanes(VectorOperators.ADD) + scalarDot;

        return exactNormSq + qs.constL2Q() - 2f * dot;
    }

    /**
     * Computes the approximate inner product between a prepared VASQ-4 query
     * and a nibble-packed encoded vector.
     *
     * <p>Formula: {@code IP ≈ dotUnsigned + dotOffset}</p>
     *
     * @param segment    off-heap memory segment
     * @param offset     byte offset of the target vector's norm header
     * @param halfDim    half of paddedDim (number of nibble-packed bytes)
     * @param qs         pre-prepared VASQ-4 query state
     * @return approximate inner product
     */
    public static float computeDot(MemorySegment segment, long offset,
                                    int halfDim, Vasq4QueryState qs) {
        long    codeOffset = offset + 4L;
        float[] qTildeHi   = qs.qTildeHi();
        float[] qTildeLo   = qs.qTildeLo();

        FloatVector accHi = FloatVector.zero(F_SPECIES);
        FloatVector accLo = FloatVector.zero(F_SPECIES);

        int i = 0;
        for (; i + VL <= halfDim; i += VL) {
            ByteVector packed = ByteVector.fromMemorySegment(
                    B_SPECIES, segment, codeOffset + i, ByteOrder.nativeOrder());

            ByteVector hi = packed.lanewise(VectorOperators.LSHR, 4).and(NIBBLE_MASK);
            FloatVector hiF = (FloatVector) hi.castShape(F_SPECIES, 0);
            accHi = hiF.fma(FloatVector.fromArray(F_SPECIES, qTildeHi, i), accHi);

            ByteVector lo = packed.and(NIBBLE_MASK);
            FloatVector loF = (FloatVector) lo.castShape(F_SPECIES, 0);
            accLo = loF.fma(FloatVector.fromArray(F_SPECIES, qTildeLo, i), accLo);
        }

        // Scalar cleanup
        float scalarDot = 0f;
        for (; i < halfDim; i++) {
            byte packed = segment.get(ValueLayout.JAVA_BYTE, codeOffset + i);
            int hiVal = (packed >>> 4) & 0x0F;
            int loVal = packed & 0x0F;
            scalarDot += hiVal * qTildeHi[i] + loVal * qTildeLo[i];
        }

        return accHi.add(accLo).reduceLanes(VectorOperators.ADD) + scalarDot + qs.dotOffset();
    }

    /**
     * Returns the number of float lanes per SIMD register.
     *
     * @return lane count (e.g. 8 for AVX2, 16 for AVX-512)
     */
    public static int laneCount() { return VL; }

    /** Returns the float vector species used by this kernel. */
    public static VectorSpecies<Float> floatSpecies() { return F_SPECIES; }

    /** Returns the byte vector species used for packed loads. */
    public static VectorSpecies<Byte> byteSpecies() { return B_SPECIES; }
}
