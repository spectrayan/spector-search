/**
 * Document ingestion pipeline for Spector Search.
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
