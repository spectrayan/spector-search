/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Business Source License 1.1 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://github.com/spectrayan/spector/blob/main/spector-memory/LICENSE
 *
 * Change Date: May 27, 2030
 * Change License: Apache License, Version 2.0
 */
package com.spectrayan.spector.memory;

/**
 * Controls how recall results are scored after retrieval.
 *
 * <p>This is orthogonal to {@link TextSearchMode}, which controls <em>which</em>
 * retrieval signals to use (vector, keyword, both). {@code ScoringMode} controls
 * <em>how</em> the retrieved candidates are ranked.</p>
 *
 * <h3>Modes</h3>
 * <ul>
 *   <li><b>{@link #COGNITIVE}</b>: Full biologically-inspired scoring pipeline —
 *       importance weighting, temporal decay, tag overlap boosting, valence alignment.
 *       Use for interactive memory systems where recall quality depends on
 *       relevance + recency + emotional context.</li>
 *   <li><b>{@link #SIMILARITY}</b>: Pure vector similarity (cosine/dot product) —
 *       the HNSW score IS the final score. No importance, no decay, no tag boosting.
 *       Use for information retrieval benchmarks and document search where
 *       semantic similarity is the only ranking signal.</li>
 * </ul>
 *
 * @see RecallOptions
 * @see TextSearchMode
 */
public enum ScoringMode {

    /**
     * Full cognitive scoring pipeline:
     * {@code alpha × similarity + beta × importance × decay},
     * with tag overlap boosting and optional valence alignment.
     */
    COGNITIVE,

    /**
     * Pure similarity scoring: HNSW cosine similarity is the final score.
     * Bypasses importance, decay, tag boosting, and valence alignment.
     * Ideal for information retrieval benchmarks.
     */
    SIMILARITY
}
