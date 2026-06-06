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

import com.spectrayan.spector.memory.MemoryType;

/**
 * A single corpus entry in the cognitive benchmark dataset.
 *
 * <p>Maps directly to one line in {@code corpus.jsonl}. Contains the memory text,
 * cognitive annotations (valence, importance, arousal, synaptic tags), temporal
 * metadata, entity references, and recall statistics needed by the benchmark harness
 * to exercise all Spector Memory subsystems.</p>
 *
 * <h3>Field Constraints</h3>
 * <ul>
 *   <li>{@code id} — unique across corpus</li>
 *   <li>{@code text} — 1–4096 characters</li>
 *   <li>{@code title} — 1–256 characters</li>
 *   <li>{@code synapticTags} — 1–10 tags per record</li>
 *   <li>{@code valence} — signed byte, -128 to +127</li>
 *   <li>{@code importance} — float, 0.05 to 10.0</li>
 *   <li>{@code arousal} — unsigned 0–255 (stored as int)</li>
 *   <li>{@code sessionId} — matches a session in temporal_chains.jsonl</li>
 *   <li>{@code timestampMs} — epoch milliseconds</li>
 *   <li>{@code entityMentions} — typed entity references</li>
 *   <li>{@code memoryType} — one of EPISODIC, SEMANTIC, PROCEDURAL, WORKING</li>
 *   <li>{@code agentRecallCount} — integer ≥ 0, default 0</li>
 * </ul>
 *
 * @param id              unique identifier for this corpus memory
 * @param text            the memory text content (1–4096 chars)
 * @param title           short descriptive title (1–256 chars)
 * @param synapticTags    contextual tags for Bloom filter encoding (1–10 tags)
 * @param valence         emotional valence, signed byte (-128 to +127)
 * @param importance      ICNU-fused importance score (0.05 to 10.0)
 * @param arousal         physiological arousal level, unsigned (0–255)
 * @param sessionId       session identifier linking to temporal chains
 * @param timestampMs     creation time as epoch milliseconds
 * @param entityMentions  entities mentioned in this memory
 * @param memoryType      cognitive memory type (EPISODIC, SEMANTIC, PROCEDURAL, WORKING)
 * @param agentRecallCount     number of times this memory has been recalled (≥ 0)
 * @param interest        ICNU Interest hint — how engaging this is to the user (0.0–1.0)
 * @param challenge       ICNU Challenge hint — how complex the topic is (0.0–1.0)
 * @param urgency         ICNU Urgency hint — how time-critical this information is (0.0–1.0)
 */
public record BenchmarkCorpusRecord(
        String id,
        String text,
        String title,
        List<String> synapticTags,
        byte valence,
        float importance,
        int arousal,
        String sessionId,
        long timestampMs,
        List<EntityMention> entityMentions,
        MemoryType memoryType,
        int agentRecallCount,
        float interest,
        float challenge,
        float urgency
) {
    /**
     * Backward-compatible constructor for existing code that doesn't provide ICNU hints.
     * Defaults to 0.5 for all ICNU values.
     */
    public BenchmarkCorpusRecord(String id, String text, String title,
                                  List<String> synapticTags, byte valence, float importance,
                                  int arousal, String sessionId, long timestampMs,
                                  List<EntityMention> entityMentions, MemoryType memoryType,
                                  int agentRecallCount) {
        this(id, text, title, synapticTags, valence, importance, arousal,
                sessionId, timestampMs, entityMentions, memoryType, agentRecallCount,
                0.5f, 0.5f, 0.5f);
    }
}
