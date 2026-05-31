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
 * Exception for internal bugs and invariant violations ({@code SPE-900-xxx}).
 *
 * <p>Thrown when the system reaches a state that should be impossible — violated
 * assertions, unreachable code paths, concurrent execution failures, or any
 * condition that indicates a bug in Spector itself (not in user input).</p>
 *
 * <p>Replaces raw {@link IllegalStateException} and {@link UnsupportedOperationException}
 * throws with structured, identifiable error codes.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   default -> throw new SpectorInternalException(
 *       ErrorCode.UNREACHABLE_CODE, "switch on QuantType: " + type);
 *   // → "[SPE-900-003] Reached unreachable code path: switch on QuantType: SVASQ_16"
 * }</pre>
 *
 * <p>If a customer reports an {@code SPE-900-xxx} error, it always indicates a bug
 * in Spector that needs to be fixed — never a user configuration issue.</p>
 *
 * @see ErrorCode
 */
public class SpectorInternalException extends SpectorException {

    /**
     * Creates an internal exception with a formatted message.
     *
     * @param errorCode the internal error code (must be in the SPE-900-xxx range)
     * @param args      values to substitute into the message template
     */
    public SpectorInternalException(ErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }

    public SpectorInternalException(ErrorCode errorCode, String preformattedMessage, boolean isPreformatted) {
        super(errorCode, preformattedMessage, isPreformatted);
    }

    /**
     * Creates an internal exception with a cause and formatted message.
     *
     * @param errorCode the internal error code
     * @param cause     the underlying exception
     * @param args      values to substitute into the message template
     */
    public SpectorInternalException(ErrorCode errorCode, Throwable cause, Object... args) {
        super(errorCode, cause, args);
    }
}
