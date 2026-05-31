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
package com.spectrayan.spector.index.error;

import com.spectrayan.spector.commons.error.*;

/**
 * Exception thrown when BM25 text tokenization fails.
 *
 * @see SpectorIndexException
 */
public class SpectorBm25TokenizationException extends SpectorIndexException {

    private final String details;

    public SpectorBm25TokenizationException(String details) {
        super(ErrorCode.BM25_TOKENIZATION_FAILED, details);
        this.details = details;
    }

    public SpectorBm25TokenizationException(String details, Throwable cause) {
        super(ErrorCode.BM25_TOKENIZATION_FAILED, cause, details);
        this.details = details;
    }

    /** Returns the details of the tokenization failure. */
    public String getDetails() {
        return details;
    }
}
