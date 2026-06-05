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
package com.spectrayan.spector.bench.cognitive.model;

import java.util.List;

import com.spectrayan.spector.memory.CognitiveProfile;

/**
 * A single query entry in the cognitive benchmark dataset.
 *
 * <p>Maps directly to one line in {@code queries.jsonl}. Contains the query text,
 * cognitive profile selection, synaptic filter tags, optional valence constraints,
 * the expected contributing subsystem, and an optional temporal hint — providing
 * all parameters the benchmark harness needs to exercise specific Spector Memory
 * subsystems.</p>
 *
 * <h3>Field Constraints</h3>
 * <ul>
 *   <li>{@code id} — unique across queries</li>
 *   <li>{@code text} — 1–1024 characters</li>
 *   <li>{@code cognitiveProfile} — one of the 12 {@link CognitiveProfile} enum values</li>
 *   <li>{@code synapticFilterTags} — 0–10 tags for Bloom filter gating</li>
 *   <li>{@code minValence} — nullable signed byte, -128 to +127</li>
 *   <li>{@code maxValence} — nullable signed byte, -128 to +127</li>
 *   <li>{@code expectedSubsystem} — one of: VECTOR_SIMILARITY, TAG_GATING,
 *       VALENCE_FILTER, IMPORTANCE_DECAY, HEBBIAN_GRAPH, TEMPORAL_CHAIN, ENTITY_GRAPH</li>
 *   <li>{@code temporalHint} — one of: RECENT, OLD, or null</li>
 * </ul>
 *
 * @param id                  unique identifier for this query
 * @param text                the query text content (1–1024 chars)
 * @param cognitiveProfile    cognitive profile to use during retrieval
 * @param synapticFilterTags  tags for synaptic Bloom filter gating (0–10 tags)
 * @param minValence          optional minimum valence filter (nullable, -128 to +127)
 * @param maxValence          optional maximum valence filter (nullable, -128 to +127)
 * @param expectedSubsystem   the subsystem expected to contribute most to correct retrieval
 * @param temporalHint        optional temporal bias hint (RECENT, OLD, or null)
 */
public record BenchmarkQuery(
        String id,
        String text,
        CognitiveProfile cognitiveProfile,
        List<String> synapticFilterTags,
        Byte minValence,
        Byte maxValence,
        String expectedSubsystem,
        String temporalHint
) {}
