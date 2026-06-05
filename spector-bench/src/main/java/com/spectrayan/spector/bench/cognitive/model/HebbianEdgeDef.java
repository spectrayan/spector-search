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

/**
 * A Hebbian co-activation edge between two memories in the corpus.
 *
 * <p>Maps directly to one line in {@code hebbian_edges.jsonl}. Each edge represents
 * a bidirectional association between two memories that have been co-activated
 * (co-ingested) together, weighted by how frequently the co-activation occurred.</p>
 *
 * <h3>Field Constraints</h3>
 * <ul>
 *   <li>{@code memoryIdA} — must reference an existing ID in {@code corpus.jsonl}</li>
 *   <li>{@code memoryIdB} — must reference an existing ID in {@code corpus.jsonl},
 *       different from {@code memoryIdA}</li>
 *   <li>{@code coActivationCount} — integer in range [1, 10000] representing
 *       co-ingestion frequency; higher values indicate stronger associative bonds</li>
 * </ul>
 *
 * @param memoryIdA         first memory endpoint of the edge (corpus ID)
 * @param memoryIdB         second memory endpoint of the edge (corpus ID, distinct from A)
 * @param coActivationCount co-activation frequency between the two memories (1–10000)
 */
public record HebbianEdgeDef(
        String memoryIdA,
        String memoryIdB,
        int coActivationCount
) {}
