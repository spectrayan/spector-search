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
 * Spector Query — Query engine with hybrid search orchestration and RRF fusion.
 *
 * <p>Orchestrates fan-out queries across keyword and vector indexes using
 * virtual threads, then merges results via Reciprocal Rank Fusion (RRF)
 * for best-of-both-worlds retrieval.</p>
 */
package com.spectrayan.spector.query;
