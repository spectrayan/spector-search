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
 * Exception for cognitive memory tier errors ({@code SPE-310-xxx}).
 *
 * <p>Covers memory tier capacity, recall pipeline failures, consolidation errors,
 * memory ID lookups, and WAL corruption in the memory subsystem.</p>
 *
 * @see ErrorCode#MEMORY_TIER_FULL
 * @see ErrorCode#MEMORY_RECALL_FAILED
 */
public class SpectorMemoryException extends SpectorException {

    public SpectorMemoryException(ErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }

    public SpectorMemoryException(ErrorCode errorCode, String preformattedMessage, boolean isPreformatted) {
        super(errorCode, preformattedMessage, isPreformatted);
    }

    public SpectorMemoryException(ErrorCode errorCode, Throwable cause, Object... args) {
        super(errorCode, cause, args);
    }
}
