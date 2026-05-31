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
 * Exception thrown when a document cannot be read or processed.
 *
 * <p>This exception carries information about the file that failed and the
 * nature of the failure, without terminating the pipeline.</p>
 *
 * @see SpectorIngestionException
 */
public class SpectorDocumentReadException extends SpectorIngestionException {

    private final String fileName;
    private final String reason;

    public SpectorDocumentReadException(String fileName, String reason) {
        super(ErrorCode.DOCUMENT_READ_FAILED, fileName, reason);
        this.fileName = fileName;
        this.reason = reason;
    }

    public SpectorDocumentReadException(String fileName, String reason, Throwable cause) {
        super(ErrorCode.DOCUMENT_READ_FAILED, cause, fileName, reason);
        this.fileName = fileName;
        this.reason = reason;
    }

    /** Returns the name of the file that could not be read. */
    public String getFileName() {
        return fileName;
    }

    /** Returns the reason the read failed. */
    public String getReason() {
        return reason;
    }
}
