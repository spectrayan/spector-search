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
package com.spectrayan.spector.memory.graph;

/**
 * Configuration mode for entity extraction during memory ingestion.
 *
 * <p>Controls whether entities are extracted from memory text during ingestion
 * and which extraction strategy is used.</p>
 */
public enum EntityExtractionMode {
    /** No entity extraction (default). Entity graph features are disabled. */
    NONE,

    /** LLM-powered extraction via TextGenerationProvider. */
    LLM,

    /** Custom EntityExtractor provided via Builder. */
    CUSTOM
}
