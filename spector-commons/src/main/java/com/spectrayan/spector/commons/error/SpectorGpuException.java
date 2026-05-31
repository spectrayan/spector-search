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
 * Exception for GPU and CUDA errors ({@code SPE-400-xxx}).
 *
 * <p>Thrown when CUDA driver is not found, GPU memory allocation fails,
 * kernel launches fail, or GPU device reports errors.</p>
 *
 * <p>Carries optional allocation context (requested bytes, available bytes)
 * for GPU memory exhaustion diagnostics.</p>
 *
 * @see ErrorCode#CUDA_DRIVER_NOT_FOUND
 * @see ErrorCode#GPU_MEMORY_EXHAUSTED
 */
public class SpectorGpuException extends SpectorException {

    private final long requestedBytes;
    private final long availableBytes;

    public SpectorGpuException(ErrorCode errorCode, Object... args) {
        super(errorCode, args);
        this.requestedBytes = -1;
        this.availableBytes = -1;
    }

    public SpectorGpuException(ErrorCode errorCode, String preformattedMessage, boolean isPreformatted) {
        super(errorCode, preformattedMessage, isPreformatted);
        this.requestedBytes = -1;
        this.availableBytes = -1;
    }

    public SpectorGpuException(ErrorCode errorCode, Throwable cause, Object... args) {
        super(errorCode, cause, args);
        this.requestedBytes = -1;
        this.availableBytes = -1;
    }

    /**
     * Creates a GPU exception with memory allocation context.
     *
     * @param errorCode      the GPU error code
     * @param requestedBytes bytes that were requested
     * @param availableBytes bytes that were available
     */
    public SpectorGpuException(ErrorCode errorCode, long requestedBytes, long availableBytes) {
        super(errorCode, requestedBytes, availableBytes);
        this.requestedBytes = requestedBytes;
        this.availableBytes = availableBytes;
    }

    /** Returns the requested allocation size, or {@code -1} if not applicable. */
    public long requestedBytes() {
        return requestedBytes;
    }

    /** Returns the available memory at failure time, or {@code -1} if not applicable. */
    public long availableBytes() {
        return availableBytes;
    }
}
