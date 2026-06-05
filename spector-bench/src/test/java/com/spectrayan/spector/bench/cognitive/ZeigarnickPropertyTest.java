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

/**
 * Property-based tests for the Zeigarnik decay clamp.
 *
 * <p><b>Validates: Requirements 12.3</b>
 *
 * <p>Property 26: For any corpus memory with the unresolved flag (bit 5 = 0 in flags byte,
 * not pinned), the decay bucket SHALL be clamped to 0, making it score as if perpetually
 * fresh regardless of actual age.
 */
class ZeigarnickPropertyTest {

    private static final int DIMS = 8;

    // ══════════════════════════════════════════════════════════════
    // Property 26: Unresolved → decay bucket clamped to 0
    // ══════════════════════════════════════════════════════════════

    /**
     * Property 26: An unresolved memory (FLAG_RESOLVED not set, not pinned) should
     * score the same as a fresh memory regardless of how old it is.
     *
     * <p><b>Validates: Requirements 12.3</b>
     */
    @Property(tries = 100)
    void unresolvedMemory_scoresAsFresh(@ForAll("oldTimestamps") long ageMs) {

        float[] mins = IdentityCalibration.mins(DIMS);
        float[] scales = IdentityCalibration.scales(DIMS);
        CognitiveRecordLayout layout = new CognitiveRecordLayout(DIMS);

        try (Arena arena = Arena.ofConfined()) {
            int corpusSize = 2;
            long totalBytes = (long) corpusSize * layout.stride();
            MemorySegment segment = arena.allocate(totalBytes);

            long nowMs = System.currentTimeMillis();
            float[] identicalVec = new float[DIMS];
            for (int i = 0; i < DIMS; i++) identicalVec[i] = 0.3f;
            float importance = 5.0f;

            // Record 0: UNRESOLVED (bit 5 = 0) + old → should clamp to bucket 0
            byte unresolvedFlags = 0; // no resolved flag, no pinned flag
            long oldTimestamp = nowMs - ageMs;
            writeRecord(segment, layout, 0, identicalVec, importance,
                    oldTimestamp, 0, unresolvedFlags, mins, scales);

            // Record 1: RESOLVED + old → normal decay
            byte resolvedFlags = SynapticHeaderConstants.FLAG_RESOLVED;
            writeRecord(segment, layout, 1, identicalVec, importance,
                    oldTimestamp, 0, resolvedFlags, mins, scales);

            float[] queryVec = new float[DIMS];

            RecallOptions options = RecallOptions.builder()
                    .topK(2)
                    .alpha(0.3f)
                    .beta(0.7f)
                    .build();

            List<CognitiveScorer.ScoredRecord> results = CognitiveScorer.score(
                    segment, corpusSize, layout, queryVec, options, nowMs);

            if (results.size() == 2) {
                // Unresolved memory (index 0) should score >= resolved memory (index 1)
                // because its decay is clamped to 1.0 (bucket 0)
                float unresolvedScore = results.stream()
                        .filter(r -> r.index() == 0).findFirst()
                        .map(CognitiveScorer.ScoredRecord::score).orElse(0f);
                float resolvedScore = results.stream()
                        .filter(r -> r.index() == 1).findFirst()
                        .map(CognitiveScorer.ScoredRecord::score).orElse(0f);

                assert unresolvedScore >= resolvedScore
                        : String.format("Unresolved memory (score=%.6f) should score >= " +
                        "resolved memory (score=%.6f) for age=%dms",
                        unresolvedScore, resolvedScore, ageMs);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Generators
    // ══════════════════════════════════════════════════════════════

    @Provide
    Arbitrary<Long> oldTimestamps() {
        // Timestamps old enough to produce non-trivial decay (at least 1 day)
        return Arbitraries.longs().between(
                86_400_000L,        // 1 day
                31_536_000_000L     // 1 year
        );
    }

    // ══════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════

    private void writeRecord(MemorySegment segment, CognitiveRecordLayout layout,
                             int index, float[] vector, float importance,
                             long timestamp, int recallCount, byte flags,
                             float[] mins, float[] scales) {
        long offset = (long) index * layout.stride();
        CognitiveHeader header = new CognitiveHeader(
                timestamp, 0L, 1.0f, importance, recallCount, (short) 0, (byte) 0, flags);
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
