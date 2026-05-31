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
 * Exception for document ingestion pipeline errors ({@code SPE-600-xxx}).
 *
 * <p>Thrown when document parsing, chunking, or the ingestion pipeline fails.</p>
 *
 * @see ErrorCode#INGESTION_FORMAT_UNSUPPORTED
 * @see ErrorCode#INGESTION_PIPELINE_FAILED
 */
public class SpectorIngestionException extends SpectorException {

    public SpectorIngestionException(ErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }

    public SpectorIngestionException(ErrorCode errorCode, String preformattedMessage, boolean isPreformatted) {
        super(errorCode, preformattedMessage, isPreformatted);
    }

    public SpectorIngestionException(ErrorCode errorCode, Throwable cause, Object... args) {
        super(errorCode, cause, args);
    }
}
