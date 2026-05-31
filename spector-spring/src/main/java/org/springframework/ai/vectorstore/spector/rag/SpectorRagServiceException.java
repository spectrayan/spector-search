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

/**
 * Exception thrown by {@link SpectorRagService} when a dependency fails
 * (vector store unavailable, context builder error, etc.).
 *
 * <p>This exception propagates dependency errors without crashing the application,
 * allowing callers to handle retrieval failures gracefully.</p>
 */
public class SpectorRagServiceException extends RuntimeException {

    /**
     * Creates a new SpectorRagServiceException with the specified message.
     *
     * @param message the error message
     */
    public SpectorRagServiceException(String message) {
        super(message);
    }

    /**
     * Creates a new SpectorRagServiceException with the specified message and cause.
     *
     * @param message the error message
     * @param cause   the underlying cause
     */
    public SpectorRagServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
