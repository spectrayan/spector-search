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
