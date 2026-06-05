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

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.FloatRange;

/**
 * Property-based tests for importance pre-screen exclusion.
 *
 * <p><b>Validates: Requirements 3.4</b>
 *
 * <p>Property 9: For any corpus memory with importance < 1.0 AND adjustedBucket == MAX_BUCKET(11)
 * AND not pinned AND resolved, that memory SHALL be excluded from scoring results.
 */
class ImportancePreScreenPropertyTest {

    private static final int DIMS = 8;
    private static final int MAX_BUCKET = 11;

    // ══════════════════════════════════════════════════════════════
    // Property 9: Low-importance + MAX_BUCKET + unpinned + resolved → excluded
    // ══════════════════════════════════════════════════════════════

    /**
     * Property 9: A memory with low importance (< 1.0), MAX decay bucket, not pinned,
     * and resolved shall be excluded from results even with high vector similarity.
     *
     * <p><b>Validates: Requirements 3.4</b>
     */
    @Property(tries = 100)
    void lowImportance_maxBucket_unpinned_resolved_isExcluded(
            @ForAll @FloatRange(min = 0.05f, max = 0.99f) float lowImportance) {

        float[] mins = IdentityCalibration.mins(DIMS);
        float[] scales = IdentityCalibration.scales(DIMS);
        CognitiveRecordLayout layout = new CognitiveRecordLayout(DIMS);

        try (Arena arena = Arena.ofConfined()) {
            int corpusSize = 2;
            long totalBytes = (long) corpusSize * layout.stride();
            MemorySegment segment = arena.allocate(totalBytes);

            float[] queryVec = new float[DIMS]; // zero vector

            // Record 0: Should be excluded (low importance, very old, unpinned, resolved)
            long veryOldTimestamp = System.currentTimeMillis() - (6L * 365 * 24 * 60 * 60 * 1000); // 6 years ago
            writeRecord(segment, layout, 0, queryVec, lowImportance,
                    veryOldTimestamp, 0, SynapticHeaderConstants.FLAG_RESOLVED, mins, scales);

            // Record 1: Should NOT be excluded (high importance)
            writeRecord(segment, layout, 1, queryVec, 5.0f,
                    veryOldTimestamp, 0, SynapticHeaderConstants.FLAG_RESOLVED, mins, scales);

            RecallOptions options = RecallOptions.builder()
                    .topK(corpusSize)
                    .build();

            long nowMs = System.currentTimeMillis();
            List<CognitiveScorer.ScoredRecord> results = CognitiveScorer.score(
                    segment, corpusSize, layout, queryVec, options, nowMs);

            // The low-importance record should be excluded
            boolean excludedRecordPresent = results.stream()
                    .anyMatch(r -> r.index() == 0);
            assert !excludedRecordPresent
                    : "Low-importance + MAX_BUCKET + unpinned + resolved should be excluded";

            // The high-importance record should still be present
            boolean highImpPresent = results.stream()
                    .anyMatch(r -> r.index() == 1);
            assert highImpPresent
                    : "High-importance record should not be excluded";
        }
    }

    /**
     * Property 9b: A pinned memory with low importance and MAX bucket should NOT be excluded.
     *
     * <p><b>Validates: Requirements 3.4</b>
     */
    @Property(tries = 100)
    void pinnedMemory_notExcluded_evenWithLowImportanceAndMaxBucket(
            @ForAll @FloatRange(min = 0.05f, max = 0.99f) float lowImportance) {

        float[] mins = IdentityCalibration.mins(DIMS);
        float[] scales = IdentityCalibration.scales(DIMS);
        CognitiveRecordLayout layout = new CognitiveRecordLayout(DIMS);

        try (Arena arena = Arena.ofConfined()) {
            int corpusSize = 1;
            long totalBytes = (long) corpusSize * layout.stride();
            MemorySegment segment = arena.allocate(totalBytes);

            float[] queryVec = new float[DIMS];
            long veryOldTimestamp = System.currentTimeMillis() - (6L * 365 * 24 * 60 * 60 * 1000);

            // Pinned + resolved + low importance + very old
            byte flags = (byte) (SynapticHeaderConstants.FLAG_PINNED | SynapticHeaderConstants.FLAG_RESOLVED);
            writeRecord(segment, layout, 0, queryVec, lowImportance,
                    veryOldTimestamp, 0, flags, mins, scales);

            RecallOptions options = RecallOptions.builder().topK(1).build();

            List<CognitiveScorer.ScoredRecord> results = CognitiveScorer.score(
                    segment, corpusSize, layout, queryVec, options, System.currentTimeMillis());

            assert !results.isEmpty()
                    : "Pinned memory should not be excluded even with low importance and MAX bucket";
        }
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
                timestamp,
                0L,
                1.0f,
                importance,
                recallCount,
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
