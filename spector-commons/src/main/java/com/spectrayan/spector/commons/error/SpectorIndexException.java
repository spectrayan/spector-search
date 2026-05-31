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
package com.spectrayan.spector.commons.error;

/**
 * Exception for index construction, search, persistence, and integrity errors ({@code SPE-200-xxx}).
 *
 * <p>Covers HNSW graph building, IVF training, BM25 tokenization, index serialization,
 * and structural integrity violations.</p>
 *
 * @see ErrorCode#HNSW_BUILD_FAILED
 * @see ErrorCode#INDEX_FULL
 */
public class SpectorIndexException extends SpectorException {

    public SpectorIndexException(ErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }

    public SpectorIndexException(ErrorCode errorCode, String preformattedMessage, boolean isPreformatted) {
        super(errorCode, preformattedMessage, isPreformatted);
    }

    public SpectorIndexException(ErrorCode errorCode, Throwable cause, Object... args) {
        super(errorCode, cause, args);
    }
}
