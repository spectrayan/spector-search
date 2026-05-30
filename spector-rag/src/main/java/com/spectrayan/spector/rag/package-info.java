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
