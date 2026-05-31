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
 * Exception thrown when the vector store has reached its capacity limits.
 *
 * @see SpectorStorageException
 */
public class SpectorStoreFullException extends SpectorStorageException {

    private final int maxCapacity;

    public SpectorStoreFullException(int maxCapacity) {
        super(ErrorCode.STORE_FULL, maxCapacity);
        this.maxCapacity = maxCapacity;
    }

    public SpectorStoreFullException(int maxCapacity, Throwable cause) {
        super(ErrorCode.STORE_FULL, cause, maxCapacity);
        this.maxCapacity = maxCapacity;
    }

    /** Returns the maximum capacity of the vector store. */
    public int getMaxCapacity() {
        return maxCapacity;
    }
}
