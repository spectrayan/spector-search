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

import java.util.List;

import com.spectrayan.spector.bench.cognitive.model.BenchmarkQuery;
import com.spectrayan.spector.bench.cognitive.model.ScoredResult;
import com.spectrayan.spector.memory.CognitiveResult;
import com.spectrayan.spector.memory.RecallMode;
import com.spectrayan.spector.memory.RecallOptions;
import com.spectrayan.spector.memory.SpectorMemory;

/**
 * Cognitive retriever wrapping the full Spector Memory recall pipeline with
 * {@link RecallMode#OBSERVE} to prevent side effects during benchmarking.
 *
 * <p>This serves as the experimental condition in the benchmark: it exercises
 * the complete 6-phase cognitive scoring pipeline plus graph augmentation
 * (Hebbian, Temporal, Entity) without mutating memory state. Paired with
 * {@link BaselineRetriever} for comparative evaluation.</p>
 *
 * <h3>Pipeline Phases Exercised</h3>
 * <ol>
 *   <li>Tombstone Check — skips logically deleted records</li>
 *   <li>Synaptic Tag Gating — Bloom filter containment check</li>
 *   <li>Valence Filter — emotional range filtering</li>
 *   <li>Importance/Decay Pre-screen — eliminates low-value candidates</li>
 *   <li>SIMD L2 Distance — calibrated INT8 asymmetric quantization</li>
 *   <li>Fused Cognitive Score — alpha×similarity + beta×importance×decay</li>
 * </ol>
 *
 * <p>Plus graph augmentation: Hebbian spreading activation, temporal chain
 * traversal, and entity graph multi-hop discovery.</p>
 *
 * <h3>Edge Case Handling</h3>
 * <ul>
 *   <li>Empty synapticFilterTags → no tag filter applied (all tags pass)</li>
 *   <li>Null minValence → profile default applies (no override)</li>
 *   <li>Null maxValence → profile default applies (no override)</li>
 *   <li>DEFAULT_MODE_NETWORK profile → restricts to SEMANTIC and PROCEDURAL
 *       memory types (handled implicitly by {@link com.spectrayan.spector.memory.CognitiveProfile#applyTo})</li>
 * </ul>
 */
public final class CognitiveRetriever {

    private final SpectorMemory memory;

    /**
     * Creates a new CognitiveRetriever backed by the given SpectorMemory instance.
     *
     * @param memory the memory instance to execute recall queries against;
     *               must not be null
     * @throws NullPointerException if memory is null
     */
    public CognitiveRetriever(SpectorMemory memory) {
        if (memory == null) {
            throw new NullPointerException("SpectorMemory instance must not be null");
        }
        this.memory = memory;
    }

    /**
     * Builds {@link RecallOptions} from a {@link BenchmarkQuery}, always using
     * {@link RecallMode#OBSERVE} to prevent state mutations during benchmarking.
     *
     * <p>The builder applies the query's cognitive profile first (which sets
     * alpha/beta weights, valence defaults, and profile-specific overrides such
     * as memory type restrictions for DEFAULT_MODE_NETWORK), then conditionally
     * applies query-specific overrides for synaptic filter tags and valence bounds.</p>
     *
     * @param query the benchmark query containing profile and filter parameters
     * @return configured RecallOptions ready for execution
     */
    RecallOptions buildOptions(BenchmarkQuery query) {
        RecallOptions.Builder builder = RecallOptions.builder()
                .topK(10)
                .recallMode(RecallMode.OBSERVE)
                .profile(query.cognitiveProfile());

        // Apply synaptic tag filter only when tags are present
        if (!query.synapticFilterTags().isEmpty()) {
            builder.synapticFilter(query.synapticFilterTags().toArray(String[]::new));
        }

        // Override profile valence defaults only when query specifies explicit bounds
        if (query.minValence() != null) {
            builder.minValence(query.minValence());
        }
        if (query.maxValence() != null) {
            builder.maxValence(query.maxValence());
        }

        return builder.build();
    }

    /**
     * Executes a recall query through the full cognitive pipeline and maps
     * results to {@link ScoredResult} for metric computation.
     *
     * <p>Builds options from the query, executes recall via
     * {@link SpectorMemory#recall(String, RecallOptions)}, then maps each
     * {@link CognitiveResult} to a simplified {@link ScoredResult} containing
     * only the memory ID and final fused score.</p>
     *
     * @param queryText the text content of the query for embedding and retrieval
     * @param query     the benchmark query with profile and filter parameters
     * @return scored results ordered by descending cognitive score
     */
    public List<ScoredResult> retrieve(String queryText, BenchmarkQuery query) {
        RecallOptions options = buildOptions(query);
        List<CognitiveResult> results = memory.recall(queryText, options);
        return results.stream()
                .map(r -> new ScoredResult(r.id(), r.score()))
                .toList();
    }

    /**
     * Executes a recall query and returns the full {@link CognitiveResult} list
     * including score breakdowns, for subsystem contribution analysis.
     *
     * @param queryText the text content of the query for embedding and retrieval
     * @param query     the benchmark query with profile and filter parameters
     * @return full cognitive results with breakdown metadata
     */
    public List<CognitiveResult> retrieveWithBreakdown(String queryText, BenchmarkQuery query) {
        RecallOptions options = buildOptions(query);
        return memory.recall(queryText, options);
    }
}
