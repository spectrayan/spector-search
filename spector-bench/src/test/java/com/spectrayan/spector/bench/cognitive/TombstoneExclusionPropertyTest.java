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
import java.util.Set;
import java.util.stream.Collectors;

import com.spectrayan.spector.bench.cognitive.model.ScoredResult;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout.CognitiveHeader;
import com.spectrayan.spector.memory.synapse.IdentityCalibration;
import com.spectrayan.spector.memory.synapse.SynapticHeaderConstants;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * Property-based tests for tombstone exclusion invariant.
 *
 * <p><b>Validates: Requirements 3.1, 8.1</b>
 *
 * <p>Property 6: For any query and any corpus memory with its tombstone flag set,
 * that memory SHALL never appear in scoring pipeline results.
 */
class TombstoneExclusionPropertyTest {

    private static final int DIMS = 8;

    // ══════════════════════════════════════════════════════════════
    // Property 6: Tombstoned memories never appear in results
    // ══════════════════════════════════════════════════════════════

    /**
     * Property 6: For any corpus where some records are tombstoned, those records
     * shall never appear in BaselineRetriever results regardless of their vector
     * similarity, importance, or recall count.
     *
     * <p><b>Validates: Requirements 3.1, 8.1</b>
     */
    @Property(tries = 100)
    void tombstonedMemories_neverAppearInResults(
            @ForAll("queryVectors") float[] queryVector,
            @ForAll @IntRange(min = 5, max = 20) int corpusSize,
            @ForAll @IntRange(min = 1, max = 5) int tombstoneCount) {

        int actualTombstones = Math.min(tombstoneCount, corpusSize - 1);

        float[] mins = IdentityCalibration.mins(DIMS);
        float[] scales = IdentityCalibration.scales(DIMS);
        CognitiveRecordLayout layout = new CognitiveRecordLayout(DIMS);

        try (Arena arena = Arena.ofConfined()) {
            long totalBytes = (long) corpusSize * layout.stride();
            MemorySegment segment = arena.allocate(totalBytes);

            String[] ids = new String[corpusSize];
            Set<String> tombstonedIds = new java.util.HashSet<>();

            for (int i = 0; i < corpusSize; i++) {
                ids[i] = "mem-" + i;
                // Make tombstoned records have VERY high similarity (close vectors)
                // to prove they're excluded regardless of score
                float[] vec;
                byte flags;
                if (i < actualTombstones) {
                    // Tombstoned — use the query vector itself for max similarity
                    vec = queryVector.clone();
                    flags = SynapticHeaderConstants.FLAG_TOMBSTONE;
                    tombstonedIds.add(ids[i]);
                } else {
                    // Non-tombstoned — use a distant vector
                    vec = generateDeterministicVector(i, corpusSize);
                    flags = SynapticHeaderConstants.FLAG_RESOLVED; // alive and resolved
                }
                writeRecord(segment, layout, i, vec, flags, mins, scales);
            }

            BaselineRetriever retriever = new BaselineRetriever(
                    segment, layout, corpusSize, mins, scales, ids);

            List<ScoredResult> results = retriever.retrieve(queryVector, corpusSize);

            // Verify no tombstoned memory appears in results
            Set<String> resultIds = results.stream()
                    .map(ScoredResult::memoryId)
                    .collect(Collectors.toSet());

            for (String tombId : tombstonedIds) {
                assert !resultIds.contains(tombId)
                        : "Tombstoned memory " + tombId + " appeared in results";
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

    private float[] generateDeterministicVector(int index, int corpusSize) {
        float[] vec = new float[DIMS];
        for (int d = 0; d < DIMS; d++) {
            vec[d] = -1.0f + 2.0f * ((index * (d + 1) * 7 + d * 13) % (corpusSize * 3))
                    / (float) (corpusSize * 3);
        }
        return vec;
    }

    private void writeRecord(MemorySegment segment, CognitiveRecordLayout layout,
                             int index, float[] vector, byte flags,
                             float[] mins, float[] scales) {
        long offset = (long) index * layout.stride();

        CognitiveHeader header = new CognitiveHeader(
                System.currentTimeMillis(),
                0L,
                1.0f,
                5.0f,    // high importance — should not override tombstone
                10,      // high recall count — should not override tombstone
                (short) 0,
                (byte) 0,
                flags
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
