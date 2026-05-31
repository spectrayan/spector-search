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
package com.spectrayan.spector.rag;

import java.util.List;

/**
 * Output from a RAG pipeline execution.
 *
 * @param contextText  the assembled context string for LLM prompting
 * @param attributions source attributions for included chunks
 * @param message      optional message (e.g., "No matching documents found")
 * @param queryTimeMs  total pipeline execution time in milliseconds
 */
public record RagResponse(
        String contextText,
        List<Attribution> attributions,
        String message,
        long queryTimeMs
) {

    /**
     * Source attribution for a chunk in the context.
     *
     * @param documentId  source document ID
     * @param chunkOffset chunk offset within the document
     */
    public record Attribution(String documentId, int chunkOffset) {}

    /** Creates an empty response when no results are found. */
    public static RagResponse empty(long queryTimeMs) {
        return new RagResponse("", List.of(), "No matching documents were found", queryTimeMs);
    }
}
