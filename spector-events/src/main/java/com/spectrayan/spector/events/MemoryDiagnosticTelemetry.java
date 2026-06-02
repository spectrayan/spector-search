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
 * Memory diagnostic telemetry — periodic health snapshot of the
 * memory subsystem (JVM heap, off-heap, page faults, tier counts).
 *
 * @param offHeapAllocated off-heap memory allocated
 * @param pinnedBytes      pinned/locked memory bytes
 * @param jvmHeapUsed      JVM heap used bytes
 * @param jvmHeapMax       JVM heap max bytes
 * @param gpuAllocated     GPU memory allocated
 * @param gpuFree          GPU memory free
 * @param softPageFaults   soft page fault count
 * @param hardPageFaults   hard page fault count
 * @param workingCount     working memory tier count
 * @param episodicCount    episodic memory tier count
 * @param semanticCount    semantic memory tier count
 * @param proceduralCount  procedural memory tier count
 * @param hebbianEdges     Hebbian graph edge count
 * @param temporalLinks    temporal chain link count
 * @param entityNodes      entity graph node count
 * @param entityEdges      entity graph edge count
 * @param coActivationPairs co-activation pair count
 * @param stdpEdges        STDP edge count
 */
public record MemoryDiagnosticTelemetry(
        long offHeapAllocated,
        long pinnedBytes,
        long jvmHeapUsed,
        long jvmHeapMax,
        long gpuAllocated,
        long gpuFree,
        long softPageFaults,
        long hardPageFaults,
        int workingCount,
        int episodicCount,
        int semanticCount,
        int proceduralCount,
        int hebbianEdges,
        int temporalLinks,
        int entityNodes,
        int entityEdges,
        int coActivationPairs,
        int stdpEdges
) implements TelemetryEvent {}
