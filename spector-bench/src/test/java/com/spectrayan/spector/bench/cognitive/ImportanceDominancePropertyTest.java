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

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;

import com.spectrayan.spector.memory.RecallOptions;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout.CognitiveHeader;
import com.spectrayan.spector.memory.synapse.CognitiveScorer;
import com.spectrayan.spector.memory.synapse.IdentityCalibration;
import com.spectrayan.spector.memory.synapse.SynapticHeaderConstants;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based tests for importance dominance with equal similarity.
 *
 * <p><b>Validates: Requirements 4.1, 4.3</b>
 *
 * <p>Property 13: For any two corpus memories with identical vector distance to the query
 * but differing importance scores (imp_A > imp_B), and a profile with beta > 0, the
 * higher-importance memory SHALL rank above the lower-importance memory.
 */
class ImportanceDominancePropertyTest {

    private static final int DIMS = 8;

    // ══════════════════════════════════════════════════════════════
    // Property 13: Higher importance → higher rank (beta > 0)
    // ══════════════════════════════════════════════════════════════

    /**
     * Property 13: With identical vectors but different importance, the higher-importance
     * memory ranks above the lower-importance memory when beta > 0.
     *
     * <p><b>Validates: Requirements 4.1, 4.3</b>
     */
    @Property(tries = 100)
    void higherImportance_ranksHigher_whenBetaPositive(
            @ForAll("importancePairs") ImportancePair pair) {

        float[] mins = IdentityCalibration.mins(DIMS);
        float[] scales = IdentityCalibration.scales(DIMS);
        CognitiveRecordLayout layout = new CognitiveRecordLayout(DIMS);

        try (Arena arena = Arena.ofConfined()) {
            int corpusSize = 2;
            long totalBytes = (long) corpusSize * layout.stride();
            MemorySegment segment = arena.allocate(totalBytes);

            long nowMs = System.currentTimeMillis();
            long recentTimestamp = nowMs - 60_000; // 1 minute ago — bucket 0

            // Both records have IDENTICAL vectors (same L2 distance to query)
            float[] identicalVec = new float[DIMS];
            for (int i = 0; i < DIMS; i++) {
                identicalVec[i] = 0.3f;
            }

            // Record 0: HIGH importance
            writeRecord(segment, layout, 0, identicalVec, pair.highImportance(),
                    recentTimestamp, mins, scales);
            // Record 1: LOW importance
            writeRecord(segment, layout, 1, identicalVec, pair.lowImportance(),
                    recentTimestamp, mins, scales);

            float[] queryVec = new float[DIMS]; // zero vector

            RecallOptions options = RecallOptions.builder()
                    .topK(2)
                    .alpha(0.3f)
                    .beta(pair.beta())  // beta > 0
                    .build();

            List<CognitiveScorer.ScoredRecord> results = CognitiveScorer.score(
                    segment, corpusSize, layout, queryVec, options, nowMs);

            assert results.size() == 2
                    : "Expected 2 results, got " + results.size();

            // First result should be the high-importance record
            int firstIndex = results.getFirst().index();
            assert firstIndex == 0
                    : String.format("Higher importance (%.2f) should rank above lower (%.2f) " +
                    "with beta=%.2f, but got index %d first",
                    pair.highImportance(), pair.lowImportance(), pair.beta(), firstIndex);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Support types
    // ══════════════════════════════════════════════════════════════

    record ImportancePair(float highImportance, float lowImportance, float beta) {}

    // ══════════════════════════════════════════════════════════════
    // Generators
    // ══════════════════════════════════════════════════════════════

    @Provide
    Arbitrary<ImportancePair> importancePairs() {
        return Combinators.combine(
                Arbitraries.floats().between(3.0f, 9.0f),   // high importance
                Arbitraries.floats().between(0.1f, 2.0f),   // low importance
                Arbitraries.floats().between(0.1f, 0.8f)    // beta > 0
        ).as(ImportancePair::new);
    }

    // ══════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════

    private void writeRecord(MemorySegment segment, CognitiveRecordLayout layout,
                             int index, float[] vector, float importance,
                             long timestamp, float[] mins, float[] scales) {
        long offset = (long) index * layout.stride();

        CognitiveHeader header = new CognitiveHeader(
                timestamp,
                0L,
                1.0f,
                importance,
                0,
                (short) 0,
                (byte) 0,
                SynapticHeaderConstants.FLAG_RESOLVED
        );
        layout.writeHeader(segment, offset, header);

        byte[] quantized = new byte[DIMS];
        for (int i = 0; i < DIMS; i++) {
            float normalized = (vector[i] - mins[i]) / scales[i];
            int q = Math.round(normalized);
            quantized[i] = (byte) Math.max(0, Math.min(255, q));
        }
        layout.writeQuantizedVector(segment, offset, quantized);
    }
}
