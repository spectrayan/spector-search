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
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.spectrayan.spector.bench.cognitive.model.ScoredResult;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout.CognitiveHeader;
import com.spectrayan.spector.memory.synapse.IdentityCalibration;
import com.spectrayan.spector.memory.synapse.SynapticHeaderConstants;

/**
 * Unit tests for {@link BaselineRetriever} verifying:
 * <ul>
 *   <li>Results are ordered by descending similarity</li>
 *   <li>Tombstoned records are excluded from results</li>
 *   <li>Only L2 distance affects ranking (other header fields are irrelevant)</li>
 * </ul>
 */
class BaselineRetrieverTest {

    // Use 8 dimensions so that stride (64B header + 8B vector = 72) is 8-byte aligned
    private static final int DIMS = 8;

    private Arena arena;
    private CognitiveRecordLayout layout;
    private float[] mins;
    private float[] scales;

    @BeforeEach
    void setUp() {
        arena = Arena.ofConfined();
        layout = new CognitiveRecordLayout(DIMS);
        mins = IdentityCalibration.mins(DIMS);
        scales = IdentityCalibration.scales(DIMS);
    }

    // ══════════════════════════════════════════════════════════════
    // Test: Results ordered by descending similarity
    // ══════════════════════════════════════════════════════════════

    @Test
    void retrieve_returnsResultsOrderedByDescendingSimilarity() {
        // Create 3 records with known vectors at different distances from query
        int recordCount = 3;
        MemorySegment segment = allocateSegment(recordCount);

        // Query vector: [0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5]
        float[] query = {0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f};

        // Record 0: close to query → distance ~ 0
        writeRecord(segment, 0, fill(0.5f), (byte) 0);
        // Record 1: far from query → large distance
        writeRecord(segment, 1, fill(-0.5f), (byte) 0);
        // Record 2: moderately close
        writeRecord(segment, 2, fill(0.3f), (byte) 0);

        String[] ids = {"mem-0", "mem-1", "mem-2"};
        BaselineRetriever retriever = new BaselineRetriever(
                segment, layout, recordCount, mins, scales, ids);

        List<ScoredResult> results = retriever.retrieve(query, 3);

        assertEquals(3, results.size());
        // Scores must be strictly descending
        for (int i = 0; i < results.size() - 1; i++) {
            assertTrue(results.get(i).score() >= results.get(i + 1).score(),
                    "Results not in descending order at index " + i +
                            ": " + results.get(i).score() + " < " + results.get(i + 1).score());
        }
        // Closest record should be first
        assertEquals("mem-0", results.get(0).memoryId());
        // Farthest record should be last
        assertEquals("mem-1", results.get(results.size() - 1).memoryId());
    }

    // ══════════════════════════════════════════════════════════════
    // Test: Tombstoned records excluded
    // ══════════════════════════════════════════════════════════════

    @Test
    void retrieve_excludesTombstonedRecords() {
        int recordCount = 3;
        MemorySegment segment = allocateSegment(recordCount);

        float[] query = fill(0.0f);

        // Record 0: alive, close to query
        writeRecord(segment, 0, fill(0.0f), (byte) 0);
        // Record 1: TOMBSTONED, even closer (should be excluded)
        writeRecord(segment, 1, fill(0.0f),
                SynapticHeaderConstants.FLAG_TOMBSTONE);
        // Record 2: alive, farther away
        writeRecord(segment, 2, fill(0.5f), (byte) 0);

        String[] ids = {"mem-0", "mem-1", "mem-2"};
        BaselineRetriever retriever = new BaselineRetriever(
                segment, layout, recordCount, mins, scales, ids);

        List<ScoredResult> results = retriever.retrieve(query, 10);

        // Only 2 non-tombstoned records should appear
        assertEquals(2, results.size());
        // Tombstoned record should not be in results
        assertTrue(results.stream().noneMatch(r -> r.memoryId().equals("mem-1")),
                "Tombstoned record 'mem-1' should not appear in results");
    }

    // ══════════════════════════════════════════════════════════════
    // Test: Only L2 distance affects ranking
    // ══════════════════════════════════════════════════════════════

    @Test
    void retrieve_onlyL2DistanceAffectsRanking_importanceAndValenceIgnored() {
        int recordCount = 3;
        MemorySegment segment = allocateSegment(recordCount);

        float[] query = fill(0.0f);

        // Record 0: far from query, but HIGH importance (0.99) and HIGH valence (127)
        writeRecordWithAnnotations(segment, 0, fill(0.8f),
                (byte) 0, 0.99f, (byte) 127);
        // Record 1: close to query, but LOW importance (0.01) and LOW valence (-128)
        writeRecordWithAnnotations(segment, 1, fill(0.0f),
                (byte) 0, 0.01f, (byte) -128);
        // Record 2: medium distance, MEDIUM importance (0.5) and neutral valence
        writeRecordWithAnnotations(segment, 2, fill(0.4f),
                (byte) 0, 0.5f, (byte) 0);

        String[] ids = {"mem-far", "mem-close", "mem-mid"};
        BaselineRetriever retriever = new BaselineRetriever(
                segment, layout, recordCount, mins, scales, ids);

        List<ScoredResult> results = retriever.retrieve(query, 3);

        assertEquals(3, results.size());
        // Despite low importance/valence, the closest vector should be ranked first
        assertEquals("mem-close", results.get(0).memoryId());
        // Despite high importance/valence, the farthest vector should be ranked last
        assertEquals("mem-far", results.get(results.size() - 1).memoryId());
    }

    // ══════════════════════════════════════════════════════════════
    // Test: topK limits results
    // ══════════════════════════════════════════════════════════════

    @Test
    void retrieve_returnsAtMostTopKResults() {
        int recordCount = 5;
        MemorySegment segment = allocateSegment(recordCount);

        float[] query = fill(0.0f);
        String[] ids = new String[recordCount];

        for (int i = 0; i < recordCount; i++) {
            float val = i * 0.2f;
            writeRecord(segment, i, fill(val), (byte) 0);
            ids[i] = "mem-" + i;
        }

        BaselineRetriever retriever = new BaselineRetriever(
                segment, layout, recordCount, mins, scales, ids);

        List<ScoredResult> results = retriever.retrieve(query, 2);

        assertEquals(2, results.size());
        // Should contain the 2 closest: mem-0 and mem-1
        assertEquals("mem-0", results.get(0).memoryId());
        assertEquals("mem-1", results.get(1).memoryId());
    }

    // ══════════════════════════════════════════════════════════════
    // Test: All scores are positive (similarity = 1/(1+L2) > 0)
    // ══════════════════════════════════════════════════════════════

    @Test
    void retrieve_allScoresArePositive() {
        int recordCount = 3;
        MemorySegment segment = allocateSegment(recordCount);

        float[] query = fill(1.0f);

        writeRecord(segment, 0, fill(-1.0f), (byte) 0);
        writeRecord(segment, 1, fill(0.0f), (byte) 0);
        writeRecord(segment, 2, fill(1.0f), (byte) 0);

        String[] ids = {"mem-0", "mem-1", "mem-2"};
        BaselineRetriever retriever = new BaselineRetriever(
                segment, layout, recordCount, mins, scales, ids);

        List<ScoredResult> results = retriever.retrieve(query, 3);

        for (ScoredResult result : results) {
            assertTrue(result.score() > 0.0f,
                    "Score should be positive but was: " + result.score());
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Helper methods
    // ══════════════════════════════════════════════════════════════

    private MemorySegment allocateSegment(int recordCount) {
        long totalBytes = (long) recordCount * layout.stride();
        // Allocate with 32-byte alignment for SIMD compatibility and proper field access
        return arena.allocate(totalBytes, 32);
    }

    /**
     * Creates a DIMS-length array filled with the given value.
     */
    private float[] fill(float value) {
        float[] vec = new float[DIMS];
        java.util.Arrays.fill(vec, value);
        return vec;
    }

    /**
     * Writes a record with the given vector and flags at the specified index.
     * Sets minimal header fields; importance and valence default to 0.
     */
    private void writeRecord(MemorySegment segment, int index, float[] vector, byte flags) {
        writeRecordWithAnnotations(segment, index, vector, flags, 0.5f, (byte) 0);
    }

    /**
     * Writes a record with full cognitive annotations for testing that only L2 matters.
     */
    private void writeRecordWithAnnotations(MemorySegment segment, int index,
                                             float[] vector, byte flags,
                                             float importance, byte valence) {
        long offset = (long) index * layout.stride();

        // Write header using CognitiveHeader
        CognitiveHeader header = new CognitiveHeader(
                System.currentTimeMillis(), // timestamp
                0L,                         // synaptic tags
                1.0f,                       // exact norm
                importance,                 // importance
                0,                          // recall count
                (short) 0,                  // centroid ID
                valence,                    // valence
                flags                       // flags
        );
        layout.writeHeader(segment, offset, header);

        // Quantize and write vector using identity calibration:
        // Identity maps float [-1, 1] to byte [0, 255]: byte = round((float - (-1)) / (2/255))
        byte[] quantized = new byte[DIMS];
        for (int i = 0; i < DIMS; i++) {
            float normalized = (vector[i] - mins[i]) / scales[i];
            int q = Math.round(normalized);
            quantized[i] = (byte) Math.max(0, Math.min(255, q));
        }
        layout.writeQuantizedVector(segment, offset, quantized);
    }
}
