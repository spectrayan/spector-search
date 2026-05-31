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
package com.spectrayan.spector.commons.concurrent;

/**
 * Exception thrown when a concurrent fork-join operation fails.
 *
 * <p>Wraps the root cause of the first failed subtask. In structured
 * concurrency mode, this wraps the {@code FailedException} cause.
 * In classic mode, it wraps the {@code ExecutionException} cause.</p>
 */
public class ConcurrentExecutionException extends Exception {

    /**
     * Creates a new concurrent execution exception.
     *
     * @param message descriptive message
     * @param cause   the underlying exception from the failed task
     */
    public ConcurrentExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
