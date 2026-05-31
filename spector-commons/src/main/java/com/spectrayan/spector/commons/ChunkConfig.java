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
package com.spectrayan.spector.commons;

import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.commons.error.SpectorValidationException;

/**
 * Configuration for the {@link TokenAwareChunker}.
 *
 * @param maxTokens     maximum token count per chunk (1 to 8192 inclusive)
 * @param overlapTokens number of overlapping tokens between consecutive chunks (0 to maxTokens - 1)
 */
public record ChunkConfig(int maxTokens, int overlapTokens) {

    /**
     * Validates the configuration parameters.
     *
     * @throws SpectorValidationException if maxTokens is not in [1, 8192] or
     *                                  overlapTokens is not in [0, maxTokens - 1]
     */
    public ChunkConfig {
        if (maxTokens <= 0 || maxTokens > 8192) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "maxTokens", 1, 8192, maxTokens);
        }
        if (overlapTokens < 0 || overlapTokens >= maxTokens) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "overlapTokens", 0, maxTokens - 1, overlapTokens);
        }
    }

    /**
     * Creates a config with no overlap.
     *
     * @param maxTokens maximum tokens per chunk
     */
    public ChunkConfig(int maxTokens) {
        this(maxTokens, 0);
    }
}
