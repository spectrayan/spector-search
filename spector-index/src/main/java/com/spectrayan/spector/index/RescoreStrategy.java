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
package com.spectrayan.spector.index;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.IntFunction;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Rescore strategy that retrieves oversampled candidates from a quantized index
 * and re-ranks them using exact float32 distance computation.
 *
 * <p>This compensates for recall loss caused by aggressive quantization (INT4, INT2)
 * by retrieving more candidates cheaply from the compressed index and then selecting
 * the true top-K based on full-precision distances.</p>
 */
public final class RescoreStrategy {

    private final int oversamplingFactor;

    /**
     * Creates a rescore strategy with the given oversampling factor.
     *
     * @param oversamplingFactor multiplier for the requested K to determine candidate count;
     *                           must be at least 1
     * @throws SpectorValidationException if oversamplingFactor is less than 1
     */
    public RescoreStrategy(int oversamplingFactor) {
        if (oversamplingFactor < 1) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "oversamplingFactor", 1, Integer.MAX_VALUE, oversamplingFactor);
        }
        this.oversamplingFactor = oversamplingFactor;
    }

    /**
     * Retrieves oversampled candidates from the quantized index, rescores them with
     * exact distances, and returns the top-K sorted by exact distance (ascending).
     *
     * @param query           the query vector (float32)
     * @param k               requested result count
     * @param quantizedSearch function that searches the quantized index for N candidates
     *                        (accepts candidate count, returns scored results)
     * @param exactDistance   function that computes exact float32 distance between
     *                        the query and a candidate identified by its internal index
     * @return top-K results sorted by exact distance (lowest distance first)
     */
    public List<ScoredResult> rescore(float[] query, int k,
                                       IntFunction<List<ScoredResult>> quantizedSearch,
                                       BiFunction<float[], Integer, Float> exactDistance) {
        int candidateCount = oversamplingFactor * k;
        List<ScoredResult> candidates = quantizedSearch.apply(candidateCount);

        // Rescore each candidate with exact distance
        List<ScoredResult> rescored = new ArrayList<>(candidates.size());
        for (ScoredResult candidate : candidates) {
            float exactScore = exactDistance.apply(query, candidate.index());
            rescored.add(new ScoredResult(candidate.id(), candidate.index(), exactScore));
        }

        // Sort by exact distance ascending (lowest/best first) and take top-K
        rescored.sort(ScoredResult::compareAscending);

        int resultCount = Math.min(k, rescored.size());
        return rescored.subList(0, resultCount);
    }

    /**
     * Returns the effective candidate count, capped by total available vectors.
     *
     * @param k            requested result count
     * @param totalVectors total number of vectors in the index
     * @return min(oversamplingFactor * k, totalVectors)
     */
    public int candidateCount(int k, int totalVectors) {
        return Math.min(oversamplingFactor * k, totalVectors);
    }

    /**
     * Returns the oversampling factor configured for this strategy.
     */
    public int oversamplingFactor() {
        return oversamplingFactor;
    }
}
