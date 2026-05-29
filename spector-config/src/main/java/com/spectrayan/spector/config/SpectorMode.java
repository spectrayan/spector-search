package com.spectrayan.spector.config;

/**
 * Global operating mode for a Spector instance.
 *
 * <ul>
 *   <li>{@link #SEARCH} — traditional vector search engine (default)</li>
 *   <li>{@link #MEMORY} — cognitive memory mode with biological mechanisms
 *       (auto-enables memory, routes ingestion/search through memory pipeline)</li>
 * </ul>
 *
 * <p>Set via {@code spector.mode} in configuration (default: {@code search}).</p>
 */
public enum SpectorMode {

    /** Traditional vector search engine. */
    SEARCH,

    /** Cognitive memory mode with decay, consolidation, and importance scoring. */
    MEMORY
}
