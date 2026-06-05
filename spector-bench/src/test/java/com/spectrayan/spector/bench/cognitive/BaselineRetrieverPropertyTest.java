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

import com.spectrayan.spector.bench.cognitive.model.ScoredResult;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout.CognitiveHeader;
import com.spectrayan.spector.memory.synapse.IdentityCalibration;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * Property-based tests for {@link BaselineRetriever}.
 *
 * <p><b>Validates: Requirements 2.2</b>
 *
 * <p>Property 3: For any corpus of memory records and any query vector, the
 * BaselineRetriever SHALL return results strictly ordered by ascending L2 distance
 * (equivalently, descending similarity = 1/(1+d)), with no other signal affecting
 * rank order.
 */
class BaselineRetrieverPropertyTest {

    private static final int DIMS = 8;

    // ══════════════════════════════════════════════════════════════
    // Property 3: Results strictly ordered by ascending L2 distance
    // ══════════════════════════════════════════════════════════════

    /**
     * Property 3: For any random corpus and query vector, the BaselineRetriever
     * returns results strictly ordered by ascending L2 distance (descending similarity).
     *
     * <p><b>Validates: Requirements 2.2</b>
     */
    @Property(tries = 100)
    void resultsAreStrictlyOrderedByAscendingL2Distance(
            @ForAll("queryVectors") float[] queryVector,
            @ForAll("corpusSizes") @IntRange(min = 2, max = 30) int corpusSize,
            @ForAll("topKValues") @IntRange(min = 1, max = 10) int topK) {

        float[] mins = IdentityCalibration.mins(DIMS);
        float[] scales = IdentityCalibration.scales(DIMS);
        CognitiveRecordLayout layout = new CognitiveRecordLayout(DIMS);

        try (Arena arena = Arena.ofConfined()) {
            long totalBytes = (long) corpusSize * layout.stride();
            MemorySegment segment = arena.allocate(totalBytes);

            String[] ids = new String[corpusSize];
            for (int i = 0; i < corpusSize; i++) {
                ids[i] = "mem-" + i;
                // Generate a random-ish vector based on index and query to ensure variety
                float[] vec = generateDeterministicVector(i, corpusSize);
                writeRecord(segment, layout, i, vec, (byte) 0, mins, scales);
            }

            BaselineRetriever retriever = new BaselineRetriever(
                    segment, layout, corpusSize, mins, scales, ids);

            List<ScoredResult> results = retriever.retrieve(queryVector, topK);

            // Verify descending score order (ascending L2 distance)
            for (int i = 0; i < results.size() - 1; i++) {
                float scoreA = results.get(i).score();
                float scoreB = results.get(i + 1).score();
                assert scoreA >= scoreB
                        : String.format("Results not ordered: score[%d]=%.6f < score[%d]=%.6f",
                        i, scoreA, i + 1, scoreB);
            }

            // Verify that the L2 distances are ascending (since similarity = 1/(1+d))
            for (int i = 0; i < results.size() - 1; i++) {
                float distA = (1.0f / results.get(i).score()) - 1.0f;
                float distB = (1.0f / results.get(i + 1).score()) - 1.0f;
                assert distA <= distB + 1e-5f
                        : String.format("L2 distances not ascending: dist[%d]=%.6f > dist[%d]=%.6f",
                        i, distA, i + 1, distB);
            }
        }
    }

    /**
     * Property 3b: Scores are always in (0, 1] range since similarity = 1/(1+L2)
     * where L2 >= 0.
     *
     * <p><b>Validates: Requirements 2.2</b>
     */
    @Property(tries = 100)
    void allScoresAreInValidRange(
            @ForAll("queryVectors") float[] queryVector,
            @ForAll("corpusSizes") @IntRange(min = 1, max = 20) int corpusSize) {

        float[] mins = IdentityCalibration.mins(DIMS);
        float[] scales = IdentityCalibration.scales(DIMS);
        CognitiveRecordLayout layout = new CognitiveRecordLayout(DIMS);

        try (Arena arena = Arena.ofConfined()) {
            long totalBytes = (long) corpusSize * layout.stride();
            MemorySegment segment = arena.allocate(totalBytes);

            String[] ids = new String[corpusSize];
            for (int i = 0; i < corpusSize; i++) {
                ids[i] = "mem-" + i;
                float[] vec = generateDeterministicVector(i, corpusSize);
                writeRecord(segment, layout, i, vec, (byte) 0, mins, scales);
            }

            BaselineRetriever retriever = new BaselineRetriever(
                    segment, layout, corpusSize, mins, scales, ids);

            List<ScoredResult> results = retriever.retrieve(queryVector, corpusSize);

            for (ScoredResult result : results) {
                assert result.score() > 0.0f && result.score() <= 1.0f
                        : "Score out of valid range (0, 1]: " + result.score();
            }
        }
    }

    /**
     * Property 3c: Result count never exceeds topK.
     *
     * <p><b>Validates: Requirements 2.2</b>
     */
    @Property(tries = 100)
    void resultCountNeverExceedsTopK(
            @ForAll("queryVectors") float[] queryVector,
            @ForAll("corpusSizes") @IntRange(min = 1, max = 30) int corpusSize,
            @ForAll("topKValues") @IntRange(min = 1, max = 10) int topK) {

        float[] mins = IdentityCalibration.mins(DIMS);
        float[] scales = IdentityCalibration.scales(DIMS);
        CognitiveRecordLayout layout = new CognitiveRecordLayout(DIMS);

        try (Arena arena = Arena.ofConfined()) {
            long totalBytes = (long) corpusSize * layout.stride();
            MemorySegment segment = arena.allocate(totalBytes);

            String[] ids = new String[corpusSize];
            for (int i = 0; i < corpusSize; i++) {
                ids[i] = "mem-" + i;
                float[] vec = generateDeterministicVector(i, corpusSize);
                writeRecord(segment, layout, i, vec, (byte) 0, mins, scales);
            }

            BaselineRetriever retriever = new BaselineRetriever(
                    segment, layout, corpusSize, mins, scales, ids);

            List<ScoredResult> results = retriever.retrieve(queryVector, topK);

            int expectedMax = Math.min(topK, corpusSize);
            assert results.size() <= expectedMax
                    : String.format("Too many results: got %d, expected at most %d",
                    results.size(), expectedMax);
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

    @Provide
    Arbitrary<Integer> corpusSizes() {
        return Arbitraries.integers().between(2, 30);
    }

    @Provide
    Arbitrary<Integer> topKValues() {
        return Arbitraries.integers().between(1, 10);
    }

    // ══════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════

    /**
     * Generates a deterministic vector based on index to ensure reproducibility
     * while maintaining variety across the corpus.
     */
    private float[] generateDeterministicVector(int index, int corpusSize) {
        float[] vec = new float[DIMS];
        for (int d = 0; d < DIMS; d++) {
            // Spread vectors across [-1, 1] range based on index
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
                0L,          // synaptic tags
                1.0f,        // exact norm
                0.5f,        // importance (irrelevant for baseline)
                0,           // recall count
                (short) 0,   // centroid ID
                (byte) 0,    // valence (irrelevant for baseline)
                flags
        );
        layout.writeHeader(segment, offset, header);

        // Quantize vector using identity calibration
        byte[] quantized = new byte[DIMS];
        for (int i = 0; i < DIMS; i++) {
            float normalized = (vector[i] - mins[i]) / scales[i];
            int q = Math.round(normalized);
            quantized[i] = (byte) Math.max(0, Math.min(255, q));
        }
        layout.writeQuantizedVector(segment, offset, quantized);
    }
}
