package com.spectrayan.spector.config;

/**
 * Supported persistence modes for the search engine.
 */
public enum PersistenceMode {

    /** All data in memory — lost on shutdown. */
    IN_MEMORY,

    /** Data persisted to disk via memory-mapped files. Survives restarts. */
    DISK
}
