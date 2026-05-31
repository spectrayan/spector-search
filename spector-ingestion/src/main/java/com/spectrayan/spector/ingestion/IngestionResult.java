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

import java.util.List;

/**
 * Outcome of an ingestion operation.
 *
 * @param documentId   the parent document ID
 * @param chunksStored number of chunks successfully stored
 * @param failures     list of chunk IDs that failed (empty on full success)
 * @param durationMs   total time spent in milliseconds
 */
public record IngestionResult(
        String documentId,
        int chunksStored,
        List<String> failures,
        long durationMs
) {
    /** Creates a successful single-document result. */
    public static IngestionResult single(String documentId, long durationMs) {
        return new IngestionResult(documentId, 1, List.of(), durationMs);
    }

    /** Creates a chunked result. */
    public static IngestionResult chunked(String documentId, int chunks, List<String> failures, long durationMs) {
        return new IngestionResult(documentId, chunks, failures, durationMs);
    }

    /** Returns true if all chunks were stored successfully. */
    public boolean isFullSuccess() {
        return failures.isEmpty();
    }
}
