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
 * Property-based tests for recency dominance.
 *
 * <p><b>Validates: Requirements 12.1</b>
 *
 * <p>Property 25: For any two memories with identical vector similarity, importance,
 * and recall count but different timestamps, the more recent memory SHALL rank higher.
 */
class RecencyPropertyTest {

    private static final int DIMS = 8;

    // ══════════════════════════════════════════════════════════════
    // Property 25: More recent → higher rank
    // ══════════════════════════════════════════════════════════════

    /**
     * Property 25: With identical vectors, importance, and recall count but different
     * timestamps (in different decay buckets), the more recent memory ranks higher.
     *
     * <p><b>Validates: Requirements 12.1</b>
     */
    @Property(tries = 100)
    void moreRecent_ranksHigher(@ForAll("recencyPairs") RecencyPair pair) {

        float[] mins = IdentityCalibration.mins(DIMS);
        float[] scales = IdentityCalibration.scales(DIMS);
        CognitiveRecordLayout layout = new CognitiveRecordLayout(DIMS);

        try (Arena arena = Arena.ofConfined()) {
            int corpusSize = 2;
            long totalBytes = (long) corpusSize * layout.stride();
            MemorySegment segment = arena.allocate(totalBytes);

            long nowMs = System.currentTimeMillis();

            // Both records have identical vectors and importance
            float[] identicalVec = new float[DIMS];
            for (int i = 0; i < DIMS; i++) {
                identicalVec[i] = 0.3f;
            }
            float importance = 5.0f;

            // Record 0: RECENT (1 hour ago)
            long recentTimestamp = nowMs - pair.recentAgeMs();
            writeRecord(segment, layout, 0, identicalVec, importance,
                    recentTimestamp, 0, mins, scales);

            // Record 1: OLD (much older — different decay bucket)
            long oldTimestamp = nowMs - pair.oldAgeMs();
            writeRecord(segment, layout, 1, identicalVec, importance,
                    oldTimestamp, 0, mins, scales);

            float[] queryVec = new float[DIMS];

            RecallOptions options = RecallOptions.builder()
                    .topK(2)
                    .alpha(0.4f)
                    .beta(0.6f)  // beta > 0 so decay matters
                    .build();

            List<CognitiveScorer.ScoredRecord> results = CognitiveScorer.score(
                    segment, corpusSize, layout, queryVec, options, nowMs);

            if (results.size() == 2) {
                // More recent memory should rank first
                assert results.getFirst().index() == 0
                        : String.format("More recent memory (age=%dms) should rank above older (age=%dms)",
                        pair.recentAgeMs(), pair.oldAgeMs());
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Support types
    // ══════════════════════════════════════════════════════════════

    record RecencyPair(long recentAgeMs, long oldAgeMs) {}

    // ══════════════════════════════════════════════════════════════
    // Generators
    // ══════════════════════════════════════════════════════════════

    @Provide
    Arbitrary<RecencyPair> recencyPairs() {
        // Recent: within 1 hour (bucket 0), Old: 7+ days (bucket 4+)
        return Combinators.combine(
                Arbitraries.longs().between(1000L, 3_600_000L),           // recent: <1 hour
                Arbitraries.longs().between(604_800_000L, 7_776_000_000L) // old: 7 days to 3 months
        ).as(RecencyPair::new);
    }

    // ══════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════

    private void writeRecord(MemorySegment segment, CognitiveRecordLayout layout,
                             int index, float[] vector, float importance,
                             long timestamp, int recallCount,
                             float[] mins, float[] scales) {
        long offset = (long) index * layout.stride();
        CognitiveHeader header = new CognitiveHeader(
                timestamp, 0L, 1.0f, importance, recallCount, (short) 0, (byte) 0,
                SynapticHeaderConstants.FLAG_RESOLVED);
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
