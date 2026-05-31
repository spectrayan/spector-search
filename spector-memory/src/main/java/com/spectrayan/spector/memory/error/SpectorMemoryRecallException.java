/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Business Source License 1.1 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://github.com/spectrayan/spector/blob/main/spector-memory/LICENSE
 *
 * Change Date: May 27, 2030
 * Change License: Apache License, Version 2.0
 */
package com.spectrayan.spector.memory.error;

import com.spectrayan.spector.commons.error.*;

/**
 * Exception thrown when the cognitive recall pipeline or memory identification fails.
 *
 * @see SpectorMemoryException
 */
public class SpectorMemoryRecallException extends SpectorMemoryException {

    private final String details;

    public SpectorMemoryRecallException(String details) {
        super(ErrorCode.MEMORY_RECALL_FAILED, details);
        this.details = details;
    }

    public SpectorMemoryRecallException(String details, Throwable cause) {
        super(ErrorCode.MEMORY_RECALL_FAILED, cause, details);
        this.details = details;
    }

    public SpectorMemoryRecallException(ErrorCode errorCode, String details) {
        super(errorCode, details);
        this.details = details;
    }

    public SpectorMemoryRecallException(ErrorCode errorCode, Throwable cause, String details) {
        super(errorCode, cause, details);
        this.details = details;
    }

    /** Returns details of the recall pipeline failure. */
    public String getDetails() {
        return details;
    }
}
