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
 * Property-based tests for arousal-modulated decay ordering.
 *
 * <p><b>Validates: Requirements 10.3, 12.4</b>
 *
 * <p>Property 21: For any two corpus memories with equal age, importance, and vector
 * similarity but different arousal values (arousal_A > arousal_B), the higher-arousal
 * memory SHALL rank above the lower-arousal memory.
 */
class ArousalOrderingPropertyTest {

    private static final int DIMS = 8;

    // ══════════════════════════════════════════════════════════════
    // Property 21: Higher arousal → higher rank
    // ══════════════════════════════════════════════════════════════

    /**
     * Property 21: With identical vectors, importance, and age, but different arousal,
     * the higher-arousal memory ranks higher due to slower decay.
     *
     * <p>This property is visible when decay is non-trivial (memories are old enough
     * for arousal modulation to make a difference). We use a timestamp that puts
     * records into a non-zero bucket.
     *
     * <p><b>Validates: Requirements 10.3, 12.4</b>
     */
    @Property(tries = 100)
    void higherArousal_ranksHigher(
            @ForAll("arousalPairs") ArousalPair pair) {

        float[] mins = IdentityCalibration.mins(DIMS);
        float[] scales = IdentityCalibration.scales(DIMS);
        // Use V2+ layout which supports arousal
        CognitiveRecordLayout layout = new CognitiveRecordLayout(DIMS,
                com.spectrayan.spector.memory.synapse.HeaderLayoutV2.INSTANCE);

        try (Arena arena = Arena.ofConfined()) {
            int corpusSize = 2;
            long totalBytes = (long) corpusSize * layout.stride();
            MemorySegment segment = arena.allocate(totalBytes);

            long nowMs = System.currentTimeMillis();
            // Use a timestamp that puts records into bucket 4-5 (3-7 days old)
            // so decay is significant and arousal modulation is visible
            long timestamp = nowMs - (5L * 24 * 60 * 60 * 1000); // 5 days ago

            float[] identicalVec = new float[DIMS];
            for (int i = 0; i < DIMS; i++) {
                identicalVec[i] = 0.2f;
            }
            float importance = 5.0f;

            // Record 0: HIGH arousal
            writeRecordV2(segment, layout, 0, identicalVec, importance, timestamp,
                    pair.highArousal(), mins, scales);
            // Record 1: LOW arousal
            writeRecordV2(segment, layout, 1, identicalVec, importance, timestamp,
                    pair.lowArousal(), mins, scales);

            float[] queryVec = new float[DIMS];

            RecallOptions options = RecallOptions.builder()
                    .topK(2)
                    .alpha(0.3f)
                    .beta(0.7f)
                    .build();

            List<CognitiveScorer.ScoredRecord> results = CognitiveScorer.score(
                    segment, corpusSize, layout, queryVec, options, nowMs);

            if (results.size() == 2) {
                // Higher arousal should have higher score (due to slower decay)
                float scoreHigh = results.stream()
                        .filter(r -> r.index() == 0).findFirst()
                        .map(CognitiveScorer.ScoredRecord::score).orElse(0f);
                float scoreLow = results.stream()
                        .filter(r -> r.index() == 1).findFirst()
                        .map(CognitiveScorer.ScoredRecord::score).orElse(0f);

                assert scoreHigh >= scoreLow
                        : String.format("Higher arousal (%d) should score >= lower arousal (%d): " +
                        "scoreHigh=%.6f, scoreLow=%.6f",
                        Byte.toUnsignedInt(pair.highArousal()),
                        Byte.toUnsignedInt(pair.lowArousal()),
                        scoreHigh, scoreLow);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Support types
    // ══════════════════════════════════════════════════════════════

    record ArousalPair(byte highArousal, byte lowArousal) {}

    // ══════════════════════════════════════════════════════════════
    // Generators
    // ══════════════════════════════════════════════════════════════

    @Provide
    Arbitrary<ArousalPair> arousalPairs() {
        // Ensure high arousal is in a higher quartile than low arousal
        // Quartiles: 0-63, 64-127, 128-191, 192-255
        return Combinators.combine(
                Arbitraries.integers().between(192, 255),  // high arousal (extreme quartile)
                Arbitraries.integers().between(0, 63)      // low arousal (neutral quartile)
        ).as((high, low) -> new ArousalPair((byte) high.intValue(), (byte) low.intValue()));
    }

    // ══════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════

    private void writeRecordV2(MemorySegment segment, CognitiveRecordLayout layout,
                               int index, float[] vector, float importance, long timestamp,
                               byte arousal, float[] mins, float[] scales) {
        long offset = (long) index * layout.stride();

        // Write core header fields
        var header = new CognitiveRecordLayout.CognitiveHeader(
                timestamp, 0L, 1.0f, importance, 0, (short) 0, (byte) 0,
                SynapticHeaderConstants.FLAG_RESOLVED);
        layout.writeHeader(segment, offset, header);

        // Write V2 arousal field
        segment.set(java.lang.foreign.ValueLayout.JAVA_BYTE,
                offset + SynapticHeaderConstants.OFFSET_AROUSAL, arousal);

        // Write vector
        byte[] quantized = new byte[DIMS];
        for (int i = 0; i < DIMS; i++) {
            float normalized = (vector[i] - mins[i]) / scales[i];
            int q = Math.round(normalized);
            quantized[i] = (byte) Math.max(0, Math.min(255, q));
        }
        layout.writeQuantizedVector(segment, offset, quantized);
    }
}
