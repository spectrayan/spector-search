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
 * A scored retrieval result pairing a memory ID with its computed similarity score.
 *
 * <p>Used by both {@code BaselineRetriever} (vector-only scoring) and
 * {@code CognitiveRetriever} (full pipeline scoring) to represent ranked
 * results in a uniform format for metric computation.</p>
 *
 * @param memoryId the unique identifier of the retrieved memory record
 * @param score    the similarity score (higher is more similar)
 */
public record ScoredResult(String memoryId, float score) {
}
