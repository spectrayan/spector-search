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
package org.springframework.ai.vectorstore.spector.rag;

import com.spectrayan.spector.rag.ChunkAttribution;

import java.util.List;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Result of a RAG retrieval operation from {@link SpectorRagService}.
 *
 * @param documents    the scored documents matching the query, ordered by descending relevance
 * @param contextText  the assembled context string from matched documents
 * @param attributions source attribution entries for each included chunk
 */
public record RetrievalResult(
        List<ScoredDocument> documents,
        String contextText,
        List<ChunkAttribution> attributions
) {

    public RetrievalResult {
        if (documents == null) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "documents");
        }
        if (contextText == null) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "contextText");
        }
        if (attributions == null) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "attributions");
        }
        documents = List.copyOf(documents);
        attributions = List.copyOf(attributions);
    }

    /**
     * Creates an empty retrieval result indicating no relevant documents were found.
     */
    public static RetrievalResult empty() {
        return new RetrievalResult(List.of(), "", List.of());
    }

    /**
     * Returns true if no documents were found meeting the similarity threshold.
     */
    public boolean isEmpty() {
        return documents.isEmpty();
    }
}
