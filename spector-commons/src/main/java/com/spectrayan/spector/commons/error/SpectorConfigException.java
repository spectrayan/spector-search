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
 * Exception for configuration loading, parsing, and validation errors ({@code SPE-110-xxx}).
 *
 * <p>Thrown when a configuration file cannot be found, parsed, or contains invalid values.
 * Replaces the previous unstructured {@code SpectorConfigException} from spector-config.</p>
 *
 * @see ErrorCode#CONFIG_FILE_NOT_FOUND
 * @see ErrorCode#CONFIG_PARSE_FAILED
 */
public class SpectorConfigException extends SpectorException {

    public SpectorConfigException(ErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }

    public SpectorConfigException(ErrorCode errorCode, String preformattedMessage, boolean isPreformatted) {
        super(errorCode, preformattedMessage, isPreformatted);
    }

    public SpectorConfigException(ErrorCode errorCode, Throwable cause, Object... args) {
        super(errorCode, cause, args);
    }
}
