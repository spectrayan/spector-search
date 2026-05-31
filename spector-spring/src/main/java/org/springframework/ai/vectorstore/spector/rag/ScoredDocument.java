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

import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * A document result with a relevance score from RAG retrieval.
 *
 * @param documentId the source document identifier
 * @param content    the document text content
 * @param score      the relevance score (0.0–1.0 inclusive)
 * @param chunkOffset the offset of the chunk within the source document
 */
public record ScoredDocument(String documentId, String content, float score, int chunkOffset) {

    public ScoredDocument {
        if (documentId == null || documentId.isBlank()) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "documentId");
        }
        if (score < 0.0f || score > 1.0f) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "score", 0, 1, score);
        }
        if (chunkOffset < 0) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NEGATIVE, "chunkOffset", 0);
        }
    }
}
