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
 * Exception thrown when the memory consolidation process fails.
 *
 * @see SpectorMemoryException
 */
public class SpectorMemoryConsolidationException extends SpectorMemoryException {

    private final String details;

    public SpectorMemoryConsolidationException(String details) {
        super(ErrorCode.MEMORY_CONSOLIDATION_FAILED, details);
        this.details = details;
    }

    public SpectorMemoryConsolidationException(String details, Throwable cause) {
        super(ErrorCode.MEMORY_CONSOLIDATION_FAILED, cause, details);
        this.details = details;
    }

    /** Returns details of the memory consolidation failure. */
    public String getDetails() {
        return details;
    }
}
