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
package com.spectrayan.spector.storage.error;

import com.spectrayan.spector.commons.error.*;

/**
 * Exception thrown when a memory-mapped file creation or mapping fails.
 *
 * @see SpectorStorageException
 */
public class SpectorMmapException extends SpectorStorageException {

    private final String path;
    private final String details;

    public SpectorMmapException(String path, String details) {
        super(ErrorCode.MMAP_FAILED, path + ": " + details);
        this.path = path;
        this.details = details;
    }

    public SpectorMmapException(String path, String details, Throwable cause) {
        super(ErrorCode.MMAP_FAILED, cause, path + ": " + details);
        this.path = path;
        this.details = details;
    }

    /** Returns the path of the file that failed to map. */
    public String getPath() {
        return path;
    }

    /** Returns details of the mapping failure. */
    public String getDetails() {
        return details;
    }
}
