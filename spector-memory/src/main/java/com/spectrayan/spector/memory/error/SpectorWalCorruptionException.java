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

import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.commons.error.SpectorStorageException;

import java.nio.file.Path;

/**
 * Exception thrown when the Write-Ahead Log (WAL) detects unrecoverable
 * mid-log data corruption, checksum mismatch, or unsupported version.
 *
 * <p>Uses {@link ErrorCode#MEMORY_WAL_CORRUPTED} ({@code SPE-310-005}).</p>
 *
 * @see SpectorStorageException
 * @see ErrorCode#MEMORY_WAL_CORRUPTED
 */
public class SpectorWalCorruptionException extends SpectorStorageException {

    private final String walPath;

    /**
     * Creates a WAL corruption exception with a descriptive reason.
     *
     * @param reason description of the corruption (e.g., "CRC mismatch at position 128")
     */
    public SpectorWalCorruptionException(String reason) {
        super(ErrorCode.MEMORY_WAL_CORRUPTED, reason);
        this.walPath = null;
    }

    /**
     * Creates a WAL corruption exception for a specific WAL file with a reason.
     *
     * @param reason  description of the corruption
     * @param walPath the WAL file path where corruption was detected
     */
    public SpectorWalCorruptionException(String reason, Path walPath) {
        super(ErrorCode.MEMORY_WAL_CORRUPTED, reason + " in " + walPath);
        this.walPath = walPath != null ? walPath.toString() : null;
    }

    /**
     * Creates a WAL corruption exception with a cause.
     *
     * @param reason description of the corruption
     * @param cause  the underlying cause
     */
    public SpectorWalCorruptionException(String reason, Throwable cause) {
        super(ErrorCode.MEMORY_WAL_CORRUPTED, cause, reason);
        this.walPath = null;
    }

    /** Returns the path to the WAL file where corruption was detected, or null. */
    public String getWalPath() {
        return walPath;
    }
}
