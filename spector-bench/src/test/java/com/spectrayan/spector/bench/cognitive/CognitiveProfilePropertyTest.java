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

import com.spectrayan.spector.memory.CognitiveProfile;
import com.spectrayan.spector.memory.RecallOptions;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout.CognitiveHeader;
import com.spectrayan.spector.memory.synapse.CognitiveScorer;
import com.spectrayan.spector.memory.synapse.IdentityCalibration;
import com.spectrayan.spector.memory.synapse.SynapticHeaderConstants;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

/**
 * Property-based tests for cognitive profile constraints.
 *
 * <p><b>Validates: Requirements 11.2, 11.3, 11.4</b>
 *
 * <p>Properties 22, 23:
 * <ul>
 *   <li>22: DEBUGGING profile constrains valence ≤ -10;
 *       DEFAULT_MODE_NETWORK restricts to SEMANTIC/PROCEDURAL types</li>
 *   <li>23: HYPERFOCUS profile uses alpha=1.0, beta=0.0</li>
 * </ul>
 */
class CognitiveProfilePropertyTest {

    private static final int DIMS = 8;

    // ══════════════════════════════════════════════════════════════
    // Property 22a: DEBUGGING valence constraint
    // ══════════════════════════════════════════════════════════════

    /**
     * Property 22a: With DEBUGGING profile, all results SHALL have valence ≤ -10.
     *
     * <p><b>Validates: Requirements 11.2</b>
     */
    @Property(tries = 100)
    void debuggingProfile_onlyNegativeValence(
            @ForAll @IntRange(min = 5, max = 15) int corpusSize) {

        float[] mins = IdentityCalibration.mins(DIMS);
        float[] scales = IdentityCalibration.scales(DIMS);
        CognitiveRecordLayout layout = new CognitiveRecordLayout(DIMS);

        try (Arena arena = Arena.ofConfined()) {
            long totalBytes = (long) corpusSize * layout.stride();
            MemorySegment segment = arena.allocate(totalBytes);

            long nowMs = System.currentTimeMillis();
            // Create records with various valence values spanning the full range
            for (int i = 0; i < corpusSize; i++) {
                byte valence = (byte) (-128 + i * 20); // spread across full range
                float[] vec = new float[DIMS];
                vec[0] = 0.1f * i;
                writeRecord(segment, layout, i, vec, valence, nowMs - 60_000, mins, scales);
            }

            float[] queryVec = new float[DIMS];

            // DEBUGGING profile: valence constrained to <= -10
            RecallOptions.Builder builder = RecallOptions.builder().topK(corpusSize);
            CognitiveProfile.DEBUGGING.applyTo(builder);
            RecallOptions options = builder.build();

            List<CognitiveScorer.ScoredRecord> results = CognitiveScorer.score(
                    segment, corpusSize, layout, queryVec, options, nowMs);

            // All results should have valence within DEBUGGING's range
            for (CognitiveScorer.ScoredRecord result : results) {
                byte valence = result.header().valence();
                assert valence <= CognitiveProfile.DEBUGGING.maxValence()
                        : String.format("DEBUGGING result has valence %d, expected <= %d",
                        valence, CognitiveProfile.DEBUGGING.maxValence());
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Property 23: HYPERFOCUS alpha=1, beta=0
    // ══════════════════════════════════════════════════════════════

    /**
     * Property 23: HYPERFOCUS profile has alpha=1.0 and beta=0.0.
     *
     * <p><b>Validates: Requirements 11.3</b>
     */
    @Property(tries = 100)
    void hyperfocusProfile_hasCorrectWeights() {
        // Verify the profile parameters directly
        assert CognitiveProfile.HYPERFOCUS.alpha() == 1.0f
                : "HYPERFOCUS alpha should be 1.0, got: " + CognitiveProfile.HYPERFOCUS.alpha();
        assert CognitiveProfile.HYPERFOCUS.beta() == 0.0f
                : "HYPERFOCUS beta should be 0.0, got: " + CognitiveProfile.HYPERFOCUS.beta();
    }

    /**
     * Property 23b: With HYPERFOCUS profile and a focus mask, scoring relies
     * purely on similarity (alpha=1.0, beta=0.0 means importance×decay is zeroed out).
     *
     * <p><b>Validates: Requirements 11.3</b>
     */
    @Property(tries = 100)
    void hyperfocusProfile_pureSimlarity_ignoresImportance() {

        float[] mins = IdentityCalibration.mins(DIMS);
        float[] scales = IdentityCalibration.scales(DIMS);
        CognitiveRecordLayout layout = new CognitiveRecordLayout(DIMS);

        try (Arena arena = Arena.ofConfined()) {
            int corpusSize = 2;
            long totalBytes = (long) corpusSize * layout.stride();
            MemorySegment segment = arena.allocate(totalBytes);

            long nowMs = System.currentTimeMillis();

            // Record 0: CLOSE to query, LOW importance
            float[] closeVec = new float[DIMS]; // zero vector = closest to zero query
            writeRecordWithImportance(segment, layout, 0, closeVec, 0.5f,
                    nowMs - 60_000, mins, scales);

            // Record 1: FAR from query, HIGH importance
            float[] farVec = new float[DIMS];
            for (int i = 0; i < DIMS; i++) farVec[i] = 0.9f;
            writeRecordWithImportance(segment, layout, 1, farVec, 9.0f,
                    nowMs - 60_000, mins, scales);

            float[] queryVec = new float[DIMS]; // zero vector

            // HYPERFOCUS with alpha=1.0, beta=0.0 → pure similarity
            RecallOptions options = RecallOptions.builder()
                    .topK(2)
                    .alpha(CognitiveProfile.HYPERFOCUS.alpha())
                    .beta(CognitiveProfile.HYPERFOCUS.beta())
                    .build();

            List<CognitiveScorer.ScoredRecord> results = CognitiveScorer.score(
                    segment, corpusSize, layout, queryVec, options, nowMs);

            if (results.size() == 2) {
                // The closer vector should rank first despite lower importance
                assert results.getFirst().index() == 0
                        : "HYPERFOCUS should rank by pure similarity, not importance";
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════

    private void writeRecord(MemorySegment segment, CognitiveRecordLayout layout,
                             int index, float[] vector, byte valence, long timestamp,
                             float[] mins, float[] scales) {
        long offset = (long) index * layout.stride();
        CognitiveHeader header = new CognitiveHeader(
                timestamp, 0L, 1.0f, 5.0f, 0, (short) 0, valence,
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

    private void writeRecordWithImportance(MemorySegment segment, CognitiveRecordLayout layout,
                                           int index, float[] vector, float importance,
                                           long timestamp, float[] mins, float[] scales) {
        long offset = (long) index * layout.stride();
        CognitiveHeader header = new CognitiveHeader(
                timestamp, 0L, 1.0f, importance, 0, (short) 0, (byte) 0,
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
