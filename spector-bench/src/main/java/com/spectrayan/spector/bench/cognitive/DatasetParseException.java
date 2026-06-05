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
package com.spectrayan.spector.bench.cognitive;

/**
 * Thrown when a dataset file cannot be parsed due to I/O errors or malformed content.
 *
 * <p>Wraps the underlying cause (e.g., {@link java.io.IOException} or Jackson parse
 * exceptions) and includes the file path and line number (when available) to aid
 * debugging.</p>
 */
public class DatasetParseException extends RuntimeException {

    /**
     * Creates a parse exception with a descriptive message and underlying cause.
     *
     * @param message description of what failed (including file path and line if available)
     * @param cause   the underlying exception that caused the parse failure
     */
    public DatasetParseException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates a parse exception with a descriptive message.
     *
     * @param message description of what failed (including file path and line if available)
     */
    public DatasetParseException(String message) {
        super(message);
    }
}
