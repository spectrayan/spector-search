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
 * Memory snapshot telemetry — emitted before and after reflect()
 * consolidation. Used by the memory diff view to show before/after
 * comparison.
 *
 * @param phase            "pre-reflect" or "post-reflect"
 * @param reflectCycleId   UUID linking pre/post snapshots for the same cycle
 * @param hebbianEdgeCount current Hebbian edge count
 * @param temporalLinkCount current temporal chain link count
 * @param entityNodeCount  current entity graph node count
 * @param entityEdgeCount  current entity graph edge count
 * @param offHeapBytes     off-heap memory used
 * @param tombstoneCount   number of tombstoned memories
 * @param coActivationPairs co-activation pair count
 * @param stdpEdges        STDP-strengthened edge count
 */
public record MemorySnapshotTelemetry(
        String phase,
        String reflectCycleId,
        int hebbianEdgeCount,
        int temporalLinkCount,
        int entityNodeCount,
        int entityEdgeCount,
        long offHeapBytes,
        int tombstoneCount,
        int coActivationPairs,
        int stdpEdges
) implements TelemetryEvent {}
