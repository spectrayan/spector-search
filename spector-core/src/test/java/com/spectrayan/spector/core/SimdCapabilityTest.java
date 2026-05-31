/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.spectrayan.spector.core;

import com.spectrayan.spector.core.simd.SimdCapability;

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
