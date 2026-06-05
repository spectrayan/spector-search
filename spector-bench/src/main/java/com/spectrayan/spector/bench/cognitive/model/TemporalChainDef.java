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

/**
 * A temporal chain linking memories within a single session in chronological order.
 *
 * <p>Maps directly to one line in {@code temporal_chains.jsonl}. Each chain captures
 * the sequential ordering of memories recorded during a particular session, enabling
 * temporal traversal in the benchmark's doubly-linked list structure.</p>
 *
 * <h3>Field Constraints</h3>
 * <ul>
 *   <li>{@code sessionId} — must match at least one corpus record's {@code session_id}
 *       in {@code corpus.jsonl}</li>
 *   <li>{@code orderedMemoryIds} — array of 2–200 corpus IDs in ascending
 *       {@code timestamp_ms} order within that session; each must reference an existing
 *       ID in {@code corpus.jsonl}</li>
 * </ul>
 *
 * @param sessionId        the session identifier grouping these memories
 * @param orderedMemoryIds corpus memory IDs in ascending timestamp order (2–200 entries)
 */
public record TemporalChainDef(
        String sessionId,
        List<String> orderedMemoryIds
) {}
