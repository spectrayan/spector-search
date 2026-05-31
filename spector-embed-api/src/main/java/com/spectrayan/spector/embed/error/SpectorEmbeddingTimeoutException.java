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
package com.spectrayan.spector.embed.error;

import com.spectrayan.spector.commons.error.*;

/**
 * Exception thrown when an embedding request exceeds the configured timeout limits.
 *
 * @see SpectorEmbeddingException
 */
public class SpectorEmbeddingTimeoutException extends SpectorEmbeddingException {

    private final long timeoutMs;

    public SpectorEmbeddingTimeoutException(long timeoutMs) {
        super(ErrorCode.EMBEDDING_TIMEOUT, timeoutMs);
        this.timeoutMs = timeoutMs;
    }

    public SpectorEmbeddingTimeoutException(long timeoutMs, Throwable cause) {
        super(ErrorCode.EMBEDDING_TIMEOUT, cause, timeoutMs);
        this.timeoutMs = timeoutMs;
    }

    /** Returns the timeout duration in milliseconds that was exceeded. */
    public long getTimeoutMs() {
        return timeoutMs;
    }
}
