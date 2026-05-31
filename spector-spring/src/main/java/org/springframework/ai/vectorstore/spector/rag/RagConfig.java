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
 * Configuration for RAG retrieval operations in {@link SpectorRagService}.
 *
 * @param topK               the number of top results to retrieve (1–100, default 5)
 * @param similarityThreshold the minimum relevance score for results (0.0–1.0, default 0.7)
 * @param tokenLimit         the maximum number of tokens in assembled context (1–8192, default 4096)
 */
public record RagConfig(int topK, float similarityThreshold, int tokenLimit) {

    /** Default topK value. */
    public static final int DEFAULT_TOP_K = 5;

    /** Default similarity threshold. */
    public static final float DEFAULT_SIMILARITY_THRESHOLD = 0.7f;

    /** Default token limit. */
    public static final int DEFAULT_TOKEN_LIMIT = 4096;

    public RagConfig {
        if (topK < 1 || topK > 100) {
            throw new SpectorValidationException(ErrorCode.TOP_K_INVALID, 1, topK);
        }
        if (similarityThreshold < 0.0f || similarityThreshold > 1.0f) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "similarityThreshold", 0.0, 1.0, similarityThreshold);
        }
        if (tokenLimit < 1 || tokenLimit > 8192) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "tokenLimit", 1, 8192, tokenLimit);
        }
    }

    /**
     * Creates a RagConfig with all default values.
     */
    public static RagConfig defaults() {
        return new RagConfig(DEFAULT_TOP_K, DEFAULT_SIMILARITY_THRESHOLD, DEFAULT_TOKEN_LIMIT);
    }
}
