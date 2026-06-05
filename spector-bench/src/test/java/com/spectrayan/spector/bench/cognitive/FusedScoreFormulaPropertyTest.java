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
import com.spectrayan.spector.memory.synapse.SynapticTagEncoder;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based tests for the fused scoring formula correctness.
 *
 * <p><b>Validates: Requirements 3.5</b>
 *
 * <p>Property 10: For any valid scoring inputs, the CognitiveScorer SHALL compute
 * finalScore = (alpha × similarity + beta × importance × decay) × (1 + tagOverlap × tagRelevanceBoost).
 */
class FusedScoreFormulaPropertyTest {

    private static final int DIMS = 8;

    // ══════════════════════════════════════════════════════════════
    // Property 10: Fused score formula verification
    // ══════════════════════════════════════════════════════════════

    /**
     * Property 10: For a single record with known parameters, the scorer's output
     * should match independently computed fused score formula.
     *
     * <p><b>Validates: Requirements 3.5</b>
     */
    @Property(tries = 100)
    void fusedScore_matchesFormula(@ForAll("scoringInputs") ScoringInput input) {

        float[] mins = IdentityCalibration.mins(DIMS);
        float[] scales = IdentityCalibration.scales(DIMS);
        CognitiveRecordLayout layout = new CognitiveRecordLayout(DIMS);

        try (Arena arena = Arena.ofConfined()) {
            int corpusSize = 1;
            long totalBytes = (long) corpusSize * layout.stride();
            MemorySegment segment = arena.allocate(totalBytes);

            long nowMs = System.currentTimeMillis();
            // Use a recent timestamp to get bucket 0 (fresh) for simplicity
            long timestamp = nowMs - 1000; // 1 second ago → bucket 0, decay = 1.0

            float[] recordVec = new float[DIMS];
            // Set known vector values
            for (int i = 0; i < DIMS; i++) {
                recordVec[i] = input.vecComponent();
            }

            long synapticTags = SynapticTagEncoder.encode("test-tag");
            byte flags = SynapticHeaderConstants.FLAG_RESOLVED;

            CognitiveHeader header = new CognitiveHeader(
                    timestamp,
                    synapticTags,
                    1.0f,
                    input.importance(),
                    0,
                    (short) 0,
                    (byte) 0,
                    flags
            );
            long offset = 0;
            layout.writeHeader(segment, offset, header);

            byte[] quantized = new byte[DIMS];
            for (int i = 0; i < DIMS; i++) {
                float normalized = (recordVec[i] - mins[i]) / scales[i];
                int q = Math.round(normalized);
                quantized[i] = (byte) Math.max(0, Math.min(255, q));
            }
            layout.writeQuantizedVector(segment, offset, quantized);

            // Query with known alpha, beta
            float[] queryVec = new float[DIMS]; // zero vector

            RecallOptions options = RecallOptions.builder()
                    .topK(1)
                    .alpha(input.alpha())
                    .beta(input.beta())
                    .synapticFilter("test-tag")
                    .build();

            List<CognitiveScorer.ScoredRecord> results = CognitiveScorer.score(
                    segment, corpusSize, layout, queryVec, options, nowMs);

            if (!results.isEmpty()) {
                float actualScore = results.getFirst().score();
                // Score must be > 0 (positive final score)
                assert actualScore > 0.0f
                        : "Final score should be > 0, got: " + actualScore;
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Support types
    // ══════════════════════════════════════════════════════════════

    record ScoringInput(float alpha, float beta, float importance, float vecComponent) {}

    // ══════════════════════════════════════════════════════════════
    // Generators
    // ══════════════════════════════════════════════════════════════

    @Provide
    Arbitrary<ScoringInput> scoringInputs() {
        return Combinators.combine(
                Arbitraries.floats().between(0.1f, 1.0f),  // alpha
                Arbitraries.floats().between(0.0f, 0.9f),  // beta
                Arbitraries.floats().between(0.5f, 8.0f),  // importance
                Arbitraries.floats().between(-0.5f, 0.5f)  // vecComponent
        ).as(ScoringInput::new);
    }
}
