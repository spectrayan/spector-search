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
 * Exception for embedding provider errors ({@code SPE-300-xxx}).
 *
 * <p>Thrown when an embedding provider (e.g. Ollama) is unreachable, returns an error,
 * times out, or returns vectors with unexpected dimensions.</p>
 *
 * @see ErrorCode#EMBEDDING_UNAVAILABLE
 * @see ErrorCode#EMBEDDING_TIMEOUT
 */
public class SpectorEmbeddingException extends SpectorException {

    public SpectorEmbeddingException(ErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }

    public SpectorEmbeddingException(ErrorCode errorCode, String preformattedMessage, boolean isPreformatted) {
        super(errorCode, preformattedMessage, isPreformatted);
    }

    public SpectorEmbeddingException(ErrorCode errorCode, Throwable cause, Object... args) {
        super(errorCode, cause, args);
    }
}
