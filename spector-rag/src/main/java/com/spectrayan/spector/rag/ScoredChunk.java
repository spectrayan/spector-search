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

import com.spectrayan.spector.commons.TextChunk;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * A text chunk annotated with a relevance score from search.
 *
 * @param chunk the text chunk
 * @param score relevance score (higher is more relevant)
 */
public record ScoredChunk(TextChunk chunk, float score) {

    public ScoredChunk {
        if (chunk == null) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "chunk");
        }
        if (Float.isNaN(score)) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "score", "NaN");
        }
    }
}
