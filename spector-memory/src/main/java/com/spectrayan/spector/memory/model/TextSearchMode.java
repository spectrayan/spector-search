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
package com.spectrayan.spector.memory.model;


/**
 * Controls which retrieval paths are active during recall.
 *
 * <h3>Retrieval Stack</h3>
 * <pre>
 *   Layer 4: ColBERT v2 Reranker   (token-level late interaction)
 *   Layer 3: SPLADE / Li-LSR       (learned sparse retrieval)
 *   Layer 2: BM25                  (keyword search, SIMD-accelerated)
 *   Layer 1: Dense Vector           (HNSW semantic similarity)
 *   ─────── RRF Fusion ─────────── (merges all layer signals)
 * </pre>
 *
 * <h3>Modes</h3>
 * <table>
 *   <tr><th>Mode</th><th>Active Layers</th><th>Best For</th></tr>
 *   <tr><td>{@link #HYBRID}</td><td>BM25 + Vector</td><td>General purpose (default)</td></tr>
 *   <tr><td>{@link #KEYWORD_ONLY}</td><td>BM25 only</td><td>Exact terms: names, error codes, IDs</td></tr>
 *   <tr><td>{@link #VECTOR_ONLY}</td><td>Vector only</td><td>Vague/conceptual queries</td></tr>
 *   <tr><td>{@link #SPLADE}</td><td>SPLADE only</td><td>Synonym-aware keyword search</td></tr>
 *   <tr><td>{@link #SPLADE_HYBRID}</td><td>SPLADE + Vector</td><td>Best quality without BM25</td></tr>
 *   <tr><td>{@link #LI_LSR}</td><td>Li-LSR only</td><td>Fast inference-free sparse</td></tr>
 *   <tr><td>{@link #COLBERT_RERANK}</td><td>All first-stage + ColBERT rerank</td><td>Maximum quality</td></tr>
 *   <tr><td>{@link #FULL_STACK}</td><td>All layers active</td><td>Best possible recall + precision</td></tr>
 * </table>
 *
 * <h3>Graceful Degradation</h3>
 * <p>If a mode requires a provider that is not configured (e.g., SPLADE mode without
 * a {@link com.spectrayan.spector.embed.SparseEncodingProvider}), the pipeline will
 * log a warning and silently degrade to the closest available mode.</p>
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
    VECTOR_ONLY,

    /**
     * SPLADE learned sparse retrieval only — no BM25, no vector.
     * <p>Neural term expansion captures synonyms and related concepts
     * that BM25 misses. Requires a configured
     * {@link com.spectrayan.spector.embed.SparseEncodingProvider}.</p>
     */
    SPLADE,

    /**
     * SPLADE + dense vector search, fused via RRF.
     * <p>Recommended upgrade from {@link #HYBRID} when a SPLADE provider
     * is available. Combines semantic recall (vector) with learned term
     * expansion (SPLADE) without the limitations of exact-match BM25.</p>
     */
    SPLADE_HYBRID,

    /**
     * Li-LSR inference-free sparse retrieval.
     * <p>Uses precomputed lookup tables for query encoding — no neural model
     * needed at query time. Fastest sparse retrieval option.</p>
     */
    LI_LSR,

    /**
     * Any first-stage retrieval + ColBERT v2 reranking.
     * <p>Runs the default first-stage retrieval (HYBRID) and then reranks
     * the top-N candidates using ColBERT's token-level MaxSim scoring.
     * Requires a configured
     * {@link com.spectrayan.spector.embed.TokenEmbeddingProvider}.</p>
     */
    COLBERT_RERANK,

    /**
     * Full stack: BM25 + SPLADE + dense vector + ColBERT reranker.
     * <p>All retrieval layers active with three-way RRF fusion, followed
     * by ColBERT reranking. Maximum quality at the cost of latency.
     * Requires both SparseEncodingProvider and TokenEmbeddingProvider.</p>
     */
    FULL_STACK;

    // ── Convenience query methods ──

    /** Returns true if this mode uses BM25 keyword search. */
    public boolean usesBM25() {
        return this == HYBRID || this == KEYWORD_ONLY
                || this == COLBERT_RERANK || this == FULL_STACK;
    }

    /** Returns true if this mode uses dense vector search. */
    public boolean usesVector() {
        return this != KEYWORD_ONLY && this != SPLADE && this != LI_LSR;
    }

    /** Returns true if this mode uses SPLADE or Li-LSR sparse retrieval. */
    public boolean usesSPLADE() {
        return this == SPLADE || this == SPLADE_HYBRID
                || this == LI_LSR || this == FULL_STACK;
    }

    /** Returns true if this mode uses ColBERT v2 reranking. */
    public boolean usesColBERT() {
        return this == COLBERT_RERANK || this == FULL_STACK;
    }
}
