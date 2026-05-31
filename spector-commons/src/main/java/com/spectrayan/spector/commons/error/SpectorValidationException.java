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
 * Exception for input validation failures ({@code SPE-100-xxx}).
 *
 * <p>Thrown when user-supplied arguments violate API contracts: null values,
 * out-of-range parameters, dimension mismatches, empty collections, etc.</p>
 *
 * <p>Replaces raw {@link IllegalArgumentException} throws at public API boundaries
 * with structured, identifiable error codes.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   if (dimensions < 1)
 *       throw new SpectorValidationException(ErrorCode.DIMENSIONS_INVALID, dimensions);
 *   // → "[SPE-100-001] Vector dimensions must be positive, got 0"
 *
 *   if (vector == null)
 *       throw new SpectorValidationException(ErrorCode.VECTOR_NULL);
 *   // → "[SPE-100-003] Vector must not be null"
 * }</pre>
 *
 * @see ErrorCode
 */
public class SpectorValidationException extends SpectorException {

    /**
     * Creates a validation exception with a formatted message.
     *
     * @param errorCode the validation error code (must be in the SPE-100-xxx range)
     * @param args      values to substitute into the message template
     */
    public SpectorValidationException(ErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }

    public SpectorValidationException(ErrorCode errorCode, String preformattedMessage, boolean isPreformatted) {
        super(errorCode, preformattedMessage, isPreformatted);
    }

    /**
     * Creates a validation exception with a cause and formatted message.
     *
     * @param errorCode the validation error code
     * @param cause     the underlying exception
     * @param args      values to substitute into the message template
     */
    public SpectorValidationException(ErrorCode errorCode, Throwable cause, Object... args) {
        super(errorCode, cause, args);
    }
}
