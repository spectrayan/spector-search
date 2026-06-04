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
 * Controls which retrieval paths are active during recall.
 *
 * <h3>Modes</h3>
 * <ul>
 *   <li>{@link #HYBRID} — parallel vector + BM25 keyword search, fused via RRF-weighted scoring (default)</li>
 *   <li>{@link #KEYWORD_ONLY} — BM25 keyword search only, no vector similarity</li>
 *   <li>{@link #VECTOR_ONLY} — vector similarity search only, no BM25 keyword boost</li>
 * </ul>
 *
 * <h3>When to Use Each Mode</h3>
 * <table>
 *   <tr><th>Query Type</th><th>Best Mode</th><th>Why</th></tr>
 *   <tr><td>"what's John's phone number?"</td><td>{@code KEYWORD_ONLY}</td><td>Exact term match; vector similarity doesn't help</td></tr>
 *   <tr><td>"explain the deployment process"</td><td>{@code HYBRID}</td><td>Both semantic meaning and keyword terms are useful</td></tr>
 *   <tr><td>"something like that error from last week"</td><td>{@code VECTOR_ONLY}</td><td>Vague query; keyword matching would miss it</td></tr>
 * </table>
 *
 * @see RecallOptions#textSearchMode()
 */
public enum TextSearchMode {

    /**
     * Parallel vector + BM25 keyword search with fused scoring.
     * <p>Vector candidates get a BM25 boost when they also match keywords.
     * BM25-only candidates are scored as {@code γ·bm25Score + β·importance·decay}.</p>
     * <p>This is the default mode.</p>
     */
    HYBRID,

    /**
     * BM25 keyword search only — no vector similarity computation.
     * <p>Useful for exact-term queries (names, error codes, IDs).</p>
     */
    KEYWORD_ONLY,

    /**
     * Vector similarity search only — no BM25 keyword boost.
     * <p>Equivalent to the pre-text-search behavior.</p>
     */
    VECTOR_ONLY
}
