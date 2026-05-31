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
package com.spectrayan.spector.query;

import java.util.Map;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Represents a search query with mode selection and parameters.
 *
 * @param text       the raw query text (used for keyword search and/or embedding)
 * @param vector     optional pre-computed query vector (for vector search)
 * @param mode       the search mode
 * @param topK       number of results to return
 * @param metadata   optional query-level metadata (filters, trace IDs, etc.)
 */
public record SearchQuery(
        String text,
        float[] vector,
        SearchMode mode,
        int topK,
        Map<String, Object> metadata
) {
    /** Search execution modes. */
    public enum SearchMode {
        /** Keyword-only (BM25) search. */
        KEYWORD,
        /** Vector-only (ANN) search. */
        VECTOR,
        /** Hybrid: keyword + vector fused via RRF. */
        HYBRID
    }

    public SearchQuery {
        if (topK <= 0) throw new SpectorValidationException(ErrorCode.TOP_K_INVALID, 1, topK);
        if (mode == null) mode = SearchMode.HYBRID;
        if (metadata == null) metadata = Map.of();
    }

    /** Creates a keyword-only query. */
    public static SearchQuery keyword(String text, int topK) {
        return new SearchQuery(text, null, SearchMode.KEYWORD, topK, Map.of());
    }

    /** Creates a vector-only query. */
    public static SearchQuery vector(float[] vector, int topK) {
        return new SearchQuery(null, vector, SearchMode.VECTOR, topK, Map.of());
    }

    /** Creates a hybrid query with text and pre-computed vector. */
    public static SearchQuery hybrid(String text, float[] vector, int topK) {
        return new SearchQuery(text, vector, SearchMode.HYBRID, topK, Map.of());
    }
}
