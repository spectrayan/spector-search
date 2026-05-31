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
package com.spectrayan.spector.ingestion;

/**
 * Abstraction for the storage target that ingestion writes to.
 *
 * <p>This decouples the ingestion pipeline from concrete implementations,
 * allowing both search-engine and cognitive-memory targets to receive
 * chunks from the same unified pipeline.</p>
 *
 * <h3>Implementations</h3>
 * <ul>
 *   <li><b>EngineIngestionTarget</b> (spector-engine): VectorStore → HNSW → BM25</li>
 *   <li><b>CognitiveIngestionTarget</b> (spector-memory): quantize → surprise → tier route → WAL</li>
 * </ul>
 *
 * <p>The pipeline calls {@link #ingest(String, String, float[])} for each
 * chunk after embedding. The target handles all downstream storage.</p>
 */
public interface IngestionTarget {

    /**
     * Ingests a single chunk/document with its text and embedding vector.
     *
     * <p>Called by the pipeline once per chunk after chunking and embedding.
     * The implementation handles all downstream storage, indexing, and
     * persistence.</p>
     *
     * @param id     document or chunk ID
     * @param text   the text content of this chunk
     * @param vector the embedding vector for this chunk
     */
    void ingest(String id, String text, float[] vector);

    /**
     * Stores lightweight parent document metadata after all chunks are ingested.
     *
     * <p>Called once per parent document with the total chunk count. This allows
     * targets to maintain a registry of ingested documents without storing
     * the full content.</p>
     *
     * <p>Default is no-op — cognitive targets may not need parent tracking.</p>
     *
     * @param parentId   the parent document ID
     * @param chunkCount number of chunks the document was split into
     */
    default void storeParentMetadata(String parentId, int chunkCount) {}

    /**
     * Called when a batch of ingestion operations completes.
     *
     * <p>Targets can use this for flush operations (WAL sync, index compaction, etc.).</p>
     *
     * <p>Default is no-op.</p>
     */
    default void onBatchComplete() {}
}
