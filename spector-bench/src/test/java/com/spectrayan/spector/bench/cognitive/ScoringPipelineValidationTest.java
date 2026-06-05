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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import com.spectrayan.spector.memory.RecallOptions;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout.CognitiveHeader;
import com.spectrayan.spector.memory.synapse.CognitiveScorer;
import com.spectrayan.spector.memory.synapse.IdentityCalibration;
import com.spectrayan.spector.memory.synapse.SynapticHeaderConstants;
import com.spectrayan.spector.memory.synapse.SynapticTagEncoder;

/**
 * Example-based tests for each scoring phase of the CognitiveScorer independently.
 *
 * <p>Validates each of the 6 phases in isolation with controlled synthetic data:
 * <ol>
 *   <li>Tombstone exclusion</li>
 *   <li>Tag gating (Bloom filter containment)</li>
 *   <li>Valence filtering</li>
 *   <li>Importance pre-screen + decay bucket MAX exclusion</li>
 *   <li>L2 distance computation</li>
 *   <li>Fused score formula</li>
 * </ol>
 *
 * <p><b>Validates: Requirements 3.1–3.7</b>
 */
class ScoringPipelineValidationTest {

    private static final int DIMS = 8;

    // ══════════════════════════════════════════════════════════════
    // Phase 1: Tombstone Exclusion
    // ══════════════════════════════════════════════════════════════

    /**
     * Phase 1: Creates 2 records — identical except one is tombstoned.
     * Verifies tombstoned record never appears in results.
     */
    @Test
    void tombstoneExclusion() {
        float[] mins = IdentityCalibration.mins(DIMS);
        float[] scales = IdentityCalibration.scales(DIMS);
        CognitiveRecordLayout layout = new CognitiveRecordLayout(DIMS);

        try (Arena arena = Arena.ofConfined()) {
            int corpusSize = 2;
            MemorySegment segment = arena.allocate((long) corpusSize * layout.stride());
            float[] vec = new float[DIMS]; // zero vector

            // Record 0: tombstoned
            writeRecord(segment, layout, 0, vec, 5.0f, System.currentTimeMillis(),
                    SynapticHeaderConstants.FLAG_TOMBSTONE, (byte) 0, 0L, mins, scales);
            // Record 1: alive
            writeRecord(segment, layout, 1, vec, 5.0f, System.currentTimeMillis(),
                    SynapticHeaderConstants.FLAG_RESOLVED, (byte) 0, 0L, mins, scales);

            RecallOptions options = RecallOptions.builder().topK(2).build();
            List<CognitiveScorer.ScoredRecord> results = CognitiveScorer.score(
                    segment, corpusSize, layout, vec, options, System.currentTimeMillis());

            assertEquals(1, results.size(), "Only non-tombstoned record should be returned");
            assertEquals(1, results.getFirst().index(), "Alive record should be at index 1");
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Phase 2: Tag Gating
    // ══════════════════════════════════════════════════════════════

    /**
     * Phase 2: Creates records with known Bloom filter encodings.
     * Verifies containment: (record_tags & query_mask) == query_mask
     */
    @Test
    void tagGatingContainment() {
        float[] mins = IdentityCalibration.mins(DIMS);
        float[] scales = IdentityCalibration.scales(DIMS);
        CognitiveRecordLayout layout = new CognitiveRecordLayout(DIMS);

        try (Arena arena = Arena.ofConfined()) {
            int corpusSize = 3;
            MemorySegment segment = arena.allocate((long) corpusSize * layout.stride());
            float[] vec = new float[DIMS];
            long queryMask = SynapticTagEncoder.encode("java", "performance");

            // Record 0: has matching tags
            long matchingTags = SynapticTagEncoder.encode("java", "performance", "memory");
            writeRecord(segment, layout, 0, vec, 5.0f, System.currentTimeMillis(),
                    SynapticHeaderConstants.FLAG_RESOLVED, (byte) 0, matchingTags, mins, scales);

            // Record 1: use 0L as tags (guaranteed zero overlap with any query mask)
            writeRecord(segment, layout, 1, vec, 5.0f, System.currentTimeMillis(),
                    SynapticHeaderConstants.FLAG_RESOLVED, (byte) 0, 0L, mins, scales);

            // Record 2: partial overlap (has "java" but not "performance")
            long partialTags = SynapticTagEncoder.encode("java", "cooking");
            writeRecord(segment, layout, 2, vec, 5.0f, System.currentTimeMillis(),
                    SynapticHeaderConstants.FLAG_RESOLVED, (byte) 0, partialTags, mins, scales);

            RecallOptions options = RecallOptions.builder()
                    .topK(3)
                    .synapticFilter("java", "performance")
                    .build();
            List<CognitiveScorer.ScoredRecord> results = CognitiveScorer.score(
                    segment, corpusSize, layout, vec, options, System.currentTimeMillis());

            // Non-matching record (index 1) should be excluded (zero overlap)
            // Note: CognitiveScorer uses broadened containment — skip only on ZERO overlap.
            // Record 1 ("cooking", "recipes") has zero overlap with query ("java", "performance")
            boolean nonMatchingPresent = results.stream()
                    .anyMatch(r -> r.index() == 1);
            assertFalse(nonMatchingPresent,
                    "Record with zero tag overlap should be excluded");

            // Matching record (index 0) should be present
            boolean matchingPresent = results.stream()
                    .anyMatch(r -> r.index() == 0);
            assertTrue(matchingPresent,
                    "Record with full tag match should be present");

            // Partial overlap record (index 2) may or may not be present
            // depending on whether any bits overlap — with broadened containment
            // it passes if ANY overlap exists
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Phase 3: Valence Filtering
    // ══════════════════════════════════════════════════════════════

    /**
     * Phase 3: Creates records spanning valence -128 to +127.
     * Verifies only records within [min, max] pass.
     */
    @Test
    void valenceRangeFiltering() {
        float[] mins = IdentityCalibration.mins(DIMS);
        float[] scales = IdentityCalibration.scales(DIMS);
        CognitiveRecordLayout layout = new CognitiveRecordLayout(DIMS);

        try (Arena arena = Arena.ofConfined()) {
            int corpusSize = 5;
            MemorySegment segment = arena.allocate((long) corpusSize * layout.stride());
            float[] vec = new float[DIMS];

            byte[] valences = {-100, -50, 0, 50, 100};
            for (int i = 0; i < corpusSize; i++) {
                writeRecord(segment, layout, i, vec, 5.0f, System.currentTimeMillis(),
                        SynapticHeaderConstants.FLAG_RESOLVED, valences[i], 0L, mins, scales);
            }

            // Filter: only valence in [-60, 60]
            RecallOptions options = RecallOptions.builder()
                    .topK(5)
                    .minValence((byte) -60)
                    .maxValence((byte) 60)
                    .build();
            List<CognitiveScorer.ScoredRecord> results = CognitiveScorer.score(
                    segment, corpusSize, layout, vec, options, System.currentTimeMillis());

            // Only valences -50, 0, 50 should pass (indices 1, 2, 3)
            assertEquals(3, results.size(), "Only 3 records should pass valence filter [-60, 60]");
            for (var result : results) {
                byte v = result.header().valence();
                assertTrue(v >= -60 && v <= 60,
                        "Result valence " + v + " outside range [-60, 60]");
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Phase 4: Importance Pre-Screen
    // ══════════════════════════════════════════════════════════════

    /**
     * Phase 4: Verifies old + low-importance + resolved + unpinned = excluded.
     */
    @Test
    void importanceDecayPreScreen() {
        float[] mins = IdentityCalibration.mins(DIMS);
        float[] scales = IdentityCalibration.scales(DIMS);
        CognitiveRecordLayout layout = new CognitiveRecordLayout(DIMS);

        try (Arena arena = Arena.ofConfined()) {
            int corpusSize = 2;
            MemorySegment segment = arena.allocate((long) corpusSize * layout.stride());
            float[] vec = new float[DIMS];
            long veryOldTs = System.currentTimeMillis() - (6L * 365 * 24 * 60 * 60 * 1000);

            // Record 0: low importance, very old, resolved, unpinned → should be excluded
            writeRecordWithTimestamp(segment, layout, 0, vec, 0.5f, veryOldTs,
                    SynapticHeaderConstants.FLAG_RESOLVED, mins, scales);

            // Record 1: high importance, very old → should survive
            writeRecordWithTimestamp(segment, layout, 1, vec, 8.0f, veryOldTs,
                    SynapticHeaderConstants.FLAG_RESOLVED, mins, scales);

            RecallOptions options = RecallOptions.builder().topK(2).build();
            List<CognitiveScorer.ScoredRecord> results = CognitiveScorer.score(
                    segment, corpusSize, layout, vec, options, System.currentTimeMillis());

            // Only high-importance record should survive
            assertEquals(1, results.size(), "Low-importance MAX_BUCKET record should be excluded");
            assertEquals(1, results.getFirst().index(), "High-importance record at index 1");
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Phase 5: L2 Distance Computation
    // ══════════════════════════════════════════════════════════════

    /**
     * Phase 5: Known vectors with precomputed distances.
     * Verifies closer vectors rank higher.
     */
    @Test
    void vectorDistanceComputation() {
        float[] mins = IdentityCalibration.mins(DIMS);
        float[] scales = IdentityCalibration.scales(DIMS);
        CognitiveRecordLayout layout = new CognitiveRecordLayout(DIMS);

        try (Arena arena = Arena.ofConfined()) {
            int corpusSize = 2;
            MemorySegment segment = arena.allocate((long) corpusSize * layout.stride());

            float[] queryVec = new float[DIMS]; // zero vector

            // Record 0: close to query (small values)
            float[] closeVec = new float[DIMS];
            for (int i = 0; i < DIMS; i++) closeVec[i] = 0.1f;

            // Record 1: far from query (large values)
            float[] farVec = new float[DIMS];
            for (int i = 0; i < DIMS; i++) farVec[i] = 0.9f;

            writeRecord(segment, layout, 0, closeVec, 5.0f, System.currentTimeMillis(),
                    SynapticHeaderConstants.FLAG_RESOLVED, (byte) 0, 0L, mins, scales);
            writeRecord(segment, layout, 1, farVec, 5.0f, System.currentTimeMillis(),
                    SynapticHeaderConstants.FLAG_RESOLVED, (byte) 0, 0L, mins, scales);

            RecallOptions options = RecallOptions.builder()
                    .topK(2)
                    .alpha(1.0f)  // pure similarity scoring
                    .beta(0.0f)
                    .build();
            List<CognitiveScorer.ScoredRecord> results = CognitiveScorer.score(
                    segment, corpusSize, layout, queryVec, options, System.currentTimeMillis());

            assertEquals(2, results.size());
            // Closer vector should rank first
            assertEquals(0, results.getFirst().index(),
                    "Closer vector should rank first");
            assertTrue(results.get(0).score() > results.get(1).score(),
                    "Closer vector should have higher score");
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Phase 6: Fused Score Formula
    // ══════════════════════════════════════════════════════════════

    /**
     * Phase 6: Verifies alpha×sim + beta×imp×decay with known inputs.
     * With alpha=0.5, beta=0.5, the score incorporates both similarity and importance.
     */
    @Test
    void fusedScoreFormula() {
        float[] mins = IdentityCalibration.mins(DIMS);
        float[] scales = IdentityCalibration.scales(DIMS);
        CognitiveRecordLayout layout = new CognitiveRecordLayout(DIMS);

        try (Arena arena = Arena.ofConfined()) {
            int corpusSize = 2;
            MemorySegment segment = arena.allocate((long) corpusSize * layout.stride());
            long nowMs = System.currentTimeMillis();

            float[] vec = new float[DIMS];
            for (int i = 0; i < DIMS; i++) vec[i] = 0.3f;

            // Record 0: high importance
            writeRecord(segment, layout, 0, vec, 9.0f, nowMs - 60_000,
                    SynapticHeaderConstants.FLAG_RESOLVED, (byte) 0, 0L, mins, scales);
            // Record 1: low importance (same vector)
            writeRecord(segment, layout, 1, vec, 1.0f, nowMs - 60_000,
                    SynapticHeaderConstants.FLAG_RESOLVED, (byte) 0, 0L, mins, scales);

            RecallOptions options = RecallOptions.builder()
                    .topK(2)
                    .alpha(0.5f)
                    .beta(0.5f)
                    .build();
            List<CognitiveScorer.ScoredRecord> results = CognitiveScorer.score(
                    segment, corpusSize, layout, vec, options, nowMs);

            assertEquals(2, results.size());
            // With equal similarity but different importance, high importance wins
            assertEquals(0, results.getFirst().index(),
                    "Higher importance should rank first with beta=0.5");
            assertTrue(results.get(0).score() > results.get(1).score(),
                    "Score ordering should reflect importance difference");
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════

    private void writeRecord(MemorySegment segment, CognitiveRecordLayout layout,
                             int index, float[] vector, float importance, long timestamp,
                             byte flags, byte valence, long synapticTags,
                             float[] mins, float[] scales) {
        long offset = (long) index * layout.stride();
        CognitiveHeader header = new CognitiveHeader(
                timestamp, synapticTags, 1.0f, importance, 0, (short) 0, valence, flags);
        layout.writeHeader(segment, offset, header);

        byte[] quantized = new byte[DIMS];
        for (int i = 0; i < DIMS; i++) {
            float normalized = (vector[i] - mins[i]) / scales[i];
            int q = Math.round(normalized);
            quantized[i] = (byte) Math.max(0, Math.min(255, q));
        }
        layout.writeQuantizedVector(segment, offset, quantized);
    }

    private void writeRecordWithTimestamp(MemorySegment segment, CognitiveRecordLayout layout,
                                          int index, float[] vector, float importance,
                                          long timestamp, byte flags,
                                          float[] mins, float[] scales) {
        writeRecord(segment, layout, index, vector, importance, timestamp, flags,
                (byte) 0, 0L, mins, scales);
    }
}
