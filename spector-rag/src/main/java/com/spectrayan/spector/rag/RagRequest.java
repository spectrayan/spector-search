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

/**
 * Input parameters for a RAG pipeline query.
 *
 * @param query       the user query text
 * @param topK        maximum number of chunks to retrieve (default: 5)
 * @param tokenLimit  maximum tokens in assembled context (default: 4096)
 * @param searchMode  "vector" or "hybrid" (default: "vector")
 */
public record RagRequest(String query, Integer topK, Integer tokenLimit, String searchMode) {

    /** Default topK if not specified. */
    public static final int DEFAULT_TOP_K = 5;

    /** Default token limit if not specified. */
    public static final int DEFAULT_TOKEN_LIMIT = 4096;

    /** Convenience constructor with just a query. */
    public RagRequest(String query) {
        this(query, null, null, null);
    }

    /** Returns resolved topK with bounds [1, 100]. */
    public int resolvedTopK() {
        if (topK == null) return DEFAULT_TOP_K;
        return Math.max(1, Math.min(100, topK));
    }

    /** Returns resolved token limit with bounds [256, 131072]. */
    public int resolvedTokenLimit() {
        if (tokenLimit == null) return DEFAULT_TOKEN_LIMIT;
        return Math.max(256, Math.min(131_072, tokenLimit));
    }

    /** Returns resolved search mode (defaults to "vector"). */
    public String resolvedSearchMode() {
        if (searchMode == null || searchMode.isBlank()) return "vector";
        String normalized = searchMode.toLowerCase().trim();
        if ("hybrid".equals(normalized)) return "hybrid";
        return "vector";
    }
}
