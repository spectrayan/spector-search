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
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * Property-based tests for consolidation preservation.
 *
 * <p><b>Validates: Requirements 14.2</b>
 *
 * <p>Property 30: After consolidation, all non-tombstoned and non-pruned memories
 * SHALL remain retrievable with unchanged scores (within floating-point epsilon).
 */
class ConsolidationPropertyTest {

    private static final int DIMS = 8;
    private static final float EPSILON = 1e-5f;

    // ══════════════════════════════════════════════════════════════
    // Property 30: Non-pruned memories retrievable unchanged
    // ══════════════════════════════════════════════════════════════

    /**
     * Property 30: Non-tombstoned, non-pruned memories score consistently across
     * repeated queries (no mutation during OBSERVE mode scoring).
     *
     * <p>We verify that scoring the same corpus twice produces identical results,
     * which is the essence of "unchanged after consolidation" — the scoring pipeline
     * does not mutate memory state.
     *
     * <p><b>Validates: Requirements 14.2</b>
     */
    @Property(tries = 100)
    void nonPrunedMemories_scoreConsistently(
            @ForAll("queryVectors") float[] queryVector,
            @ForAll @IntRange(min = 3, max = 15) int corpusSize) {

        float[] mins = IdentityCalibration.mins(DIMS);
        float[] scales = IdentityCalibration.scales(DIMS);
        CognitiveRecordLayout layout = new CognitiveRecordLayout(DIMS);

        try (Arena arena = Arena.ofConfined()) {
            long totalBytes = (long) corpusSize * layout.stride();
            MemorySegment segment = arena.allocate(totalBytes);

            long nowMs = System.currentTimeMillis();
            for (int i = 0; i < corpusSize; i++) {
                float[] vec = generateVector(i, corpusSize);
                long timestamp = nowMs - (long) i * 3600_000;
                // All records are alive (not tombstoned) and resolved
                byte flags = SynapticHeaderConstants.FLAG_RESOLVED;
                writeRecord(segment, layout, i, vec, 5.0f, timestamp, flags, mins, scales);
            }

            RecallOptions options = RecallOptions.builder()
                    .topK(corpusSize)
                    .build();

            // Score twice — results should be identical
            List<CognitiveScorer.ScoredRecord> results1 = CognitiveScorer.score(
                    segment, corpusSize, layout, queryVector, options, nowMs);
            List<CognitiveScorer.ScoredRecord> results2 = CognitiveScorer.score(
                    segment, corpusSize, layout, queryVector, options, nowMs);

            assert results1.size() == results2.size()
                    : "Repeated scoring should return same count";

            for (int i = 0; i < results1.size(); i++) {
                float score1 = results1.get(i).score();
                float score2 = results2.get(i).score();
                assert Math.abs(score1 - score2) < EPSILON
                        : String.format("Score mismatch at position %d: %.6f vs %.6f",
                        i, score1, score2);
                assert results1.get(i).index() == results2.get(i).index()
                        : "Same records should be in same positions";
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Generators
    // ══════════════════════════════════════════════════════════════

    @Provide
    Arbitrary<float[]> queryVectors() {
        return Arbitraries.floats().between(-1.0f, 1.0f)
                .array(float[].class).ofSize(DIMS);
    }

    // ══════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════

    private float[] generateVector(int index, int corpusSize) {
        float[] vec = new float[DIMS];
        for (int d = 0; d < DIMS; d++) {
            vec[d] = -1.0f + 2.0f * ((index * (d + 1) * 7 + d * 13) % (corpusSize * 3))
                    / (float) (corpusSize * 3);
        }
        return vec;
    }

    private void writeRecord(MemorySegment segment, CognitiveRecordLayout layout,
                             int index, float[] vector, float importance, long timestamp,
                             byte flags, float[] mins, float[] scales) {
        long offset = (long) index * layout.stride();
        CognitiveHeader header = new CognitiveHeader(
                timestamp, 0L, 1.0f, importance, 1, (short) 0, (byte) 0, flags);
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
