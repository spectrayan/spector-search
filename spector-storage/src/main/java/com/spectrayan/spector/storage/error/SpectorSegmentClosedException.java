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
 * Exception thrown when an operation is attempted on a closed memory segment or store.
 *
 * @see SpectorStorageException
 */
public class SpectorSegmentClosedException extends SpectorStorageException {

    public SpectorSegmentClosedException() {
        super(ErrorCode.SEGMENT_CLOSED);
    }

    public SpectorSegmentClosedException(Throwable cause) {
        super(ErrorCode.SEGMENT_CLOSED, cause);
    }
}
