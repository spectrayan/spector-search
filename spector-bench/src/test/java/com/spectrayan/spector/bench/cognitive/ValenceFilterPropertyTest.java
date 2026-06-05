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
 * Property-based tests for valence range filtering.
 *
 * <p><b>Validates: Requirements 3.3, 10.1, 10.2</b>
 *
 * <p>Property 8: For any query specifying a valence range [min, max] and any corpus
 * memory in the result set, the memory's valence SHALL satisfy min ≤ valence ≤ max.
 */
class ValenceFilterPropertyTest {

    private static final int DIMS = 8;

    // ══════════════════════════════════════════════════════════════
    // Property 8: Valence within [min, max] for all results
    // ══════════════════════════════════════════════════════════════

    /**
     * Property 8: All results returned by CognitiveScorer with a valence filter
     * have valence within the specified [min, max] range.
     *
     * <p><b>Validates: Requirements 3.3, 10.1, 10.2</b>
     */
    @Property(tries = 100)
    void allResults_haveValenceWithinRange(
            @ForAll("valenceRanges") ValenceRange range) {

        float[] mins = IdentityCalibration.mins(DIMS);
        float[] scales = IdentityCalibration.scales(DIMS);
        CognitiveRecordLayout layout = new CognitiveRecordLayout(DIMS);
        int corpusSize = 20;

        try (Arena arena = Arena.ofConfined()) {
            long totalBytes = (long) corpusSize * layout.stride();
            MemorySegment segment = arena.allocate(totalBytes);

            float[] queryVec = new float[DIMS]; // zero vector
            // Create records spanning the full valence range
            for (int i = 0; i < corpusSize; i++) {
                byte valence = (byte) (-128 + i * 13); // spread across range
                float[] vec = new float[DIMS];
                vec[0] = 0.1f * i; // slightly different vectors
                writeRecord(segment, layout, i, vec, valence, mins, scales);
            }

            RecallOptions options = RecallOptions.builder()
                    .topK(corpusSize)
                    .minValence(range.min())
                    .maxValence(range.max())
                    .build();

            List<CognitiveScorer.ScoredRecord> results = CognitiveScorer.score(
                    segment, corpusSize, layout, queryVec, options, System.currentTimeMillis());

            // Verify all results have valence within range
            for (CognitiveScorer.ScoredRecord result : results) {
                byte valence = result.header().valence();
                assert valence >= range.min() && valence <= range.max()
                        : String.format("Valence %d outside range [%d, %d]",
                        valence, range.min(), range.max());
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Support types
    // ══════════════════════════════════════════════════════════════

    record ValenceRange(byte min, byte max) {}

    // ══════════════════════════════════════════════════════════════
    // Generators
    // ══════════════════════════════════════════════════════════════

    @Provide
    Arbitrary<ValenceRange> valenceRanges() {
        return Arbitraries.bytes().flatMap(min ->
                Arbitraries.bytes()
                        .filter(max -> max >= min)
                        .map(max -> new ValenceRange(min, max)));
    }

    // ══════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════

    private void writeRecord(MemorySegment segment, CognitiveRecordLayout layout,
                             int index, float[] vector, byte valence,
                             float[] mins, float[] scales) {
        long offset = (long) index * layout.stride();

        CognitiveHeader header = new CognitiveHeader(
                System.currentTimeMillis(),
                0L,
                1.0f,
                5.0f,
                0,
                (short) 0,
                valence,
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
