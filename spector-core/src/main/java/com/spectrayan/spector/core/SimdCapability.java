package com.spectrayan.spector.core;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorSpecies;

/**
 * Reports the SIMD capabilities detected at runtime.
 *
 * <p>This class queries the JVM for the preferred {@link VectorSpecies}
 * and provides diagnostic information about the available SIMD width
 * and instruction set architecture.</p>
 */
public final class SimdCapability {

    /** The preferred float vector species for this platform (AVX2 = 256-bit, AVX-512 = 512-bit, etc.). */
    public static final VectorSpecies<Float> PREFERRED_SPECIES = FloatVector.SPECIES_PREFERRED;

    private SimdCapability() {
        // utility class
    }

    /**
     * Returns the number of float lanes in a single SIMD register.
     *
     * @return lane count (e.g. 8 for AVX2, 16 for AVX-512)
     */
    public static int laneCount() {
        return PREFERRED_SPECIES.length();
    }

    /**
     * Returns the SIMD vector bit width.
     *
     * @return bit width (e.g. 256 for AVX2, 512 for AVX-512)
     */
    public static int vectorBitSize() {
        return PREFERRED_SPECIES.vectorBitSize();
    }

    /**
     * Returns a human-readable summary of SIMD capabilities.
     *
     * @return capability report string
     */
    public static String report() {
        return String.format(
                "SIMD Capability: species=%s, lanes=%d, bitSize=%d",
                PREFERRED_SPECIES, laneCount(), vectorBitSize()
        );
    }
}
