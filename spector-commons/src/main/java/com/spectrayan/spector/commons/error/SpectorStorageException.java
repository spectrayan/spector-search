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
 * Exception for vector store, memory-mapped I/O, off-heap, and disk errors ({@code SPE-210-xxx}).
 *
 * <p>Covers memory segment lifecycle, mmap failures, store capacity, disk I/O,
 * WAL operations, and file format issues.</p>
 *
 * @see ErrorCode#SEGMENT_CLOSED
 * @see ErrorCode#MMAP_FAILED
 * @see ErrorCode#WAL_WRITE_FAILED
 */
public class SpectorStorageException extends SpectorException {

    public SpectorStorageException(ErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }

    public SpectorStorageException(ErrorCode errorCode, String preformattedMessage, boolean isPreformatted) {
        super(errorCode, preformattedMessage, isPreformatted);
    }

    public SpectorStorageException(ErrorCode errorCode, Throwable cause, Object... args) {
        super(errorCode, cause, args);
    }
}
