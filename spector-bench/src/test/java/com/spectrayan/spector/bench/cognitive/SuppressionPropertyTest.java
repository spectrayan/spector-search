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
package com.spectrayan.spector.bench.cognitive;

import com.spectrayan.spector.memory.inhibition.SuppressionSet;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based tests for suppression set exclusion and restoration.
 *
 * <p><b>Validates: Requirements 8.2, 8.4</b>
 *
 * <p>Property 20: For any memory ID added to the SuppressionSet, that memory SHALL be
 * excluded from subsequent recall. After unsuppression, it SHALL be eligible again.
 */
class SuppressionPropertyTest {

    // ══════════════════════════════════════════════════════════════
    // Property 20a: Suppressed excluded
    // ══════════════════════════════════════════════════════════════

    /**
     * Property 20a: After suppressing a memory ID, isSuppressed returns true.
     *
     * <p><b>Validates: Requirements 8.2</b>
     */
    @Property(tries = 200)
    void suppressed_isExcluded(@ForAll("memoryIds") String memoryId) {
        SuppressionSet set = new SuppressionSet();

        set.suppress(memoryId);

        assert set.isSuppressed(memoryId)
                : "Suppressed memory should be reported as suppressed: " + memoryId;
    }

    /**
     * Property 20b: After unsuppressing a previously suppressed memory,
     * isSuppressed returns false (eligible again).
     *
     * <p><b>Validates: Requirements 8.4</b>
     */
    @Property(tries = 200)
    void unsuppressed_isEligibleAgain(@ForAll("memoryIds") String memoryId) {
        SuppressionSet set = new SuppressionSet();

        set.suppress(memoryId);
        assert set.isSuppressed(memoryId) : "Should be suppressed after suppress()";

        set.unsuppress(memoryId);
        assert !set.isSuppressed(memoryId)
                : "Should be eligible again after unsuppress(): " + memoryId;
    }

    /**
     * Property 20c: A memory that was never suppressed is not reported as suppressed.
     *
     * <p><b>Validates: Requirements 8.2</b>
     */
    @Property(tries = 200)
    void neverSuppressed_isNotExcluded(@ForAll("memoryIds") String memoryId) {
        SuppressionSet set = new SuppressionSet();

        assert !set.isSuppressed(memoryId)
                : "Never-suppressed memory should not be reported as suppressed: " + memoryId;
    }

    /**
     * Property 20d: After clear(), all previously suppressed memories are eligible.
     *
     * <p><b>Validates: Requirements 8.4</b>
     */
    @Property(tries = 100)
    void afterClear_allEligible(@ForAll("memoryIds") String memoryId) {
        SuppressionSet set = new SuppressionSet();

        set.suppress(memoryId);
        set.suppress(memoryId + "-extra");
        set.clear();

        assert !set.isSuppressed(memoryId)
                : "After clear(), memory should be eligible: " + memoryId;
        assert set.size() == 0
                : "After clear(), size should be 0";
    }

    // ══════════════════════════════════════════════════════════════
    // Generators
    // ══════════════════════════════════════════════════════════════

    @Provide
    Arbitrary<String> memoryIds() {
        return Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(20)
                .map(s -> "mem-" + s);
    }
}
