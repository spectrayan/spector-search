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
 * Exception thrown when the embedding provider or model is not reachable.
 *
 * @see SpectorEmbeddingException
 */
public class SpectorEmbeddingUnavailableException extends SpectorEmbeddingException {

    private final String provider;

    public SpectorEmbeddingUnavailableException(String provider) {
        super(ErrorCode.EMBEDDING_UNAVAILABLE, provider);
        this.provider = provider;
    }

    public SpectorEmbeddingUnavailableException(String provider, Throwable cause) {
        super(ErrorCode.EMBEDDING_UNAVAILABLE, cause, provider);
        this.provider = provider;
    }

    public SpectorEmbeddingUnavailableException(ErrorCode errorCode, String provider) {
        super(errorCode, provider);
        this.provider = provider;
    }

    public SpectorEmbeddingUnavailableException(ErrorCode errorCode, Throwable cause, String provider) {
        super(errorCode, cause, provider);
        this.provider = provider;
    }

    /** Returns the name or URL of the embedding provider that is unavailable. */
    public String getProvider() {
        return provider;
    }
}
