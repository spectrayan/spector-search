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
 * Property-based tests for result ordering and bounds invariant.
 *
 * <p><b>Validates: Requirements 3.7</b>
 *
 * <p>Property 12: For any query execution returning N results, the results SHALL be
 * sorted in strictly descending score order, all scores SHALL be > 0.0, and N ≤ topK.
 */
class ResultOrderingPropertyTest {

    private static final int DIMS = 8;

    // ══════════════════════════════════════════════════════════════
    // Property 12: Results sorted descending, scores > 0, count ≤ topK
    // ══════════════════════════════════════════════════════════════

    /**
     * Property 12: CognitiveScorer results are sorted in descending score order,
     * all scores are positive, and result count never exceeds topK.
     *
     * <p><b>Validates: Requirements 3.7</b>
     */
    @Property(tries = 100)
    void results_sortedDescending_positiveScores_withinTopK(
            @ForAll("queryVectors") float[] queryVector,
            @ForAll @IntRange(min = 3, max = 20) int corpusSize,
            @ForAll @IntRange(min = 1, max = 10) int topK) {

        float[] mins = IdentityCalibration.mins(DIMS);
        float[] scales = IdentityCalibration.scales(DIMS);
        CognitiveRecordLayout layout = new CognitiveRecordLayout(DIMS);

        try (Arena arena = Arena.ofConfined()) {
            long totalBytes = (long) corpusSize * layout.stride();
            MemorySegment segment = arena.allocate(totalBytes);

            long nowMs = System.currentTimeMillis();
            for (int i = 0; i < corpusSize; i++) {
                float[] vec = generateDeterministicVector(i, corpusSize);
                long timestamp = nowMs - (long) i * 3600_000; // spaced 1 hour apart
                writeRecord(segment, layout, i, vec, timestamp, mins, scales);
            }

            RecallOptions options = RecallOptions.builder()
                    .topK(topK)
                    .build();

            List<CognitiveScorer.ScoredRecord> results = CognitiveScorer.score(
                    segment, corpusSize, layout, queryVector, options, nowMs);

            // Check count ≤ topK
            assert results.size() <= topK
                    : String.format("Result count %d exceeds topK=%d", results.size(), topK);

            // Check descending order and positive scores
            for (int i = 0; i < results.size(); i++) {
                float score = results.get(i).score();
                assert score > 0.0f
                        : String.format("Score at position %d is not positive: %f", i, score);

                if (i > 0) {
                    float prevScore = results.get(i - 1).score();
                    assert prevScore >= score
                            : String.format("Not descending: score[%d]=%f < score[%d]=%f",
                            i - 1, prevScore, i, score);
                }
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
                             int index, float[] vector, long timestamp,
                             float[] mins, float[] scales) {
        long offset = (long) index * layout.stride();

        CognitiveHeader header = new CognitiveHeader(
                timestamp,
                0L,
                1.0f,
                3.0f,
                1,
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
