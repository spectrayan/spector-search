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
package com.spectrayan.spector.memory.sync;

import java.io.IOException;

/**
 * Thrown when the Write-Ahead Log (WAL) detects unrecoverable mid-log data corruption or checksum mismatch.
 */
public class WalCorruptionException extends IOException {
    
    public WalCorruptionException(String message) {
        super(message);
    }

    public WalCorruptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
