package com.spectrayan.spector.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Smoke test to verify that the Java Vector API is correctly wired
 * and SIMD capabilities are detected at runtime.
 */
class SimdCapabilityTest {

    @Test
    void shouldDetectPreferredSpecies() {
        assertThat(SimdCapability.PREFERRED_SPECIES).isNotNull();
        assertThat(SimdCapability.laneCount()).isGreaterThan(0);
        assertThat(SimdCapability.vectorBitSize()).isGreaterThanOrEqualTo(64);
    }

    @Test
    void shouldReportCapabilities() {
        String report = SimdCapability.report();
        assertThat(report)
                .contains("SIMD Capability")
                .contains("lanes=")
                .contains("bitSize=");
        System.out.println(report);
    }

    @Test
    void laneCountMatchesBitSize() {
        // Float is 32 bits, so bitSize = laneCount * 32
        int expectedBitSize = SimdCapability.laneCount() * Float.SIZE;
        assertThat(SimdCapability.vectorBitSize()).isEqualTo(expectedBitSize);
    }
}
