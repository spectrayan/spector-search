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
package com.spectrayan.spector.gpu.error;

import com.spectrayan.spector.commons.error.*;

/**
 * Exception thrown when a GPU memory operation fails.
 *
 * <p>Contains information about the requested allocation size and
 * the currently available device memory, enabling callers to make
 * informed decisions about memory management.</p>
 *
 * @see SpectorGpuException
 */
public class SpectorGpuMemoryException extends SpectorGpuException {

    private final long requestedBytes;
    private final long availableBytes;

    /**
     * Creates a GPU memory exception with allocation context.
     *
     * @param message        descriptive error message
     * @param requestedBytes the number of bytes that were requested
     * @param availableBytes the number of bytes available (or budget remaining)
     */
    public SpectorGpuMemoryException(String message, long requestedBytes, long availableBytes) {
        super(ErrorCode.GPU_MEMORY_EXHAUSTED, requestedBytes, availableBytes);
        this.requestedBytes = requestedBytes;
        this.availableBytes = availableBytes;
    }

    /**
     * Creates a GPU memory exception with a cause.
     *
     * @param message        descriptive error message
     * @param cause          the underlying cause
     * @param requestedBytes the number of bytes that were requested
     * @param availableBytes the number of bytes available (or budget remaining)
     */
    public SpectorGpuMemoryException(String message, Throwable cause, long requestedBytes, long availableBytes) {
        super(ErrorCode.GPU_MEMORY_EXHAUSTED, cause, requestedBytes, availableBytes);
        this.requestedBytes = requestedBytes;
        this.availableBytes = availableBytes;
    }

    /** Returns the number of bytes that were requested in the failed allocation. */
    public long getRequestedBytes() {
        return requestedBytes;
    }

    /** Returns the number of bytes available at the time of the failure. */
    public long getAvailableBytes() {
        return availableBytes;
    }
}
