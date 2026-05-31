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
/**
 * Retrieval-Augmented Generation pipeline for Spector.
 *
 * <p>Provides the full RAG flow: query embedding → retrieval → context assembly → attribution.
 * Uses virtual threads for I/O-bound operations (embedding calls) while keeping retrieval
 * on the synchronous high-performance path.</p>
 *
 * <h3>Key Classes</h3>
 * <ul>
 *   <li>{@link com.spectrayan.spector.rag.ContextBuilder} — assembles scored chunks into a token-limited context</li>
 *   <li>{@link com.spectrayan.spector.rag.RagPipeline} — full RAG orchestrator: embed → search → assemble</li>
 *   <li>{@link com.spectrayan.spector.rag.RagRequest} — input parameters for a RAG query</li>
 *   <li>{@link com.spectrayan.spector.rag.RagResponse} — output with context, attributions, and metadata</li>
 * </ul>
 */
package com.spectrayan.spector.rag;
