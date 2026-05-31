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
 * Document ingestion pipeline for Spector.
 *
 * <p>Orchestrates the flow: document → chunk → embed → store → index.
 * Uses virtual threads and structured concurrency for parallel embedding
 * without introducing reactive complexity.</p>
 *
 * <h3>Key Classes</h3>
 * <ul>
 *   <li>{@link com.spectrayan.spector.ingestion.IngestionPipeline} — main pipeline orchestrator</li>
 *   <li>{@link com.spectrayan.spector.ingestion.IngestionTarget} — abstraction for index + store operations</li>
 *   <li>{@link com.spectrayan.spector.ingestion.IngestionResult} — outcome of an ingestion operation</li>
 * </ul>
 */
package com.spectrayan.spector.ingestion;
