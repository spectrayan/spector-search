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
package com.spectrayan.spector.index;

import com.spectrayan.spector.core.similarity.SimilarityFunction;

/**
 * A scored search result from a vector or keyword index.
 *
 * @param id    the document/vector identifier
 * @param index the internal integer index in the store
 * @param score the similarity or distance score
 */
public record ScoredResult(String id, int index, float score) implements Comparable<ScoredResult> {

    /**
     * Compares by score in descending order (highest score first).
     * For distance metrics where lower is better, callers should negate or
     * use {@link #compareAscending}.
     */
    @Override
    public int compareTo(ScoredResult other) {
        return Float.compare(other.score, this.score); // descending
    }

    /**
     * Compares by score ascending (lowest first) — used for distance metrics.
     */
    public static int compareAscending(ScoredResult a, ScoredResult b) {
        return Float.compare(a.score, b.score);
    }
}
