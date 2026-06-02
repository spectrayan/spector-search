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
package com.spectrayan.spector.events;

/**
 * Query trace telemetry — emitted after each search with per-phase
 * record survival counts for the scoring funnel.
 *
 * @param queryText       the original query text
 * @param cognitiveProfile profile used (e.g., "BALANCED")
 * @param totalRecords     total records before filtering
 * @param afterTombstone   records remaining after tombstone filter
 * @param afterTagGate     records remaining after synaptic tag gate
 * @param afterValence     records remaining after valence filter
 * @param afterDecay       records remaining after decay filter
 * @param afterVector      records remaining after vector distance filter
 * @param finalTopK        final result count
 * @param latencyMicros    end-to-end search latency in microseconds
 */
public record QueryTraceTelemetry(
        String queryText,
        String cognitiveProfile,
        int totalRecords,
        int afterTombstone,
        int afterTagGate,
        int afterValence,
        int afterDecay,
        int afterVector,
        int finalTopK,
        long latencyMicros
) implements TelemetryEvent {}
