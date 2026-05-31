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

import java.io.IOException;
import java.nio.file.Path;

import com.spectrayan.spector.core.similarity.SimilarityFunction;

/**
 * Interface for HNSW index binary persistence.
 *
 * <p>Defines serialize/deserialize operations using a page-aligned binary format
 * with 4 KB aligned regions. The format uses Panama MemorySegments for
 * memory-mapped reads, enabling constant-time loads (single mmap syscall).</p>
 *
 * @see HnswPersistenceImpl
 */
public interface HnswPersistence {

    /**
     * Persists an in-memory HNSW index to a binary file.
     *
     * <p>Writes a self-describing binary format with a 64-byte header,
     * page-aligned vector region, graph region, and ID table.</p>
     *
     * @param file  path to the output file (created or overwritten)
     * @param index the in-memory HNSW index to persist
     * @throws IOException if writing fails
     */
    void persist(Path file, HnswIndex index) throws IOException;

    /**
     * Loads an HNSW index from a persisted binary file using memory-mapped reads.
     *
     * <p>Validates the header magic and version, detects truncation via
     * totalFileSize check, and restores the full graph ready for search.</p>
     *
     * @param file  path to the persisted index file
     * @param simFn the similarity function to use for the loaded index
     * @return the restored HNSW index
     * @throws IOException if reading fails, the file is corrupted, or the format is invalid
     */
    HnswIndex load(Path file, SimilarityFunction simFn) throws IOException;

    /**
     * Appends a new vector, graph block, and ID table entry to a persisted file
     * without rewriting existing regions.
     *
     * @param file       path to the existing persisted index file
     * @param vector     the vector to append
     * @param externalId the external ID for the vector
     * @throws IOException if the file cannot be read/written or is corrupted
     */
    void append(Path file, float[] vector, String externalId) throws IOException;
}
